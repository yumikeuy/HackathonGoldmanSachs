#!/usr/bin/env bash
# End-to-end smoke test against a running, seeded stack:
#
#   docker compose up --build -d && infra/seed/seed.sh && infra/smoke.sh
#
# Sends every sample event (HTTP, and the workflow event through the external Kafka topic when `docker compose`
# is available), appends a line to infra/logs/app.log (file source), then checks through the API that alerts exist
# with the right team and severity, that an email reached MailHog and that a repeated event increases
# occurrencesCount. Exits with a non-zero code on the first failure. Needs bash and curl only.
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
MAILHOG_URL="${MAILHOG_URL:-http://localhost:8025}"
TIMEOUT="${TIMEOUT:-30}"
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
EVENTS="$ROOT_DIR/backend/src/test/resources/events"
LOG_FILE="$ROOT_DIR/infra/logs/app.log"
RUN_ID="smoke-$(date +%s)-$RANDOM"
TMP="$(mktemp)"
trap 'rm -f "$TMP"' EXIT

passed=0
ok()   { passed=$((passed + 1)); printf '  ok   %s\n' "$*"; }
fail() { printf '  FAIL %s\n' "$*" >&2; exit 1; }
step() { printf '\n%s\n' "$*"; }

get() { curl -sS -o "$TMP" -w '%{http_code}' "$BASE_URL$1"; }

ingest() {
  local source="$1" file="$2" status
  status="$(curl -sS -o "$TMP" -w '%{http_code}' -X POST "$BASE_URL/api/ingest/$source" \
            -H 'Content-Type: application/json' --data-binary @"$file")"
  [ "$status" = "202" ] || fail "POST /api/ingest/$source ($(basename "$file")): HTTP $status $(cat "$TMP")"
}

team_id() {
  [ "$(get /api/teams)" = "200" ] || fail "GET /api/teams"
  grep -o "\"id\":[0-9]*,\"name\":\"$1\"" "$TMP" | head -n1 | grep -o '[0-9][0-9]*' | head -n1 || true
}

# find_alert QUERY TITLE_FRAGMENT — prints the first AlertSummary object whose title contains the fragment
find_alert() {
  local query="$1" fragment="$2"
  [ "$(get "/api/alerts?limit=200&$query")" = "200" ] || return 1
  grep -o '{[^{}]*}' "$TMP" | grep -F "\"title\":\"" | grep -F "$fragment" | head -n1 || true
}

# await_alert QUERY TITLE_FRAGMENT — waits up to $TIMEOUT s, prints the alert
await_alert() {
  local alert=""
  for _ in $(seq 1 "$TIMEOUT"); do
    alert="$(find_alert "$1" "$2")"
    [ -n "$alert" ] && { printf '%s' "$alert"; return 0; }
    sleep 1
  done
  return 1
}

field() { printf '%s' "$1" | grep -o "\"$2\":\"\{0,1\}[^\",}]*" | head -n1 | sed 's/.*:"\{0,1\}//'; }

expect_alert() {
  local label="$1" query="$2" fragment="$3" severity="$4" team="$5" alert
  alert="$(await_alert "$query" "$fragment")" || fail "$label: no alert with title containing '$fragment' within ${TIMEOUT}s"
  [ "$(field "$alert" severity)" = "$severity" ] || fail "$label: severity $(field "$alert" severity), expected $severity"
  if [ -n "$team" ]; then
    [ "$(field "$alert" teamId)" = "$team" ] || fail "$label: team $(field "$alert" teamId), expected $team"
  fi
  ok "$label → alert #$(field "$alert" id), $severity, count $(field "$alert" occurrencesCount)"
}

step "Service"
curl -sf "$BASE_URL/actuator/health" > /dev/null || fail "$BASE_URL is not up (docker compose up --build -d)"
RATES="$(team_id "Team Rates")"; PRICING="$(team_id "Team Pricing")"; INFRA="$(team_id "Team Infra")"
[ -n "$RATES" ] && [ -n "$PRICING" ] && [ -n "$INFRA" ] || fail "teams missing — run infra/seed/seed.sh first"
ok "up and seeded (teams $RATES, $PRICING, $INFRA)"

step "Send events"
ingest payments-monitoring "$EVENTS/rates-trade-rejected.json"; ok "rates-trade-rejected.json over HTTP"
ingest legacy-gateway "$EVENTS/infra-cpu-high.json";            ok "infra-cpu-high.json over HTTP"
ingest legacy-gateway "$EVENTS/alien-schema.json";              ok "alien-schema.json over HTTP"
if (cd "$ROOT_DIR" && docker compose ps kafka --status running -q 2>/dev/null | grep -q .); then
  tr -d '\n' < "$EVENTS/pricing-workflow-failed.json" \
    | (cd "$ROOT_DIR" && docker compose exec -T kafka /opt/kafka/bin/kafka-console-producer.sh \
         --bootstrap-server localhost:9092 --topic workflow.events > /dev/null 2>&1) \
    || fail "could not produce to Kafka topic workflow.events"
  ok "pricing-workflow-failed.json over Kafka topic workflow.events"
else
  ingest payments-monitoring "$EVENTS/pricing-workflow-failed.json"
  ok "pricing-workflow-failed.json over HTTP (docker compose not available for Kafka)"
fi
# The log rule groups every ERROR line into one open alert; close the ones earlier smoke runs opened,
# so this run's line opens (and emails) a new alert.
if [ "$(get "/api/alerts?limit=200&teamId=$INFRA&status=OPEN&status=ACKED")" = "200" ]; then
  for id in $(grep -o '{[^{}]*}' "$TMP" | grep -F '[smoke-' | grep -o '^{"id":[0-9]*' | grep -o '[0-9][0-9]*'); do
    curl -sS -o /dev/null -X POST "$BASE_URL/api/alerts/$id/resolve" -H 'Content-Type: application/json' \
         -d '{"actor":"smoke-test"}'
  done
fi
mkdir -p "$(dirname "$LOG_FILE")"
printf '%s ERROR payment-service: connection pool exhausted [%s]\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$RUN_ID" >> "$LOG_FILE"
ok "appended an ERROR line to infra/logs/app.log"

step "Alerts"
expect_alert "Team Rates: rejected trade"  "teamId=$RATES"   "Rejected T-1001"          HIGH     "$RATES"
expect_alert "Team Pricing: failed workflow" "teamId=$PRICING" "Workflow eod-pricing"   CRITICAL "$PRICING"
expect_alert "Team Infra: high CPU"        "teamId=$INFRA"   "on prod-db-03"            HIGH     "$INFRA"
expect_alert "Team Infra: log line (file source)" "teamId=$INFRA" "$RUN_ID"             MEDIUM   "$INFRA"
expect_alert "Unknown schema → UNROUTED"   "unrouted=true"   ""                         UNKNOWN  ""

step "Deduplication"
before="$(field "$(find_alert "teamId=$RATES" "Rejected T-1001")" occurrencesCount)"
ingest payments-monitoring "$EVENTS/rates-trade-rejected.json"
after="$before"
for _ in $(seq 1 "$TIMEOUT"); do
  after="$(field "$(find_alert "teamId=$RATES" "Rejected T-1001")" occurrencesCount)"
  [ "$after" -gt "$before" ] 2>/dev/null && break
  sleep 1
done
[ "$after" -gt "$before" ] 2>/dev/null || fail "repeat did not increase occurrencesCount ($before → $after)"
ok "repeat increased occurrencesCount $before → $after (same alert)"

step "Delivery"
found=""
for _ in $(seq 1 "$TIMEOUT"); do
  if curl -sf "$MAILHOG_URL/api/v2/search?kind=containing&query=$RUN_ID" -o "$TMP" && grep -q '"total":[1-9]' "$TMP"; then
    found=yes; break
  fi
  sleep 1
done
[ -n "$found" ] || fail "no email mentioning $RUN_ID in MailHog ($MAILHOG_URL) within ${TIMEOUT}s"
ok "email for the log-line alert is in MailHog"

printf '\nSMOKE PASSED (%d checks)\n' "$passed"
