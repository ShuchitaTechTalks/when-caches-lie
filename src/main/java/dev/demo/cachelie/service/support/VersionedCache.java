package dev.demo.cachelie.service.support;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.demo.cachelie.domain.Versioned;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Callable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.Cache;
import org.springframework.cache.support.SimpleValueWrapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * A Spring {@link Cache} on Valkey where every write goes through {@code cas.lua}: a value is stored only if its
 * version is newer than the cached one, otherwise Valkey answers {@code STALE}. So {@code @Cacheable} and
 * {@code @CachePut} can't put an older version over a newer one.
 *
 * <p>Product 1 is stored as the hash {@code lua:product:1} with the fields {@code payload} (JSON) and {@code ver}.
 * Wrap it in a {@code TransactionAwareCacheDecorator} so that a put inside a transaction waits for the commit.
 */
public class VersionedCache implements Cache {

    private static final Logger log = LoggerFactory.getLogger("demo.lua");
    private static final RedisScript<String> CAS = RedisScript.of(new ClassPathResource("cas.lua"), String.class);

    private final String name;
    private final String keyPrefix;                     // "lua:product:"
    private final Class<? extends Versioned> valueType; // what the JSON is read back into, e.g. ProductView
    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private final Duration ttl;

    public VersionedCache(String name, String keyPrefix, Class<? extends Versioned> valueType,
                          StringRedisTemplate redis, ObjectMapper json, Duration ttl) {
        this.name = name;
        this.keyPrefix = keyPrefix;
        this.valueType = valueType;
        this.redis = redis;
        this.json = json;
        this.ttl = ttl;
    }

    @Override public String getName() { return name; }
    @Override public Object getNativeCache() { return redis; }

    private String key(Object id) { return keyPrefix + id; }

    // ---------------------------------------------------------------- reading: a plain lookup

    @Override
    public ValueWrapper get(Object key) {
        Object cached = read(key);
        return cached == null ? null : new SimpleValueWrapper(cached);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T get(Object key, Class<T> type) { return (T) read(key); }

    /** Not synchronized: don't use {@code @Cacheable(sync = true)} with this cache. */
    @Override
    @SuppressWarnings("unchecked")
    public <T> T get(Object key, Callable<T> loader) {
        Object cached = read(key);
        if (cached != null) return (T) cached;
        try {
            T loaded = loader.call();
            put(key, loaded);
            return loaded;
        } catch (Exception e) {
            throw new ValueRetrievalException(key, loader, e);
        }
    }

    private Object read(Object key) {
        Object payload = redis.opsForHash().get(key(key), "payload");
        if (payload == null) return null;
        try {
            return json.readValue(payload.toString(), valueType);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    // ---------------------------------------------------------------- writing: always through cas.lua

    /** Called by {@code @Cacheable} after a miss and by {@code @CachePut}. Valkey keeps the higher version. */
    @Override
    public void put(Object key, Object value) {
        if (value == null) return;
        long version = ((Versioned) value).version();
        String result;
        try {
            result = redis.execute(CAS, List.of(key(key)), json.writeValueAsString(value),
                    String.valueOf(version), String.valueOf(ttl.toSeconds()));
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(e);
        }
        log.info("CAS put {} version {} -> {}", key, version, result);
    }

    @Override public void evict(Object key) { redis.delete(key(key)); }

    @Override public void clear() { redis.delete(redis.keys(keyPrefix + "*")); }
}
