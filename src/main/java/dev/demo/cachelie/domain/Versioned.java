package dev.demo.cachelie.domain;

/** A cached value that carries the database version (@Version) of the row it was read from. */
public interface Versioned {
    long version();
}
