package dev.demo.cachelie.service;

import dev.demo.cachelie.domain.ProductView;

public interface ProductOps {
    ProductView read(long id, long delayMs);
    ProductView update(long id, String name, int price);

    static void pause(long ms) {
        if (ms <= 0) return;
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
