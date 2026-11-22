package dev.demo.cachelie.domain;

import jakarta.persistence.*;

@Entity
@Table(name = "product")
public class Product {
    @Id
    private Long id;
    @Column(nullable = false, unique = true)   // the "late constraint violation" comes from here
    private String name;
    private int price;
    @Version
    private long version;

    protected Product() { }
    public Product(Long id, String name, int price) { this.id = id; this.name = name; this.price = price; }

    public Long getId() { return id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public int getPrice() { return price; }
    public void setPrice(int price) { this.price = price; }
    public long getVersion() { return version; }
}
