# Code flow: from the request to Postgres and Valkey

How every request travels through the app, step by step: from the Bruno environment (the **mode**) through `ProductController`, the Spring proxies and the service, down to Postgres and the Valkey cache.

The proxy order is set with `--demo.aop.order`: `tx-outer` (the default, used for demos 1–2) or `cache-outer` (demos 3–6). See the [README](../README.md) for how to run each demo.

---

## Step 0: the path every request takes

| # | Where | What happens |
|---|---|---|
| 1 | **Bruno** | The environment you pick sets `mode` (e.g. `naive`); the URL becomes `/naive/products/1` |
| 2 | `ProductController` | `GET` → `read()`, `PUT` → `update()`, `DELETE` → `delete()` |
| 3 | `ProductController.pick(mode)` | Looks the mode up in `Map<String, ProductOps>`: the key is the bean name (`@Service("naive")`), the value is that service's **Spring proxy** |
| 4 | **Proxy** (CGLIB) | Runs the interceptors around the method. Which one runs first depends on the proxy order: `tx-outer` or `cache-outer` |
| 5 | **Service** | Reads or writes Postgres through `ProductRepository` |
| 6 | **Cache** | Through a cache manager, a support class, or a Lua script: see the tables below |
| 7 | `ProductController` | Returns JSON. A failed commit → `@ExceptionHandler` → **409 "DB ROLLED BACK"**; a product that doesn't exist → **404** |

---

## Which mode uses what

| Bruno mode | Service class | Cache written through | Valkey key | Support classes | In the talk |
|---|---|---|---|---|---|
| `naive` | `NaiveProductService` | `@Cacheable` / `@CachePut` → `cacheManager` (plain) | `products::1` | none | Demos 1, 3a |
| `safe` | `SafeProductService` | `Cache` API by hand (plain `cacheManager`) | `products::1` | `Tx` | Demo 2 (code shown) |
| `aware` | `AwareProductService` | `@Cacheable` / `@CachePut` → `awareCacheManager` (transaction-aware) | `products::1` | none | Demos 2, 5 |
| `event` | `EventProductService` | read by hand; evict from an event listener (`ProductCacheEvictor`) | `products::1` | `ManualRead` | Slide 8, mentioned |
| `wrapper` | `WrapperProductService` | read by hand through a wrapper | `products::1` | `ManualRead`, `AfterCommitCache`, `Tx` | No |
| `nested` | `CheckoutService` → `NaiveProductService` | via `naive` | `products::1` | none | Demo 3b |
| `nestedaware` | `CheckoutAwareService` → `AwareProductService` | via `aware` | `products::1` | none | Demo 4 |
| `lua` | `LuaProductService` | `@Cacheable` / `@CachePut` → `versionedCacheManager` (transaction-aware, writes through `cas.lua`) | `lua:product:1` | `VersionedCache` | Demo 6 |
| `tombstone` | `TombstoneProductService` | `@Cacheable` / `@CachePut` → `tombstoneCacheManager` | `tomb:{products:1}` and `tomb:{products:1}:deleted` | `TombstoneCache` | No |

---

## READ: `GET /{mode}/products/1`

| Mode | 1 | 2 | 3 (on a miss) | 4 | 5 |
|---|---|---|---|---|---|
| `naive`, `aware`, `lua`, `tombstone` | Proxy → **`CacheInterceptor`** (`@Cacheable`) | `cache.get(1)`: **hit → return, the method is not called** | Method runs: `repo.findById` (Postgres) | `pause(delayMs)` (the slow reader) | Back in `CacheInterceptor`: `cache.put(1, result)` → Valkey |
| `safe` | Proxy (no cache annotation on `read`) | Method: `cache().get(1)`: hit → return | `repo.findById` | `pause(delayMs)` | `cache().put(1, view)` |
| `event`, `wrapper` | Proxy → method | `ManualRead.read()`: `cache.get(1)`: hit → return | `repo.findById` | `pause(delayMs)` | `cache.put(1, view)` (`wrapper`: through `AfterCommitCache`; no transaction, so it runs at once) |
| `nested`, `nestedaware` | Proxy → `CheckoutService.read()` | Delegates to the `naive` / `aware` proxy | Same as `naive` / `aware` | | |

For `lua`, step 5 goes to `VersionedCache` → `cas.lua`: written only if the version is newer, otherwise `STALE`. For `tombstone`, step 5 goes to `TombstoneCache` → `put.lua`, which rejects an older version (`STALE`) or a deleted row (`DELETED`).

---

## UPDATE that fails at commit: `PUT /{mode}/products/1?name=Mouse&price=999`

`Mouse` already exists and names are unique. Hibernate only sends the `UPDATE` at commit, so the method succeeds and the **commit** fails.

| Mode, proxy order | 1 | 2 | 3 | 4 | 5 | Valkey ends with |
|---|---|---|---|---|---|---|
| **`naive`, `tx-outer`** (demo 1) | `TransactionInterceptor`: **begin** | `CacheInterceptor` → method: set name and price (no SQL yet) | `@CachePut` → **`SET products::1` = Mouse 999** | `TransactionInterceptor`: **commit** → flush → unique violation → **rollback** | Controller → 409 | **Mouse 999** ❌ ghost write |
| **`naive`, `cache-outer`** (demo 3a) | `CacheInterceptor` (outer) | `TransactionInterceptor`: begin → method → commit fails → rollback | The exception passes through `CacheInterceptor` → **`@CachePut` skipped** | Controller → 409 | | Keyboard 100 ✅ |
| **`safe`** | `TransactionInterceptor`: begin | Method: set fields; `Tx.submitCacheOperation(put)` registers the put of the new value | Commit fails → rollback → `afterCommit` **never runs** | Controller → 409 | | Keyboard 100 ✅ |
| **`aware`** (demo 2) | `TransactionInterceptor`: begin | `CacheInterceptor` → method | `@CachePut` → `TransactionAwareCacheDecorator` **holds the put until after the commit** | Commit fails → rollback → **the held put is discarded** | Controller → 409 | Keyboard 100 ✅ |
| **`event`** | Begin | Method: `publishEvent(ProductChanged)` | Commit fails → rollback | `ProductCacheEvictor` (`AFTER_COMMIT`) **is not called** | Controller → 409 | Keyboard 100 ✅ |
| **`wrapper`** | Begin | Method: `AfterCommitCache.evict` → `Tx.submitCacheOperation` registers it | Commit fails → rollback → the evict never runs | Controller → 409 | | Keyboard 100 ✅ |
| **`nested`, `cache-outer`** (demo 3b) | `CheckoutService` proxy: **begin (the real transaction)** | → `NaiveProductService` proxy: `CacheInterceptor` → its `@Transactional` **only joins** | `@CachePut` → **`SET products::1` = Mouse 999** | Back in `CheckoutService`: **commit** fails → rollback | Controller → 409 | **Mouse 999** ❌ |
| **`nestedaware`** (demo 4) | `CheckoutAwareService`: begin | → `AwareProductService`: joins | `@CachePut` held by the decorator until the checkout commits | The checkout commit fails → the held put is discarded | Controller → 409 | Keyboard 100 ✅ |

---

## UPDATE that succeeds, with a slow reader: `PUT …?name=Keyboard&price=200`

Send **Slow reader** (`GET …?delayMs=10000`) first, then the writer within 10 seconds.

| Mode | Order of events | Cache ends with |
|---|---|---|
| **`aware`** (demo 5) | Reader: miss → reads 100, version 0 → pauses. Writer: begin → update → **commit** → held put runs → **200, version 1**. Reader wakes up → `@Cacheable` put → **100, version 0 overwrites it** | 100 ❌ stale |
| **`lua`** (demo 6) | Reader: miss → reads version 0 → pauses. Writer: begin → update → `flush()` (version → 1) → `@CachePut` held by the decorator → commit → `VersionedCache.put` → `cas.lua(v1)` **UPDATED**. Reader wakes up → `@Cacheable` put → `cas.lua(v0)` **STALE** | 200, version 1 ✅ |
| **`tombstone`** | Same as `lua`, but through the annotations: `@CachePut` → the decorator holds it → after the commit → `TombstoneCache.put` → `put.lua(v1)` UPDATED. Reader → `put.lua(v0)` STALE | 200, version 1 ✅ |

---

## DELETE: `DELETE /{mode}/products/1` (only the `tombstone` mode)

| # | Step |
|---|---|
| 1 | `ProductController.delete()` → `pick("tombstone")`. Every other mode → `ProductOps.delete()` default → **405** |
| 2 | Proxy: `TransactionInterceptor` begin → `CacheInterceptor` → `TombstoneProductService.delete()` |
| 3 | `repo.delete()` + `flush()`: a hard delete that checks the version. Returns `ProductDeleted(1, v)` |
| 4 | `@CachePut` → `TransactionAwareCacheDecorator` **holds it until after the commit** |
| 5 | The commit succeeds → `TombstoneCache.put()` sees a `Tombstone` → `delete.lua`: removes `tomb:{products:1}` and sets `tomb:{products:1}:deleted = v` for 1 hour |
| 6 | A slow reader holding `v` arrives later → `put.lua` → `DELETED` (rejected). A new read → the row is gone → `null` → **404**, nothing cached |

More on this design: [specs/delete-tombstone.md](specs/delete-tombstone.md).

---

## Support classes: who calls them, and when

| Class | Called by | When |
|---|---|---|
| `Tx` | `SafeProductService`, `AfterCommitCache` | Inside a transaction: runs the action only after a successful commit. Without a transaction: runs it immediately |
| `ManualRead` | `EventProductService`, `WrapperProductService` | Every read in those modes: the hand-written cache-aside read with the slow-reader pause |
| `AfterCommitCache` | `WrapperProductService` | Every cache `put` and `evict` of the `wrapper` mode, deferred through `Tx` |
| `VersionedCache` | `versionedCacheManager`, used by the annotations in `LuaProductService` | Every cache read and write of the `lua` mode; writes go through `cas.lua` |
| `TombstoneCache` | `tombstoneCacheManager`, used by the annotations in `TombstoneProductService` | Every cache read and write of the `tombstone` mode; writes go through `put.lua` / `delete.lua` |

---

## The three ideas behind every row

1. **Whichever proxy is outermost decides *when* the cache is written.** `TransactionInterceptor` outer → the cache is written before the commit. `CacheInterceptor` outer → after it.
2. **Fixes that wait for the commit don't depend on the proxy order.** `Tx.submitCacheOperation`, an `AFTER_COMMIT` event, `transactionAware()` / `TransactionAwareCacheDecorator` and `AfterCommitCache` all wait for the **real** commit, whoever started the transaction.
3. **Only a version check stops the slow reader.** `cas.lua` and `put.lua` reject a late, older write. Commit order alone can't.
