package dev.demo.cachelie.service.aftercommit;

/** Published inside the transaction. Delivered to the listener only if the transaction commits. */
public record ProductChanged(long id) { }
