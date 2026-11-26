package dev.demo.cachelie.service;

import dev.demo.cachelie.domain.ProductDeleted;
import dev.demo.cachelie.domain.ProductView;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public interface ProductOps {
    /** Returns null when the product doesn't exist (the controller answers 404). */
    ProductView read(long id, long delayMs);
    ProductView update(long id, String name, int price);

    /** Only the "tombstone" mode supports deletes; every other mode answers 405. */
    default ProductDeleted delete(long id) {
        throw new ResponseStatusException(HttpStatus.METHOD_NOT_ALLOWED, "delete is only supported by the 'tombstone' mode");
    }

    static void pause(long ms) {
        if (ms <= 0) return;
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
