package dev.demo.cachelie.service.support;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.demo.cachelie.domain.Tombstone;
import dev.demo.cachelie.domain.Versioned;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.Cache;
import org.springframework.cache.support.SimpleValueWrapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * A Spring {@link Cache} on Valkey that never goes back to an older version and never brings back a deleted row.
 *
 * <p><b>Keys</b> for product 1 in cache "products". The {@code {products:1}} part is a hash tag, so both keys live in
 * the same hash slot and one Lua script can use both:
 * <pre>
 *   tomb:{products:1}           hash: payload (the product as JSON) + ver (its version)    lives 10 minutes
 *   tomb:{products:1}:deleted   the version the row had when it was deleted                lives 1 hour
 * </pre>
 *
 * <p><b>Reading</b> is a plain lookup. <b>Writing</b> always goes through a Lua script, which checks the versions:
 * <ul>
 *   <li>a normal value goes through {@code tombstone/put.lua} (rejected if older, or if the row was deleted)</li>
 *   <li>a {@link Tombstone} (what a delete returns) goes through {@code tombstone/delete.lua}</li>
 * </ul>
 *
 * <p>Wrap it in a {@code TransactionAwareCacheDecorator}: then a write made inside a transaction waits for the commit.
 */
public class TombstoneCache implements Cache {

    private static final Logger log = LoggerFactory.getLogger("demo.tombstone");

    // The two scripts in src/main/resources/tombstone/. Loaded once; Valkey runs each one as a single atomic step.
    private static final RedisScript<String> PUT_SCRIPT = RedisScript.of(new ClassPathResource("tombstone/put.lua"), String.class);
    private static final RedisScript<String> DELETE_SCRIPT = RedisScript.of(new ClassPathResource("tombstone/delete.lua"), String.class);

    private static final String PAYLOAD = "payload";   // hash field that holds the JSON

    private final String name;                          // the cache name, e.g. "products"
    private final Class<? extends Versioned> valueType; // what the JSON is read back into, e.g. ProductView
    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private final Duration valueTtl;                    // how long a cached value lives
    private final Duration tombstoneTtl;                // how long a delete is remembered

    public TombstoneCache(String name, Class<? extends Versioned> valueType, StringRedisTemplate redis, ObjectMapper json,
                          Duration valueTtl, Duration tombstoneTtl) {
        this.name = name;
        this.valueType = valueType;
        this.redis = redis;
        this.json = json;
        this.valueTtl = valueTtl;
        this.tombstoneTtl = tombstoneTtl;
    }

    @Override public String getName() { return name; }
    @Override public Object getNativeCache() { return redis; }

    // ---------------------------------------------------------------- keys

    /** tomb:{products:1}: the cached value and its version. */
    private String valueKey(Object id) { return "tomb:{" + name + ":" + id + "}"; }

    /** tomb:{products:1}:deleted: the version the row had when it was deleted. */
    private String tombstoneKey(Object id) { return valueKey(id) + ":deleted"; }

    // ---------------------------------------------------------------- reading: a plain lookup, no Lua

    /** Hit only when a value is cached. Expired, evicted or deleted means a miss, so the caller reads the database. */
    @Override
    public ValueWrapper get(Object key) {
        Object cached = readCachedValue(key);
        return cached == null ? null : new SimpleValueWrapper(cached);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T get(Object key, Class<T> type) {
        Object cached = readCachedValue(key);
        if (cached != null && type != null && !type.isInstance(cached)) {
            throw new IllegalStateException("Cached value is not a " + type.getName() + ": " + cached);
        }
        return (T) cached;
    }

    /**
     * Read, or load and store on a miss. Note: this is NOT synchronized, so two threads can load the same key at the
     * same time. Don't use {@code @Cacheable(sync = true)} with this cache.
     */
    @Override
    @SuppressWarnings("unchecked")
    public <T> T get(Object key, Callable<T> loader) {
        Object cached = readCachedValue(key);
        if (cached != null) return (T) cached;
        try {
            T loaded = loader.call();
            put(key, loaded);
            return loaded;
        } catch (Exception e) {
            throw new ValueRetrievalException(key, loader, e);
        }
    }

    private Object readCachedValue(Object key) {
        Object payload = redis.opsForHash().get(valueKey(key), PAYLOAD);
        return payload == null ? null : fromJson(payload.toString());
    }

    // ---------------------------------------------------------------- writing: always through a Lua script

    /**
     * Called by {@code @Cacheable} (after a miss) and {@code @CachePut}.
     * A delete returns a {@link Tombstone}; anything else is a normal value. {@code null} ("not found") is never cached.
     *
     * <p>If Valkey can't be reached, the error is logged and the request carries on. For an update or a delete this
     * runs after the database has committed, so failing the request would report an error for a change that did
     * happen. The cache then keeps its old value until that value's TTL runs out.
     */
    @Override
    public void put(Object key, Object value) {
        if (value == null) return;
        try {
            if (value instanceof Tombstone deleted) {
                rememberDelete(key, deleted);
            } else {
                storeValue(key, value);
            }
        } catch (DataAccessException e) {
            log.warn("tombstone cache write for {} FAILED, the cache keeps its old value until its TTL: {}", key, e.getMessage());
        }
    }

    /** put.lua: stores the value unless it is older than the cached one (STALE) or the row was deleted (DELETED). */
    private void storeValue(Object key, Object value) {
        if (!valueType.isInstance(value)) {
            throw new IllegalArgumentException("Cache '" + name + "' only stores " + valueType.getSimpleName()
                    + ", got " + value.getClass().getName());
        }
        long version = ((Versioned) value).version();
        String result = runScript(PUT_SCRIPT, key, toJson(value), version, valueTtl);
        log.info("tombstone put {} version {} -> {}", key, version, result);
    }

    /** delete.lua: removes the cached value and remembers "deleted at this version" for tombstoneTtl. */
    private void rememberDelete(Object key, Tombstone deleted) {
        String result = runScript(DELETE_SCRIPT, key, null, deleted.version(), tombstoneTtl);
        log.info("tombstone delete {} version {} -> {}", key, deleted.version(), result);
    }

    /** Both scripts get the same two keys. put.lua also gets the JSON as its first argument. */
    private String runScript(RedisScript<String> script, Object key, String jsonOrNull, long version, Duration ttl) {
        List<String> keys = List.of(valueKey(key), tombstoneKey(key));
        String versionArg = String.valueOf(version);
        String ttlArg = String.valueOf(ttl.toSeconds());
        return jsonOrNull == null
                ? redis.execute(script, keys, versionArg, ttlArg)
                : redis.execute(script, keys, jsonOrNull, versionArg, ttlArg);
    }

    // ---------------------------------------------------------------- removing

    /**
     * Called by {@code @CacheEvict}, which has no version. Removes only the payload and keeps {@code ver}, so an older
     * write is still rejected and the same version can fill the cache again. The tombstone is not touched.
     */
    @Override
    public void evict(Object key) {
        redis.opsForHash().delete(valueKey(key), PAYLOAD);
    }

    /** Removes every value and tombstone of this cache. Uses SCAN (step by step), not KEYS, which would block Valkey. */
    @Override
    public void clear() {
        List<String> keys = new ArrayList<>();
        ScanOptions allOfThisCache = ScanOptions.scanOptions().match("tomb:{" + name + ":*").count(500).build();
        try (Cursor<String> cursor = redis.scan(allOfThisCache)) {
            cursor.forEachRemaining(keys::add);
        }
        if (!keys.isEmpty()) redis.delete(keys);
    }

    // ---------------------------------------------------------------- JSON

    private String toJson(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Cannot write " + value + " as JSON", e);
        }
    }

    private Object fromJson(String payload) {
        try {
            return json.readValue(payload, valueType);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot read the cached " + valueType.getSimpleName(), e);
        }
    }
}
