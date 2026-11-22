package dev.demo.cachelie.domain;

public record ProductView(long id, String name, int price, long version) {
    public static ProductView of(Product p) { return new ProductView(p.getId(), p.getName(), p.getPrice(), p.getVersion()); }
}
