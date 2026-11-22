package dev.demo.cachelie.service.naive;

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

/** STEP 1: the textbook setup. @Transactional + @CachePut / @Cacheable. Looks perfect, is not. */
@Service("naive")
public class NaiveProductService implements ProductOps {
    private static final Logger log = LoggerFactory.getLogger("demo.naive");
    private final ProductRepository repo;

    public NaiveProductService(ProductRepository repo) { this.repo = repo; }

    @Override
    @Cacheable(cacheNames = "products", key = "#id")
    public ProductView read(long id, long delayMs) {
        log.info("cache MISS for {} -> reading DB", id);
        ProductView view = ProductView.of(repo.findById(id).orElseThrow());
        ProductOps.pause(delayMs);
        return view;
    }

    @Override
    @Transactional
    @CachePut(cacheNames = "products", key = "#id")
    public ProductView update(long id, String name, int price) {
        Product p = repo.findById(id).orElseThrow();
        p.setName(name);
        p.setPrice(price);                       // not flushed yet: the SQL runs at COMMIT
        log.info("update body finished, handing the result back to the proxy chain");
        return new ProductView(id, name, price, p.getVersion() + 1);
    }
}
