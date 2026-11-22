package dev.demo.cachelie.service.aftercommit;

import dev.demo.cachelie.domain.Product;
import dev.demo.cachelie.domain.ProductRepository;
import dev.demo.cachelie.domain.ProductView;
import dev.demo.cachelie.service.ProductOps;
import dev.demo.cachelie.service.support.Tx;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** STEP 2: evict only after the commit succeeded. Fixes the ghost, but not the delayed-put race (STEP 3). */
@Service("safe")
public class SafeProductService implements ProductOps {
    private static final Logger log = LoggerFactory.getLogger("demo.safe");
    private final ProductRepository repo;
    private final CacheManager cacheManager;

    public SafeProductService(ProductRepository repo, CacheManager cacheManager) {
        this.repo = repo;
        this.cacheManager = cacheManager;
    }

    private Cache cache() { return cacheManager.getCache("products"); }

    @Override
    public ProductView read(long id, long delayMs) {
        ProductView hit = cache().get(id, ProductView.class);
        if (hit != null) { log.info("cache HIT for {}", id); return hit; }
        log.info("cache MISS for {} -> reading DB", id);
        ProductView view = ProductView.of(repo.findById(id).orElseThrow());
        ProductOps.pause(delayMs);                         // the reader is slow between DB read and cache put
        cache().put(id, view);
        log.info("reader put {} (price {}) into the cache", id, view.price());
        return view;
    }

    @Override
    @Transactional
    public ProductView update(long id, String name, int price) {
        Product p = repo.findById(id).orElseThrow();
        p.setName(name);
        p.setPrice(price);
        Tx.afterCommit(() -> { cache().evict(id); log.info("afterCommit: evicted {}", id); });
        return new ProductView(id, name, price, p.getVersion() + 1);
    }
}
