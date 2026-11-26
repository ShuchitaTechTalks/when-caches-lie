package dev.demo.cachelie.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.demo.cachelie.domain.ProductView;
import dev.demo.cachelie.service.support.TombstoneCache;
import dev.demo.cachelie.service.support.VersionedCache;
import java.time.Duration;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.CacheManager;
import org.springframework.cache.support.SimpleCacheManager;
import org.springframework.cache.transaction.TransactionAwareCacheDecorator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext.SerializationPair;

@Configuration
public class CacheConfig {

    /** Deliberately NOT transactionAware(), so the framework default behaviour is visible. JSON values read well in the viewer. */
    @Bean
    @Primary
    RedisCacheManager cacheManager(RedisConnectionFactory cf) {
        return RedisCacheManager.builder(cf).cacheDefaults(defaults()).build();
    }

    /** FIX #2 (transactionAware): same cache, same keys, but put and evict wait for the commit. Used only by the "aware" service. */
    @Bean
    RedisCacheManager awareCacheManager(RedisConnectionFactory cf) {
        return RedisCacheManager.builder(cf).cacheDefaults(defaults()).transactionAware().build();
    }

    /**
     * FIX #3 (lua): every write goes through cas.lua, which keeps only the higher version. Wrapped in
     * TransactionAwareCacheDecorator, so the writer's @CachePut waits for the commit. Keys lua:product:<id>.
     */
    @Bean
    CacheManager versionedCacheManager(StringRedisTemplate redis, ObjectMapper json) {
        SimpleCacheManager manager = new SimpleCacheManager();
        manager.setCaches(List.of(new TransactionAwareCacheDecorator(
                new VersionedCache("products", "lua:product:", ProductView.class, redis, json, Duration.ofMinutes(10)))));
        return manager;
    }

    /**
     * EXTRA (not in the talk): versioned writes and deletes with a tombstone, see TombstoneCache and
     * docs/specs/delete-tombstone.md. Wrapped in TransactionAwareCacheDecorator, so a put inside a transaction
     * (an update or a delete) waits for the commit, and a rolled-back one never reaches the cache.
     */
    @Bean
    CacheManager tombstoneCacheManager(StringRedisTemplate redis, ObjectMapper json,
                                       @Value("${demo.tombstone.ttl:1h}") Duration tombstoneTtl) {
        SimpleCacheManager manager = new SimpleCacheManager();
        manager.setCaches(List.of(new TransactionAwareCacheDecorator(
                new TombstoneCache("products", ProductView.class, redis, json, Duration.ofMinutes(10), tombstoneTtl))));
        return manager;
    }

    private static RedisCacheConfiguration defaults() {
        return RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(Duration.ofMinutes(10))
                .serializeValuesWith(SerializationPair.fromSerializer(new Jackson2JsonRedisSerializer<>(ProductView.class)));
    }
}
