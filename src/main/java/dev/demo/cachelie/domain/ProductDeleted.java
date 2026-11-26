package dev.demo.cachelie.domain;

/** What a delete returns: the id and the version the row had when it was deleted (a delete does not change the version). */
public record ProductDeleted(long id, long version) implements Tombstone {
}
