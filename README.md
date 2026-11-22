# When Caches Lie

Live-demo code for the talk **"When Caches Lie: Solving Transactional Integrity & Distributed Race Conditions"** (WeAreDevelopers India 2026).

A small Spring Boot app that puts **Postgres** and **Valkey** side by side and shows, live, how `@Transactional` and `@Cacheable` / `@CachePut` let the cache disagree with the database, and how to fix it.

You will see two bugs and their fixes:

| | The bug | The fix |
|---|---|---|
| **Problem 1: ghost write** | `@CachePut` writes the cache *before* the commit. The commit then fails, Postgres rolls back, and the cache keeps a value that never existed. | Touch the cache only **after the commit** (`afterCommit()` or `transactionAware()`). Reordering the proxies is *not* enough. |
| **Problem 2: stale read** | A slow reader puts an old value into the cache *after* a newer write, and the old value wins. | Every cache write carries the row's `@Version`; a **Lua check-and-set** inside Valkey rejects the older one. |

---

## What's inside

| Piece | Version | Port |
|---|---|---|
| Spring Boot app (Spring Framework 6.2) | Boot 3.5.6, Java 21 | `8090` |
| Postgres | 16 | `5432` |
| Valkey (Redis-compatible) | 8 | `6379` |
| Adminer, a Postgres viewer | latest | `8080` |
| Redis Commander, a Valkey viewer | latest | `8081` |

---

## Prerequisites

- **Docker** with Docker Compose v2 (`docker compose version`)
- **Java 21** and **Maven 3.9+**
- **Bruno** ([usebruno.com](https://www.usebruno.com)) to send the requests, *or* `bash` and `curl` to use the `demo.sh` script instead

---

## Quick start

```bash
# 1. Get the code
git clone <this-repo-url>
cd cache-demo

# 2. Start Postgres, Valkey and the two viewers (waits until they are healthy)
docker compose up -d --wait

# 3. Start the app (keep this terminal open: the log is part of the demo)
mvn spring-boot:run -Dspring-boot.run.arguments=--demo.aop.order=tx-outer

# 4. Load the demo data: 3 products in Postgres, an empty cache
curl -X POST http://localhost:8090/admin/reset
```

Then open the two viewers:

| Viewer | URL | Login |
|---|---|---|
| **Adminer** (the truth in Postgres) | http://localhost:8080 | System **PostgreSQL**, Server `postgres`, User `demo`, Password `demo`, Database `demo`. Open table `product` → **Select data**. |
| **Redis Commander** (what the cache serves) | http://localhost:8081 | No login. Open the `valkey` connection and watch `products::1` and `lua:product:1`. |

> **Tip:** Redis Commander shows key names as a folder tree split at `:`, so `products::1` appears as `products` → (empty) → `1`. Expand the tree to see it.

---

## Running the demos with Bruno

The Bruno collection is in the [`bruno/`](bruno/) folder. Every request is built **once**; you switch between implementations by changing the **mode** (a Bruno environment).

### Open the collection in Bruno (once)

1. Install [Bruno](https://www.usebruno.com) and start it. Use a recent version: the collection is saved in Bruno's YAML format (`opencollection.yml`).
2. Click **Open Collection** (on the Bruno home screen, or in the menu next to the collection list).
3. Select the **`bruno`** folder inside your clone of this repo (the folder that contains `opencollection.yml`) and confirm.
4. **When Caches Lie** appears in the sidebar. Expand it and open the **Demo requests** folder: the seven requests are listed in the order you send them.

Bruno works directly on these files, so there is nothing to import or convert, and any change you save in Bruno is a change in the repo.

### Use it

1. **Pick a mode** in the environment dropdown (top right): `naive`, `safe`, `aware`, `nested`, `nestedaware` or `lua`. Each environment sets one variable, `mode`. The collection variable `baseUrl` is already set to `http://localhost:8090`.
2. **Send a request:** click it in the sidebar, then click the arrow next to the URL (or press `Ctrl+Enter` / `Cmd+Enter`). The response shows on the right.
3. **Start every demo with Reset** (request 1).
4. **Switch the mode, not the URL.** To run the same steps against another implementation, pick another environment and send the same requests again.
5. **For the two race demos** (5 and 6), open **Slow reader** and **Writer: price 200** in two tabs before you start. Send the slow reader, then send the writer within 10 seconds while the reader is still waiting.
6. **Check a variable:** hover over `{{mode}}` in the URL bar to see the value it resolves to. If no environment is selected, it isn't filled in.

### The requests

| # | Request | Method | URL |
|---|---|---|---|
| 1 | Reset | POST | `{{baseUrl}}/admin/reset` |
| 2 | Proxy chain | GET | `{{baseUrl}}/admin/chain?bean={{mode}}` |
| 3 | Warm the cache | GET | `{{baseUrl}}/{{mode}}/products/1` |
| 4 | Rename to Mouse (fails at commit) | PUT | `{{baseUrl}}/{{mode}}/products/1?name=Mouse&price=999` |
| 5 | Read again (what users get) | GET | `{{baseUrl}}/{{mode}}/products/1` |
| 6 | Slow reader (10 s) | GET | `{{baseUrl}}/{{mode}}/products/1?delayMs=10000` |
| 7 | Writer: price 200 | PUT | `{{baseUrl}}/{{mode}}/products/1?name=Keyboard&price=200` |

**How the mode works:** the first part of the URL is a Spring bean name. `ProductController` looks it up in a `Map<String, ProductOps>`, so `/naive/...` and `/aware/...` are the same request sent to two different services. An unknown mode returns `404` with the list of valid modes.

**Why "Rename to Mouse" fails:** product names are unique, and `Mouse` already exists (product 2). Hibernate only sends the `UPDATE` when it flushes at commit, so the method itself succeeds and the **commit** fails. The app answers `409 DB ROLLED BACK`. Whatever the cache did before that commit is the lie.

**"Read again" is a cache hit**, so its response is exactly what every user would get. Compare it with Adminer.

---

## The demos, in talk order

Start the app with `--demo.aop.order=tx-outer` for demos 1–2, then restart it with `--demo.aop.order=cache-outer` for demos 3–6. Send **Reset** (1) before every demo.

| Demo | Slide | Proxy order | Bruno mode | Send | What you see |
|---|---|---|---|---|---|
| **1. Break it: the ghost write** | after slide 6 | `tx-outer` | `naive` | 1, 3, 4, 5 | `409`, Postgres **Keyboard 100**, cache **Mouse 999** |
| **2. Fix: after the commit** | after slide 8 | `tx-outer` | `aware` | 1, 3, 4, 5 | `409`, both show **Keyboard 100** |
| **3. Proxy order alone looks fine** | after slide 9 | `cache-outer` | `naive` | 2, then 1, 3, 4, 5 | In sync: the cache proxy is outermost |
| **3b. …until a caller owns the transaction** | | `cache-outer` | `nested` | 1, 3, 4, 5 | Out of sync again: cache **Mouse 999** |
| **4. Fix: `transactionAware()` for that caller** | | `cache-outer` | `nestedaware` | 1, 3, 4, 5 | In sync |
| **5. Break it: the stale-read race** | after slide 10 | `cache-outer` | `aware` | 1, then **6** and within 10 s **7** (two tabs), then 5 | Postgres **200**, cache **100** |
| **6. Fix: Lua check-and-set** | after slide 11 | `cache-outer` | `lua` | same as demo 5 | Cache **200, version 1**; app log `reader CAS put 1 version 0 -> STALE` |

To restart with the other proxy order, stop the app (`Ctrl+C`) and run:

```bash
mvn spring-boot:run -Dspring-boot.run.arguments=--demo.aop.order=cache-outer
```

Request 2 (**Proxy chain**) prints the real advisor chain of a bean, outermost first, so you can check which order is active.

---

## Which class has which fix

Slide numbers refer to the talk deck (19-slide version).

| Slide | Topic | Mode | Class | What it shows |
|---|---|---|---|---|
| 4 | Problem 1: ghost write | `naive` | `service/naive/NaiveProductService` | The textbook setup: `@Transactional` + `@CachePut` / `@Cacheable`. With the transaction outermost, the cache is written before the commit. |
| 5–6 | Who wraps whom | | `config/AopConfig`, `diagnostics/ProxyChainReporter` | `AopConfig` sets the proxy order (`tx-outer`, `default`, `cache-outer`); `ProxyChainReporter` prints the real chain at startup and at `/admin/chain`. |
| 7 | Fix #1: update the cache after the commit | `safe` | `service/aftercommit/SafeProductService`, `service/support/Tx` | The cache work is registered with the transaction and runs in `afterCommit()`, which a rollback never reaches. |
| 8 | Fix #2: `transactionAware()` | `aware` | `service/aftercommit/AwareProductService`, `config/CacheConfig` | Same annotations as `naive`; the cache manager is built with `.transactionAware()`, so put and evict wait for the commit. |
| 8 | Fix #2, the event variant | `event` | `service/aftercommit/EventProductService`, `ProductCacheEvictor` | Publish an event in the transaction, evict in a `@TransactionalEventListener(AFTER_COMMIT)`. |
| | Fix #2b: your own post-commit wrapper | `wrapper` | `service/aftercommit/WrapperProductService`, `service/support/AfterCommitCache` | A `Cache` decorator that defers put and evict to `afterCommit()`. |
| 9 | Reordering the proxies is not the fix | `nested`, `nestedaware` | `service/nested/CheckoutService`, `CheckoutAwareService` | An outer `@Transactional` caller owns the commit, so the cache proxy order no longer helps; `transactionAware()` still does. |
| 10 | Problem 2: stale reads | `aware` | `service/aftercommit/AwareProductService.read()` | The `delayMs` pause between the DB read and the `@Cacheable` put lets a newer write land first. |
| 11–13 | Fix #3: version-guarded check-and-set | `lua` | `service/versioned/LuaProductService`, `src/main/resources/cas.lua` | Every cache write carries the row's `@Version`; the Lua script keeps the higher one and answers `STALE` to the older one. |

### Also in the code (not shown in the talk)

| Mode | Class | What it shows |
|---|---|---|
| `evict` | `service/evict/EvictFirstProductService` | `@CacheEvict(beforeInvocation = true)` plus a 10 s open transaction: a reader in that window re-caches the old row. |
| `evictlate` | `service/evict/EvictAfterCommitProductService` | The same slow write, evicting in `afterCommit()` instead. |

---

## Running the demos with `demo.sh` instead

`demo.sh` runs the same steps with `curl` and prints Postgres and Valkey side by side, with an automatic verdict for some steps.

```bash
./demo.sh reset            # 3 products in Postgres, empty Valkey
./demo.sh show             # what Postgres and Valkey hold for product 1
./demo.sh chain naive      # the proxy chain of a bean

# app started with --demo.aop.order=tx-outer
./demo.sh step1            # demo 1: the ghost write
./demo.sh step2 aware      # demo 2: the fix

# app started with --demo.aop.order=cache-outer
./demo.sh order            # demo 3: naive is fine, nested lies
./demo.sh orderfix         # demo 4: nestedaware stays in sync
./demo.sh step3 aware      # demo 5: the stale-read race
./demo.sh step4            # demo 6: the Lua fix

./demo.sh evictrace evict  # extra: the evict-first race (evictlate for the fix)
PAUSE=1 ./demo.sh step1    # wait for Enter between actions
```

---

## Troubleshooting

| Symptom | Fix |
|---|---|
| Demo 1 stays in sync (cache Keyboard 100) | The app runs with the cache as the outer proxy. Restart it with `--demo.aop.order=tx-outer` and check **Proxy chain**: `TransactionInterceptor <- OUTERMOST`. |
| Demo 3 `naive` ends out of sync | The app still runs `tx-outer`. Restart it with `--demo.aop.order=cache-outer`. |
| "Rename to Mouse" returns `200` instead of `409` | Send **Reset** first: the rename only fails while a product named `Mouse` exists. |
| Demo 5 or 6 shows price 200 in the cache | The writer was sent after the slow reader finished. Reset, then send the writer within 10 s of the reader. |
| `404` with `"path": "/%3Cmode%3E/products/1"` | The request URL contains the literal text `<mode>`. Use `{{mode}}` (double curly braces). |
| `{{mode}}` is not filled in, or a 404 lists the valid modes | No Bruno environment is selected. Pick one in the environment dropdown. |
| The app won't start: port `8090` in use | Another copy of the app is running. Stop it, or start this one with `--server.port=<other port>`. |
| Anything else | Send **Reset** and repeat the demo. |

---

## Stop and clean up

```bash
docker compose stop      # stop the containers, keep the data
./demo.sh down           # stop and DELETE the containers and their volumes
```

---

## Project layout

```
cache-demo/
├── docker-compose.yml          Postgres, Valkey, Adminer, Redis Commander
├── demo.sh                     scripted version of the demos (bash + curl)
├── bruno/                      Bruno collection: requests + one environment per mode
└── src/main/
    ├── java/dev/demo/cachelie/
    │   ├── config/             AopConfig (proxy order), CacheConfig (cache managers)
    │   ├── diagnostics/        ProxyChainReporter (/admin/chain)
    │   ├── domain/             Product entity (unique name, @Version), ProductView
    │   ├── web/                ProductController (/{mode}/products/{id}), AdminController (/admin/*)
    │   └── service/
    │       ├── naive/          the bug
    │       ├── aftercommit/    fixes that touch the cache after the commit
    │       ├── nested/         proxy order is not the fix
    │       ├── versioned/      Lua check-and-set
    │       ├── evict/          extra: the evict-first race
    │       └── support/        Tx, AfterCommitCache, ManualRead
    └── resources/
        ├── application.yml
        └── cas.lua             the version-guarded check-and-set
```
