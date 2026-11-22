package dev.demo.cachelie.service.support;

import dev.demo.cachelie.domain.ProductRepository;
import dev.demo.cachelie.domain.ProductView;
import dev.demo.cachelie.service.ProductOps;
import org.slf4j.Logger;
import org.springframework.cache.Cache;

/** The read path shared by the fix #2 services: cache-aside with a slow reader between the DB read and the cache put. */
public final class ManualRead {
    private ManualRead() { }

    public static ProductView read(Cache cache, ProductRepository repo, Logger log, long id, long delayMs) {
        ProductView hit = cache.get(id, ProductView.class);
        if (hit != null) { log.info("cache HIT for {}", id); return hit; }
        log.info("cache MISS for {} -> reading DB", id);
        ProductView view = ProductView.of(repo.findById(id).orElseThrow());
        ProductOps.pause(delayMs);                         // the reader is slow between DB read and cache put
        cache.put(id, view);
        log.info("reader put {} (price {}) into the cache", id, view.price());
        return view;
    }
}
