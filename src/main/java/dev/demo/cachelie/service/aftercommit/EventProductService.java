package dev.demo.cachelie.service.aftercommit;

import dev.demo.cachelie.domain.Product;
import dev.demo.cachelie.domain.ProductRepository;
import dev.demo.cachelie.domain.ProductView;
import dev.demo.cachelie.service.ProductOps;
import dev.demo.cachelie.service.support.ManualRead;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.CacheManager;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** FIX #2 (events): publish an event inside the transaction, evict in a @TransactionalEventListener(AFTER_COMMIT). */
@Service("event")
public class EventProductService implements ProductOps {
    private static final Logger log = LoggerFactory.getLogger("demo.event");
    private final ProductRepository repo;
    private final CacheManager cacheManager;
    private final ApplicationEventPublisher events;

    public EventProductService(ProductRepository repo, CacheManager cacheManager, ApplicationEventPublisher events) {
        this.repo = repo;
        this.cacheManager = cacheManager;
        this.events = events;
    }

    @Override
    public ProductView read(long id, long delayMs) {
        return ManualRead.read(cacheManager.getCache("products"), repo, log, id, delayMs);
    }

    @Override
    @Transactional
    public ProductView update(long id, String name, int price) {
        Product p = repo.findById(id).orElseThrow();
        p.setName(name);
        p.setPrice(price);                                   // not flushed yet: the SQL runs at COMMIT
        events.publishEvent(new ProductChanged(id));         // delivered only after a successful commit
        log.info("update body finished, event published");
        return new ProductView(id, name, price, p.getVersion() + 1);
    }
}
