package dev.demo.cachelie.service.versioned;

import dev.demo.cachelie.domain.Product;
import dev.demo.cachelie.domain.ProductRepository;
import dev.demo.cachelie.domain.ProductView;
import dev.demo.cachelie.service.ProductOps;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.CachePut;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * STEP 4: the same annotations as the aware service, but on versionedCacheManager. Every cache write carries the row
 * version, and cas.lua (inside VersionedCache) keeps only the higher one. The writer's put still waits for the commit.
 */
@Service("lua")
public class LuaProductService implements ProductOps {
    private static final Logger log = LoggerFactory.getLogger("demo.lua");
    private final ProductRepository repo;

    public LuaProductService(ProductRepository repo) { this.repo = repo; }

    @Override
    @Cacheable(cacheNames = "products", key = "#id", cacheManager = "versionedCacheManager")
    public ProductView read(long id, long delayMs) {
        log.info("cache MISS for {} -> reading DB", id);
        ProductView view = ProductView.of(repo.findById(id).orElseThrow());
        ProductOps.pause(delayMs);                          // a slow reader, between the DB read and the cache write
        return view;
    }

    /** flush() sends the UPDATE now, so @Version is already increased in the value we return. */
    @Override
    @Transactional
    @CachePut(cacheNames = "products", key = "#id", cacheManager = "versionedCacheManager")
    public ProductView update(long id, String name, int price) {
        Product p = repo.findById(id).orElseThrow();
        p.setName(name);
        p.setPrice(price);
        repo.flush();
        return ProductView.of(p);
    }
}
