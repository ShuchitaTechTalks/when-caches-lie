package dev.demo.cachelie.service.nested;

import dev.demo.cachelie.domain.ProductView;
import dev.demo.cachelie.service.ProductOps;
import dev.demo.cachelie.service.naive.NaiveProductService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * NESTED BREAK. A checkout service opens the transaction, then asks the naive product service to update the product.
 * Implements ProductOps only so that /nested/products/{id} reaches it.
 *
 *   (1) @Transactional here starts the REAL transaction.
 *   (2) productService.update() has @CachePut: Valkey gets Mouse 999 as it returns. Its own @Transactional only JOINS.
 *   (3) The commit happens when THIS method returns. It fails, Postgres rolls back, Valkey keeps Mouse 999.
 */
@Service("nested")
public class CheckoutService implements ProductOps {
    private static final Logger log = LoggerFactory.getLogger("demo.nested");

    private final NaiveProductService productService;          // a DIFFERENT bean (its Spring proxy), not this one

    public CheckoutService(NaiveProductService productService) { this.productService = productService; }

    @Override
    @Transactional                                              // (1) the real transaction starts here
    public ProductView update(long id, String name, int price) {
        log.info("checkout: transaction opened, calling the product service");
        ProductView view = productService.update(id, name, price);   // (2) cache written here
        log.info("checkout: product service returned, cache already written; committing now");
        return view;
    }                                                           // (3) commit here -> fails -> rollback

    @Override
    public ProductView read(long id, long delayMs) { return productService.read(id, delayMs); }
}
