package dev.demo.cachelie.service.support;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.Cache;

import java.util.concurrent.Callable;

/**
 * FIX #2b: a reusable wrapper around any Cache. put and evict wait for the commit; everything else delegates.
 * Spring's TransactionAwareCacheDecorator does the same job; write your own when you need retries or logging.
 */
public class AfterCommitCache implements Cache {
    private static final Logger log = LoggerFactory.getLogger("demo.wrapper");
    private final Cache delegate;

    public AfterCommitCache(Cache delegate) { this.delegate = delegate; }

    @Override public void put(Object key, Object value) { later("put", key, () -> delegate.put(key, value)); }
    @Override public void evict(Object key)             { later("evict", key, () -> delegate.evict(key)); }

    private void later(String what, Object key, Runnable op) {
        Tx.afterCommit(() -> {
            try {
                op.run();
                log.info("afterCommit: {} {}", what, key);
            } catch (RuntimeException e) {
                // The DB has already committed. A real app would retry or hand this to an outbox; a short TTL is the backstop.
                log.warn("post-commit cache {} failed for {}: {}", what, key, e.toString());
            }
        });
    }

    @Override public String getName()                                { return delegate.getName(); }
    @Override public Object getNativeCache()                         { return delegate.getNativeCache(); }
    @Override public ValueWrapper get(Object key)                    { return delegate.get(key); }
    @Override public <T> T get(Object key, Class<T> type)            { return delegate.get(key, type); }
    @Override public <T> T get(Object key, Callable<T> valueLoader)  { return delegate.get(key, valueLoader); }
    @Override public ValueWrapper putIfAbsent(Object key, Object v)  { return delegate.putIfAbsent(key, v); }
    @Override public void clear()                                    { delegate.clear(); }
}
