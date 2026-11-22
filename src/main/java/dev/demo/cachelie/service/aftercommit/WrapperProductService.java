package dev.demo.cachelie.service.aftercommit;

import dev.demo.cachelie.domain.Product;
import dev.demo.cachelie.domain.ProductRepository;
import dev.demo.cachelie.domain.ProductView;
import dev.demo.cachelie.service.ProductOps;
import dev.demo.cachelie.service.support.AfterCommitCache;
import dev.demo.cachelie.service.support.ManualRead;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** FIX #2b: the service talks to the cache only through AfterCommitCache, so it cannot touch it before the commit. */
@Service("wrapper")
public class WrapperProductService implements ProductOps {
    private static final Logger log = LoggerFactory.getLogger("demo.wrapper");
    private final ProductRepository repo;
    private final Cache cache;

    public WrapperProductService(ProductRepository repo, CacheManager cacheManager) {
        this.repo = repo;
        this.cache = new AfterCommitCache(cacheManager.getCache("products"));
    }

    @Override
    public ProductView read(long id, long delayMs) {
        return ManualRead.read(cache, repo, log, id, delayMs);
    }

    @Override
    @Transactional
    public ProductView update(long id, String name, int price) {
        Product p = repo.findById(id).orElseThrow();
        p.setName(name);
        p.setPrice(price);
        cache.evict(id);                                     // deferred to afterCommit by the wrapper
        log.info("update body finished, evict registered for after the commit");
        return new ProductView(id, name, price, p.getVersion() + 1);
    }
}
