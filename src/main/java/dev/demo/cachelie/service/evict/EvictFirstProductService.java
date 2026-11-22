package dev.demo.cachelie.service.evict;

import dev.demo.cachelie.domain.Product;
import dev.demo.cachelie.domain.ProductRepository;
import dev.demo.cachelie.domain.ProductView;
import dev.demo.cachelie.service.ProductOps;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * EVICT BREAK. "Evict first, then write" with @CacheEvict(beforeInvocation = true). While the transaction is still open,
 * a reader misses, reads the OLD committed row and caches it. Then the writer commits. The cache keeps the old value
 * until the TTL, and no later evict is coming.
 */
@Service("evict")
public class EvictFirstProductService implements ProductOps {
    private static final Logger log = LoggerFactory.getLogger("demo.evict");
    static final long SLOW_COMMIT_MS = 10_000;                  // stands in for a slow flush, triggers, a long batch
    private final ProductRepository repo;

    public EvictFirstProductService(ProductRepository repo) { this.repo = repo; }

    @Override
    @Cacheable(cacheNames = "products", key = "#id")
    public ProductView read(long id, long delayMs) {
        log.info("cache MISS for {} -> reading DB (sees only COMMITTED data)", id);
        return ProductView.of(repo.findById(id).orElseThrow());
    }

    @Override
    @Transactional
    @CacheEvict(cacheNames = "products", key = "#id", beforeInvocation = true)   // (1) the cache is emptied FIRST
    public ProductView update(long id, String name, int price) {
        Product p = repo.findById(id).orElseThrow();
        p.setName(name);
        p.setPrice(price);
        log.info("evict: cache already evicted; transaction stays open for {} s, send the reader now", SLOW_COMMIT_MS / 1000);
        ProductOps.pause(SLOW_COMMIT_MS);                       // (2) a reader now misses and re-caches the OLD row
        return new ProductView(id, name, price, p.getVersion() + 1);
    }                                                           // (3) commit: DB has the new price, the cache the old one
}
