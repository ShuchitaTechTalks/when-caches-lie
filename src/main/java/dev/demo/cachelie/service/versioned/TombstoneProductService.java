package dev.demo.cachelie.service.versioned;

import dev.demo.cachelie.domain.Product;
import dev.demo.cachelie.domain.ProductDeleted;
import dev.demo.cachelie.domain.ProductRepository;
import dev.demo.cachelie.domain.ProductView;
import dev.demo.cachelie.service.ProductOps;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.CachePut;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * EXTRA (not in the talk): versioned updates AND deletes behind the standard Spring cache annotations.
 * All the rules live in TombstoneCache (tombstoneCacheManager); this class only returns values that carry a version.
 * A delete returns ProductDeleted, so it uses @CachePut, never @CacheEvict (an evict can't carry a version).
 */
@Service("tombstone")
public class TombstoneProductService implements ProductOps {
    private static final Logger log = LoggerFactory.getLogger("demo.tombstone");
    private final ProductRepository repo;

    public TombstoneProductService(ProductRepository repo) { this.repo = repo; }

    /** A deleted row returns null: nothing is cached and the controller answers 404. */
    @Override
    @Cacheable(cacheNames = "products", key = "#id", cacheManager = "tombstoneCacheManager")
    public ProductView read(long id, long delayMs) {
        log.info("cache MISS for {} -> reading DB", id);
        ProductView view = repo.findById(id).map(ProductView::of).orElse(null);
        ProductOps.pause(delayMs);                                   // a slow reader, between the DB read and the cache write
        return view;
    }

    /** flush() sends the UPDATE now, so @Version is already increased in the value we return. */
    @Override
    @Transactional
    @CachePut(cacheNames = "products", key = "#id", cacheManager = "tombstoneCacheManager")
    public ProductView update(long id, String name, int price) {
        Product p = repo.findById(id).orElseThrow(() -> notFound(id));
        p.setName(name);
        p.setPrice(price);
        repo.flush();
        return ProductView.of(p);
    }

    /** Hard delete. JPA deletes with "WHERE version = ?", so the version we return is the row's last committed one. */
    @Override
    @Transactional
    @CachePut(cacheNames = "products", key = "#id", cacheManager = "tombstoneCacheManager")
    public ProductDeleted delete(long id) {
        Product p = repo.findById(id).orElseThrow(() -> notFound(id));
        long version = p.getVersion();
        repo.delete(p);
        repo.flush();
        return new ProductDeleted(id, version);
    }

    private static ResponseStatusException notFound(long id) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "product " + id + " not found");
    }
}
