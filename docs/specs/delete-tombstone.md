# Spec: versioned delete with a tombstone

**Status:** implemented as the `tombstone` mode (not shown in the talk).
**Scope:** `cache-demo` only. `cache-demo-broken` is unchanged.

---

## 1. The problem

The version-guarded cache (`lua` mode, `cas.lua`) only protects the cache **while the stored version exists**. A delete removes the entry and its version with it. A slow reader that loaded the row *before* the delete then finds no version, and its old value wins. The deleted product comes back into the cache: a **zombie**.

```
reader:  GET product 1 → reads Keyboard 100, version 0 ── stalls ─────────────────────┐
writer:              DELETE product 1, commit, remove the cache entry                 │
reader:                                                       put(version 0) → no version to compare → accepted
users:   GET → Keyboard 100    (the row no longer exists)
```

## 2. Requirements

| # | Requirement |
|---|---|
| R1 | After the database deletes a row, the cache stops serving it. |
| R2 | An older version never gets back into the cache: after an update, an expiry, an invalidation or a delete. |
| R3 | Legitimate cache fills always work, including refilling the **same** current version after the cached value expired. |
| C1 | Versions come only from the database (JPA `@Version`). Every update increases the version; **a delete does not**. |
| C2 | Hard delete. No soft-delete column. |
| C3 | Ids come from a sequence and are **never reused**. |

## 3. How the industry handles this

| Approach | Blocks stale writes after an update | Handles deletes | Needs versions | Cost |
|---|---|---|---|---|
| TTL only (plain cache-aside) | No | No: stale until the TTL | No | Lowest |
| Evict after commit + TTL (Spring `afterCommit` / `transactionAware()`) | No: the slow-reader race remains | Yes | No | Low |
| "Delayed double delete" | Lowers the odds only | Yes | No | Low |
| Leases (a token per cache miss; a delete invalidates outstanding tokens) | Yes | Yes | No | Medium: a custom token protocol |
| **Version check-and-set + tombstone (this spec)** | **Yes** | **Yes** | Yes, from the database | Medium |

Leases are described in Nishtala et al., *Scaling Memcache at Facebook*, NSDI 2013 ("leases to address two problems: stale sets and thundering herds"). This spec uses the database version instead of lease tokens, because the app already has `@Version`. The version plays the role of a fencing token.

## 4. Design

### 4.1 Keys

Both keys share the hash tag `{products:<id>}`, so they live in the same hash slot and one Lua script can use both atomically, including in Valkey Cluster.

| Key | Type | Holds | TTL |
|---|---|---|---|
| `tomb:{products:1}` | hash | `payload` (the product as JSON), `ver` (its version) | 10 minutes |
| `tomb:{products:1}:deleted` | string | **the version the row had when it was deleted** | 1 hour (`demo.tombstone.ttl`) |

The tombstone TTL only has to outlive the slowest reader (the longest gap between a reader's database read and its cache write). One hour is far above that. Because ids are never reused (C3), a long TTL never blocks a legitimate row.

### 4.2 Rules

All rules run inside one Lua script per operation, so the check and the write are one atomic step.

| Operation | Rule |
|---|---|
| **Write** at version `v` (a writer after the commit, or a reader filling the cache) | 1. A tombstone exists and `v <= tombstone` → `DELETED`, nothing written.<br>2. A cached version exists and `v < ver` → `STALE`, nothing written.<br>3. Otherwise write `payload` and `ver = v`, and reset the 10-minute TTL → `UPDATED`. An **equal** version is an identical row, so refilling it is allowed (R3). |
| **Delete** of a row that was at version `v` | Remove the value key. Set the tombstone to `v` (C1: no version increase), unless a tombstone with a higher version is already there. → `DELETED` |
| **Evict** (no version available) | Remove only `payload`. `ver` stays until the key's TTL, so an older write is still rejected (R2) and the same version can refill (R3). |

The difference between the write rule (`<` is stale) and the tombstone rule (`<=` is deleted) is the whole trick: the deleted row's own last version is blocked, without changing the version on delete.

### 4.3 Why the requirements hold

| Situation | What happens |
|---|---|
| Slow reader holds `v`, the row is deleted at `v` | `v <= tombstone (v)` → `DELETED`. **R1, R2** |
| A reader arrives after the delete | The row isn't in the database, so `read()` returns nothing and nothing is cached. **R1** |
| The row is updated to `v+1`, a slow reader holds `v` | The writer's after-commit put sets `ver = v+1`; the reader's `v < v+1` → `STALE`. **R2** |
| The cached value expires, the row is still at `v` | No tombstone; the refill carries `v` → written. **R3** |
| A delete rolls back | The cache write happens after the commit only, so no tombstone is written. |

### 4.4 Lua scripts (`src/main/resources/tombstone/`)

**`put.lua`**

```lua
-- KEYS[1] = value hash   KEYS[2] = tombstone   ARGV[1] = payload   ARGV[2] = version   ARGV[3] = value TTL (s)
local v = tonumber(ARGV[2])
local dead = redis.call('GET', KEYS[2])
if dead and v <= tonumber(dead) then return 'DELETED' end
local cur = redis.call('HGET', KEYS[1], 'ver')
if cur and v < tonumber(cur) then return 'STALE' end
redis.call('HSET', KEYS[1], 'payload', ARGV[1], 'ver', ARGV[2])
redis.call('EXPIRE', KEYS[1], tonumber(ARGV[3]))
return 'UPDATED'
```

**`delete.lua`**

```lua
-- KEYS[1] = value hash   KEYS[2] = tombstone   ARGV[1] = version at delete   ARGV[2] = tombstone TTL (s)
local v = tonumber(ARGV[1])
redis.call('DEL', KEYS[1])
local dead = redis.call('GET', KEYS[2])
if (not dead) or v > tonumber(dead) then
  redis.call('SET', KEYS[2], ARGV[1], 'EX', tonumber(ARGV[2]))
end
return 'DELETED'
```

### 4.5 Spring integration

The service uses the standard cache annotations. All tombstone logic lives in one custom `Cache`, because `@Cacheable` and `@CachePut` only ever call `cache.get(key)` and `cache.put(key, value)`.

| Annotation | Spring calls | `TombstoneCache` does |
|---|---|---|
| `@Cacheable` on `read()` | `get(key)`; on a miss it runs `read()` and then `put(key, result)` | **get:** payload present → hit, otherwise miss.<br>**put:** `put.lua` with the version inside the value. A `null` result (row not found) is not cached. |
| `@CachePut` on `update()` | `put(key, result)` | `put.lua` with the new version. |
| `@CachePut` on `delete()` | `put(key, result)`, where the result is a `ProductDeleted(id, version)` marker | Sees a `Tombstone` → `delete.lua`. |
| `@CacheEvict` | `evict(key)`, with no version | Removes only `payload`; `ver` stays as a fence. |

A delete must **return** the version it deleted, so it uses `@CachePut` with a marker. `@CacheEvict` can't carry a version.

The cache is wrapped in Spring's `TransactionAwareCacheDecorator`. Inside a transaction, `put` and `evict` wait until after the commit, so a rolled-back update or delete never touches the cache. `read()` runs outside a transaction, so its fill happens immediately.

### 4.6 Code map

| Class / file | Package | Role |
|---|---|---|
| `Versioned` | `domain` | Any cached value that carries a version. `ProductView` implements it. |
| `Tombstone` | `domain` | Marker: "this value is a delete". |
| `ProductDeleted(id, version)` | `domain` | What a delete returns: the id and the version the row had. |
| `ProductOps.delete(id)` | `service` | Default answers `405`, so existing modes don't change. |
| `DELETE /{mode}/products/{id}` | `web.ProductController` | Calls `delete(id)`. A `null` from `read()` is answered with `404`. |
| `TombstoneCache` | `service.support` | The custom `Cache`: key layout, the two scripts, safe evict. |
| `tombstoneCacheManager` bean | `config.CacheConfig` | `TombstoneCache` wrapped in `TransactionAwareCacheDecorator`. |
| `TombstoneProductService` (`tombstone`) | `service.versioned` | Read / update / delete with the standard annotations. |
| `put.lua`, `delete.lua` | `src/main/resources/tombstone/` | The atomic rules from 4.2. |

## 5. Guarantees and limits

| Guaranteed | Not guaranteed |
|---|---|
| A write carrying a version from before a delete is rejected while the tombstone lives (1 hour). | A reader stalled for longer than the tombstone TTL could still write. Size the TTL far above your slowest reader. |
| A write older than the cached version is rejected. | Only writes that go through the script are protected. A plain `SET` or `DEL` bypasses it. |
| Refilling the current version after an expiry or evict works. | Every database write path must increase `@Version`. Bulk SQL or another application that skips it breaks the comparison. |
| A rolled-back update or delete never changes the cache. | If the after-commit cache write fails (Valkey unreachable), the failure is logged as a warning and the request still succeeds, because the database has already committed. The old value stays until its TTL. |
| The value and its tombstone share a hash slot, so the scripts stay atomic in a cluster. | Valkey replicates asynchronously: after a failover the newest version or tombstone can be missing for a moment. Memory eviction can drop them too. |
| | Restoring the database from a backup moves versions backwards: flush the cache after a restore. |

## 6. Demo-only notes

- The demo seeds fixed ids (1, 2, 3) on every **Reset**, which reuses ids. That is safe here only because Reset also empties Valkey, so no tombstone survives. Real code uses sequence ids (C3).
- The talk mentions this design verbally; it is not part of the stage run.

## 7. Test plan (live)

1. **Delete race:** Reset → slow reader `GET /tombstone/products/1?delayMs=10000` → within 10 s `DELETE /tombstone/products/1` → the reader's write is rejected (log `-> DELETED`) → `GET` returns `404`; Valkey has `tomb:{products:1}:deleted = 0` and no value key.
2. **Update race:** Reset → slow reader → within 10 s `PUT …?name=Keyboard&price=200` → the reader's write is `STALE`; the cache holds version 1, price 200.
3. **Refill after expiry:** warm the cache, remove the value key's payload (or let it expire), read again → refilled with the same version (`UPDATED`).
4. **Rollback:** `PUT …?name=Mouse` (fails at commit with `409`) → the cache is unchanged.
5. Re-run the six stage demos to confirm nothing else changed.
