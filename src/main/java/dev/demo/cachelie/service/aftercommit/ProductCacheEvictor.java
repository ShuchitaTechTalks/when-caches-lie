package dev.demo.cachelie.service.aftercommit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/** FIX #2 (events): the cache is touched only after a successful commit. A rollback never delivers the event. */
@Component
public class ProductCacheEvictor {
    private static final Logger log = LoggerFactory.getLogger("demo.event");
    private final CacheManager cacheManager;

    public ProductCacheEvictor(CacheManager cacheManager) { this.cacheManager = cacheManager; }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void on(ProductChanged e) {
        cacheManager.getCache("products").evict(e.id());
        log.info("AFTER_COMMIT event: evicted {}", e.id());
    }
}
