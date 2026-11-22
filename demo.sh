#!/usr/bin/env bash
# Step-by-step driver for the "When Caches Lie" demo. Usage: ./demo.sh <command>
#   up | down | reset | show | chain | break | step1 | step2 | step3 | step4 | all   (PAUSE=1 waits for Enter between actions)
#   PHASE 1 (break only, no fixes):  chain, step1, break
#   PHASE 2 (the fixes):             step2 [safe|event|aware|wrapper], step3 [same], step4, fix2 (runs event, aware, wrapper)
#   ORDER IS NOT THE FIX:            order (cache-outer still breaks: nested), orderfix (nestedaware)
#   EVICT-FIRST RACE:                evictrace [evict|evictlate]   (the writer holds its transaction open 10 s)
set -u
APP=${APP:-http://localhost:8090}
say()   { printf '\n\033[1;36m== %s\033[0m\n' "$*"; }
bad()   { printf '\033[1;31m%s\033[0m\n' "$*"; }
good()  { printf '\033[1;32m%s\033[0m\n' "$*"; }
pause() { [[ "${PAUSE:-0}" == 1 ]] && read -rp "   (press Enter) " _ || true; }

dc()  { docker compose "$@"; }
vk()  { dc exec -T valkey valkey-cli --raw "$@"; }

show() {
  say "POSTGRES  (Adminer: http://localhost:8080)"
  dc exec -T postgres psql -U demo -d demo -c "SELECT id, name, price, version FROM product WHERE id = 1;"
  say "VALKEY    (viewer:  http://localhost:8081)"
  echo "products::1    = $(vk GET products::1)"
  echo "lua:product:1  = $(vk HGET lua:product:1 payload)  ver=$(vk HGET lua:product:1 ver)"
}

chain() {  # which advisor wraps which, read from the running app
  say "ORDERING of advisors and proxies   (GET $APP/admin/chain?bean=${1:-naive})"
  curl -s "$APP/admin/chain?bean=${1:-naive}"
}

get() { curl -s -w '  [HTTP %{http_code}]\n' "$APP/$1/products/$2?delayMs=${3:-0}"; }
put() { curl -s -w '  [HTTP %{http_code}]\n' -X PUT "$APP/$1/products/$2?name=$3&price=$4"; }
reset() { say "reset: Postgres = Keyboard 100 / Mouse 50 / Monitor 300, Valkey = empty"; curl -s -X POST "$APP/admin/reset"; echo; }

up() {
  dc up -d --wait
  cat <<MSG

Stack is up.
  Adminer (Postgres)   http://localhost:8080   System=PostgreSQL  Server=postgres  User=demo  Password=demo  Database=demo
  Valkey viewer        http://localhost:8081
Now start the app in another terminal:   mvn spring-boot:run
MSG
}

step1() {  # the bug: ghost cache write on a failed commit
  say "STEP 1  naive @Transactional + @CachePut   (break it first, no fix applied)"
  chain naive; pause
  reset; pause
  echo "warm the cache:"; get naive 1; show; pause
  echo "Rename Keyboard -> 'Mouse' and set price 999. 'Mouse' already exists, so the commit must fail:"
  put naive 1 Mouse 999
  show
  bad ">> The DB rolled back (still Keyboard 100) but Valkey now says Mouse 999. The cache lies."
}

breakall() {  # PHASE 1 end to end: both failures on the naive service, nothing fixed
  step1; pause
  say "BREAK 2  the delayed-put race on the SAME naive service"
  reset; pause
  race naive
  echo "read again:"; get naive 1
  bad ">> Postgres has price 200, Valkey serves 100. The reader's late put overwrote the writer's newer value."
}

step2() {  # fix for the ghost: only touch the cache after the commit. Optional mode: safe (default) | event | aware | wrapper
  local m=${1:-safe}
  say "STEP 2  fix #${FIXNAME:-1} ($m): the cache is touched only AFTER the commit"
  chain "$m"; pause
  reset; pause
  echo "warm the cache:"; get "$m" 1; pause
  echo "Same failing update:"; put "$m" 1 Mouse 999
  show
  good ">> Rolled back, and the cache was never touched. DB and Valkey agree."
}

race() {  # $1 = mode
  say "Reader is slow: it reads the DB, then takes 4s before writing the cache. A writer commits in between."
  get "$1" 1 4000 > /tmp/reader.$$ &            # reader: miss -> reads price 100 -> sleeps
  sleep 1
  echo "writer: set price to 200"; put "$1" 1 Keyboard 200
  wait; echo "reader returned: $(cat /tmp/reader.$$)"; rm -f /tmp/reader.$$
  show
}

step3() {  # the remaining bug: delayed put. Optional mode: safe (default) | event | aware | wrapper
  local m=${1:-safe}
  say "STEP 3  the delayed-put race on '$m' (a post-commit evict is not enough)"
  reset; pause
  race "$m"
  echo "read again:"; get "$m" 1
  bad ">> Postgres has price 200, Valkey keeps serving 100 until the TTL. Evicting after the commit cannot fix this."
}

step4() {  # fix: version-guarded Lua check-and-set
  say "STEP 4  version-guarded check-and-set in Lua (atomic inside Valkey)"
  reset; pause
  race lua
  echo "read again:"; get lua 1
  good ">> The reader's late write carried version 0 and was rejected (STALE). Valkey holds version 1, price 200."
  echo "(see the app log: 'reader CAS put ... -> STALE')"
}

ghost() {  # $1 = mode: warm the cache, run the failing update, then compare Postgres and Valkey
  local m=$1 db v code
  say "mode '$m'"
  curl -sf -X POST "$APP/admin/reset" > /dev/null || { bad "   !! app not reachable at $APP: cannot reset"; return 1; }
  get "$m" 1 > /dev/null
  echo "Rename Keyboard -> 'Mouse' (already exists), price 999:"
  code=$(curl -s -o /dev/null -w '%{http_code}' -X PUT "$APP/$m/products/1?name=Mouse&price=999")
  echo "   HTTP $code (409 = the commit failed and the DB rolled back)"
  db=$(dc exec -T postgres psql -U demo -d demo -tAc "SELECT name || '|' || price FROM product WHERE id = 1;" | tr -d '\r')
  v=$(vk GET products::1 | tr -d '\r')
  echo "   Postgres: ${db/|/ }"
  echo "   Valkey:   ${v:-<empty>}"
  if [[ "$code" != 409 ]]; then bad "   !! expected HTTP 409, got $code: no verdict"; return 1
  elif [[ -z "$v" ]]; then bad "   !! cache is empty, the warm-up read did not populate it: no verdict"; return 1
  elif [[ "$v" == *"\"name\":\"${db%%|*}\""* && "$v" == *"\"price\":${db##*|},"* ]]; then good "   >> in sync"
  else bad "   >> OUT OF SYNC: the cache holds a value the DB rolled back"; fi
}

order_check() {
  curl -s "$APP/admin/chain?bean=naive" | grep -q 'demo.aop.order = cache-outer' \
    || bad "!! The app is not running with demo.aop.order=cache-outer. Restart it with: mvn spring-boot:run -Dspring-boot.run.arguments=--demo.aop.order=cache-outer"
}

order() {  # is reordering the proxies enough? Only when the cache-annotated method owns the transaction.
  say "ORDER  the cache is the OUTER proxy (demo.aop.order=cache-outer). Same failing update, two callers."
  order_check; chain naive; pause
  ghost naive; pause                       # the method owns the transaction: the reorder works
  ghost nested                             # CheckoutService's outer @Transactional owns the commit
  bad ">> Reordering only helps when the cache-annotated method is the one that commits. Move the boundary and the cache lies again."
}

orderfix() {  # same callers, transactionAware() cache manager: the write waits for the REAL commit
  say "ORDERFIX  the same checkout caller over the transactionAware() cache manager"
  order_check
  ghost nestedaware
  good ">> transactionAware() binds the cache write to the outermost commit, wherever it happens and in any proxy order."
}
evictrace() {  # $1 = evict (default) | evictlate. The writer keeps its transaction open 10 s; a reader runs inside it.
  local m=${1:-evict} db v
  say "EVICT RACE '$m': the writer evicts, then holds its transaction open 10 s; a reader runs in between"
  reset; pause
  echo "warm the cache:"; get "$m" 1
  echo "writer: set price to 200 (returns after ~10 s)"
  put "$m" 1 Keyboard 200 > /tmp/writer.$$ &
  sleep 2
  echo "reader, while the writer's transaction is open:"; get "$m" 1
  wait; echo "writer returned: $(cat /tmp/writer.$$)"; rm -f /tmp/writer.$$
  show
  echo "read again:"; get "$m" 1
  db=$(dc exec -T postgres psql -U demo -d demo -tAc "SELECT price FROM product WHERE id = 1;" | tr -d '\r')
  v=$(vk GET products::1 | tr -d '\r')
  if [[ "$v" == *"\"price\":$db,"* ]]; then good ">> in sync: the cache serves price $db"
  else bad ">> OUT OF SYNC: Postgres has price $db, the cache serves ${v:-<empty>} until the TTL"; fi
}

case "${1:-}" in
  up) up ;;
  down) dc down -v ;;
  reset) reset ;;
  show) show ;;
  chain) chain "${2:-naive}" ;;
  break) PAUSE=${PAUSE:-1}; breakall ;;
  step1) step1 ;;
  step2) step2 "${2:-safe}" ;;
  step3) step3 "${2:-safe}" ;;
  fix2) for m in event aware wrapper; do FIXNAME=2 step2 $m; pause; done ;;
  step4) step4 ;;
  order) order ;;
  orderfix) orderfix ;;
  evictrace) evictrace "${2:-evict}" ;;
  all) PAUSE=${PAUSE:-1}; step1; pause; step2; pause; step3; pause; step4 ;;
  *) sed -n '2,7p' "$0" ;;
esac
