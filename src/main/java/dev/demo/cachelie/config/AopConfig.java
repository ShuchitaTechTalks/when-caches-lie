package dev.demo.cachelie.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/**
 * Selects the proxy-chain order with demo.aop.order. A LOWER order value is the OUTER proxy.
 *   tx-outer      (default) transaction forced outermost: this reproduces the failure on purpose
 *   default       no order given, both advisors tie at LOWEST_PRECEDENCE: see what YOUR setup does
 *   cache-outer   cache forced outermost (used only later, when we fix)
 * ProxyChainReporter prints the result at startup and at GET /admin/chain.
 */
@Configuration
public class AopConfig {
    /** Transaction wraps cache: the cache put/evict runs INSIDE the transaction (the bug). */
    @Configuration
    @ConditionalOnProperty(name = "demo.aop.order", havingValue = "tx-outer", matchIfMissing = true)
    @EnableTransactionManagement(order = Ordered.HIGHEST_PRECEDENCE)
    @EnableCaching(order = Ordered.LOWEST_PRECEDENCE)
    static class TransactionOuter { }

    /** No order given: both advisors tie at LOWEST_PRECEDENCE, so the result is whatever the registration order gives. */
    @Configuration
    @ConditionalOnProperty(name = "demo.aop.order", havingValue = "default")
    @EnableTransactionManagement
    @EnableCaching
    static class FrameworkDefault { }

    /** Cache wraps transaction: the cache op runs AFTER the commit, and a failed commit skips it. */
    @Configuration
    @ConditionalOnProperty(name = "demo.aop.order", havingValue = "cache-outer")
    @EnableTransactionManagement(order = Ordered.LOWEST_PRECEDENCE)
    @EnableCaching(order = Ordered.HIGHEST_PRECEDENCE)
    static class CacheOuter { }
}
