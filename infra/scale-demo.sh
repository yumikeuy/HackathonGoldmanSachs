#!/usr/bin/env bash
# Proves that several instances behave like one service (docs/operations.md, "Several instances"):
#
#   docker compose -f docker-compose.yml -f docker-compose.scale.yml up --build -d --scale backend=2
#   infra/seed/seed.sh && infra/scale-demo.sh
#
# Sends EVENTS (200) identical events with a fresh instrument through the load balancer, in parallel, then checks:
#   1. the events were spread over at least two instances (nginx X-Upstream header);
#   2. they became exactly ONE alert with occurrencesCount = EVENTS (dedup works across instances);
#   3. every notification of that alert exists once (no duplicate sends from two outbox workers).
# Exits non-zero on failure. Needs bash and curl only.
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
EVENTS="${EVENTS:-200}"
PARALLEL="${PARALLEL:-10}"
TIMEOUT="${TIMEOUT:-60}"
ADMIN_EMAIL="${ADMIN_EMAIL:-admin@alerting.local}"
ADMIN_PASSWORD="${ADMIN_PASSWORD:-admin}"
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
AUTH=()

ok()   { printf '  ok   %s\n' "$*"; }
fail() { printf '  FAIL %s\n' "$*" >&2; exit 1; }

get() { curl -sS -o "$WORK/body" -w '%{http_code}' ${AUTH[@]+"${AUTH[@]}"} "$BASE_URL$1"; }

status="$(curl -sS -o "$WORK/body" -w '%{http_code}' -X POST "$BASE_URL/api/auth/login" -H 'Content-Type: application/json' \
          --data-binary "{\"email\":\"$ADMIN_EMAIL\",\"password\":\"$ADMIN_PASSWORD\"}" || true)"
[ "$status" = "200" ] && AUTH=(-H "Authorization: Bearer $(grep -o '"accessToken":"[^"]*"' "$WORK/body" | sed 's/.*:"//; s/"$//')")

[ "$(get /api/teams)" = "200" ] || fail "$BASE_URL is not up or not seeded"
RATES="$(grep -o '"id":[0-9]*,"name":"Team Rates"' "$WORK/body" | grep -o '[0-9][0-9]*' | head -n1 || true)"
[ -n "$RATES" ] || fail "Team Rates missing — run infra/seed/seed.sh first"

INSTRUMENT="SCALE-$(date +%s)-$RANDOM"
sed "s/EUR-IRS-10Y/$INSTRUMENT/" "$ROOT_DIR/backend/src/test/resources/events/rates-trade-rejected.json" > "$WORK/event.json"

printf 'Sending %s identical events (instrument %s) through %s, %s in parallel\n' "$EVENTS" "$INSTRUMENT" "$BASE_URL" "$PARALLEL"
seq 1 "$EVENTS" | xargs -P "$PARALLEL" -I{} curl -sS -o /dev/null -D - -X POST "$BASE_URL/api/ingest/payments-monitoring" \
    -H 'Content-Type: application/json' --data-binary @"$WORK/event.json" \
  | tr -d '\r' > "$WORK/headers"

accepted="$(grep -c '^HTTP/1.1 202' "$WORK/headers" || true)"
[ "$accepted" = "$EVENTS" ] || fail "only $accepted of $EVENTS events answered 202"
ok "$EVENTS events accepted"

printf '  events per instance:\n'
grep -i '^x-upstream:' "$WORK/headers" | awk '{print $2}' | sort | uniq -c | sed 's/^/      /'
instances="$(grep -i '^x-upstream:' "$WORK/headers" | awk '{print $2}' | sort -u | wc -l | tr -d ' ')"
[ "$instances" -ge 2 ] || fail "all events went to one instance — is the stack scaled (--scale backend=2) behind the lb?"
ok "served by $instances instances"

alerts=""
for _ in $(seq 1 "$TIMEOUT"); do
  [ "$(get "/api/alerts?limit=200&teamId=$RATES")" = "200" ] || fail "GET /api/alerts"
  alerts="$(grep -o '{[^{}]*}' "$WORK/body" | grep -F "$INSTRUMENT" || true)"
  count="$(printf '%s' "$alerts" | grep -o '"occurrencesCount":[0-9]*' | head -n1 | grep -o '[0-9][0-9]*' || echo 0)"
  [ "${count:-0}" -ge "$EVENTS" ] && break
  sleep 1
done
n="$(printf '%s\n' "$alerts" | grep -c '"id"' || true)"
[ "$n" = "1" ] || fail "expected exactly one alert for $INSTRUMENT, found $n"
[ "${count:-0}" = "$EVENTS" ] || fail "occurrencesCount is ${count:-0}, expected $EVENTS"
ALERT_ID="$(printf '%s' "$alerts" | grep -o '^{"id":[0-9]*' | grep -o '[0-9][0-9]*')"
ok "one alert #$ALERT_ID with occurrencesCount = $count (deduplicated across instances)"

for _ in $(seq 1 "$TIMEOUT"); do
  [ "$(get "/api/simulator/deliveries?limit=200")" = "200" ] || fail "GET /api/simulator/deliveries"
  grep -o "\"alertId\":$ALERT_ID,\"teamId\":[0-9]*,\"userId\":[^,]*,\"channel\":\"[A-Z]*\",\"status\":\"[A-Z]*\"" "$WORK/body" \
    > "$WORK/deliveries" || true
  if [ -s "$WORK/deliveries" ] && ! grep -q '"status":"PENDING"' "$WORK/deliveries"; then break; fi
  sleep 1
done
[ -s "$WORK/deliveries" ] || fail "no deliveries for alert #$ALERT_ID"
total="$(wc -l < "$WORK/deliveries" | tr -d ' ')"
unique="$(sed 's/"status":"[A-Z]*"//' "$WORK/deliveries" | sort -u | wc -l | tr -d ' ')"
[ "$total" = "$unique" ] || fail "duplicate notifications: $total rows for $unique recipient/channel pairs"
sent="$(grep -c '"status":"SENT"' "$WORK/deliveries" || true)"
ok "$total notification(s), each once ($sent sent)"

printf '\nSCALE DEMO PASSED\n'
