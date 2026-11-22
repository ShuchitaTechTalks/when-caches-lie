/**
 * Demo 7: the evict-first race. A reader runs WHILE the writer's transaction is still open.
 *   evict     = EvictFirstProductService       @CacheEvict(beforeInvocation = true), slow commit  -> stale cache
 *   evictlate = EvictAfterCommitProductService  evict in afterCommit(), slow commit                -> in sync
 * The writer holds its transaction open for SLOW_COMMIT_MS so the reader can be sent by hand in between.
 * Breaks under every proxy order: the evict runs before the method, so before the commit either way.
 */
package dev.demo.cachelie.service.evict;
