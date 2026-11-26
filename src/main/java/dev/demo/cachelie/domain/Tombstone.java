package dev.demo.cachelie.domain;

/** Marker for a cache value that means "this row was deleted at version()". A tombstone-aware cache turns it into a tombstone. */
public interface Tombstone extends Versioned {
}
