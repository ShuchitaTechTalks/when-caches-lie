package dev.demo.cachelie.config;

import dev.demo.cachelie.domain.ProductView;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
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

    private static RedisCacheConfiguration defaults() {
        return RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(Duration.ofMinutes(10))
                .serializeValuesWith(SerializationPair.fromSerializer(new Jackson2JsonRedisSerializer<>(ProductView.class)));
    }
}
