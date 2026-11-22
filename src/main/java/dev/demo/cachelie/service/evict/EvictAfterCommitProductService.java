package dev.demo.cachelie.service.evict;

import dev.demo.cachelie.domain.Product;
import dev.demo.cachelie.domain.ProductRepository;
import dev.demo.cachelie.domain.ProductView;
import dev.demo.cachelie.service.ProductOps;
import dev.demo.cachelie.service.support.Tx;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * EVICT FIX. The same slow write, but the evict is registered with Tx.afterCommit (demo 2's idea). A reader during the
 * transaction just gets the old, still-committed value, and the evict after the commit clears it.
 */
@Service("evictlate")
public class EvictAfterCommitProductService implements ProductOps {
    private static final Logger log = LoggerFactory.getLogger("demo.evict");
    private final ProductRepository repo;
    private final Cache cache;

    public EvictAfterCommitProductService(ProductRepository repo, CacheManager cacheManager) {
        this.repo = repo;
        this.cache = cacheManager.getCache("products");
    }

    @Override
    @Cacheable(cacheNames = "products", key = "#id")
    public ProductView read(long id, long delayMs) {
        log.info("cache MISS for {} -> reading DB (sees only COMMITTED data)", id);
        return ProductView.of(repo.findById(id).orElseThrow());
    }

    @Override
    @Transactional
    public ProductView update(long id, String name, int price) {
        Product p = repo.findById(id).orElseThrow();
        p.setName(name);
        p.setPrice(price);
        Tx.afterCommit(() -> { cache.evict(id); log.info("evictlate: committed, evicted {}", id); });   // (1) CHANGED
        log.info("evictlate: nothing evicted yet; transaction stays open for {} s, send the reader now",
                EvictFirstProductService.SLOW_COMMIT_MS / 1000);
        ProductOps.pause(EvictFirstProductService.SLOW_COMMIT_MS);
        return new ProductView(id, name, price, p.getVersion() + 1);
    }                                                           // commit, then the evict runs
}
