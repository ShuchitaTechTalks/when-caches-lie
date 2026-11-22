package dev.demo.cachelie.service.nested;

import dev.demo.cachelie.domain.ProductView;
import dev.demo.cachelie.service.ProductOps;
import dev.demo.cachelie.service.aftercommit.AwareProductService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * NESTED FIX. Identical to CheckoutService, except the product service underneath is AwareProductService, which uses
 * the transactionAware() cache manager. Its @CachePut is deferred until THIS transaction commits, so a rollback drops it.
 */
@Service("nestedaware")
public class CheckoutAwareService implements ProductOps {
    private static final Logger log = LoggerFactory.getLogger("demo.nested");

    private final AwareProductService productService;          // the only difference from CheckoutService

    public CheckoutAwareService(AwareProductService productService) { this.productService = productService; }

    @Override
    @Transactional                                              // (1) the real transaction starts here
    public ProductView update(long id, String name, int price) {
        log.info("checkout(aware): transaction opened, calling the product service");
        ProductView view = productService.update(id, name, price);   // (2) put only REGISTERED, not written
        log.info("checkout(aware): product service returned, cache put waits for the commit");
        return view;
    }                                                           // (3) commit fails -> registered put is discarded

    @Override
    public ProductView read(long id, long delayMs) { return productService.read(id, delayMs); }
}
