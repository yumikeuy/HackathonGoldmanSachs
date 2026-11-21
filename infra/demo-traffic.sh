#!/usr/bin/env bash
# Live demo traffic: a story of events through all three source types, at a human pace, so the audience watches
# alerts appear in the UI and land in the right team by the rules. Run against a seeded stack:
#
#   docker compose up --build -d && infra/seed/seed.sh
#   infra/demo-traffic.sh            # one pass (~2 min), then exits
#   infra/demo-traffic.sh --loop     # keeps going until Ctrl+C (values vary, alerts repeat / resolve / reopen)
#
# Every line printed says what was sent, through which source, and where it should end up.
# PAUSE (seconds between events, default 2) and BASE_URL can be overridden. Needs bash, curl and docker compose
# (for the Kafka topic; without it the workflow events go over HTTP).
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
PAUSE="${PAUSE:-2}"
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LOG_FILE="$ROOT_DIR/infra/logs/app.log"
LOOP=false
[ "${1:-}" = "--loop" ] && LOOP=true

BOLD=$'\033[1m'; DIM=$'\033[2m'; RED=$'\033[31m'; GREEN=$'\033[32m'; YELLOW=$'\033[33m'; BLUE=$'\033[34m'; RESET=$'\033[0m'
[ -t 1 ] || { BOLD=; DIM=; RED=; GREEN=; YELLOW=; BLUE=; RESET=; }

KAFKA=false
if (cd "$ROOT_DIR" && docker compose ps kafka --status running -q 2>/dev/null | grep -q .); then KAFKA=true; fi

act()  { printf '\n%s%s%s\n' "$BOLD" "$*" "$RESET"; }
note() { printf '   %s%s%s\n' "$DIM" "$*" "$RESET"; }
pause() { sleep "$PAUSE"; }
now()  { date -u +%Y-%m-%dT%H:%M:%SZ; }
pick() { local items=("$@"); printf '%s' "${items[RANDOM % ${#items[@]}]}"; }

# send_http SOURCE JSON EXPECTATION
send_http() {
  local status
  status="$(curl -sS -o /dev/null -w '%{http_code}' -X POST "$BASE_URL/api/ingest/$1" -H 'Content-Type: application/json' -d "$2")"
  printf '   %s[HTTP %-19s]%s %-58s %s→ %s%s %s\n' "$BLUE" "$1" "$RESET" "$(summary "$2")" "$GREEN" "$3" "$RESET" \
    "$([ "$status" = 202 ] || printf '%s(HTTP %s)%s' "$RED" "$status" "$RESET")"
}

# send_kafka JSON EXPECTATION — topic workflow.events (source "workflow-events")
send_kafka() {
  if $KAFKA; then
    printf '%s\n' "$1" | (cd "$ROOT_DIR" && docker compose exec -T kafka /opt/kafka/bin/kafka-console-producer.sh \
      --bootstrap-server localhost:9092 --topic workflow.events > /dev/null 2>&1)
    printf '   %s[Kafka workflow.events  ]%s %-58s %s→ %s%s\n' "$YELLOW" "$RESET" "$(summary "$1")" "$GREEN" "$2" "$RESET"
  else
    send_http payments-monitoring "$1" "$2"
  fi
}

# send_log LINE EXPECTATION — file source "app-log" tails infra/logs/app.log
send_log() {
  mkdir -p "$(dirname "$LOG_FILE")"
  printf '%s\n' "$1" >> "$LOG_FILE"
  printf '   %s[file app.log           ]%s %-58s %s→ %s%s\n' "$YELLOW" "$RESET" "${1:0:58}" "$GREEN" "$2" "$RESET"
}

summary() { printf '%s' "$1" | tr -d '\n' | sed 's/[{}"]//g; s/,/, /g' | cut -c1-58; }

curl -sf "$BASE_URL/actuator/health" > /dev/null || { echo "Service at $BASE_URL is not up — docker compose up --build -d"; exit 1; }
printf '%sLive demo traffic%s → %s  (pause %ss, Kafka: %s)\n' "$BOLD" "$RESET" "$BASE_URL" "$PAUSE" "$($KAFKA && echo yes || echo 'no, HTTP instead')"
note "Open the UI on Alerts — alerts appear live, no reload. Teams: Rates (trades), Pricing (workflows), Infra (CPU, logs)."

INSTRUMENTS=(EUR-IRS-10Y USD-IRS-5Y GBP-OIS-2Y JPY-IRS-30Y)
COUNTERPARTIES=("ACME Bank" "Globex Capital" "Initech Securities" "Umbrella Markets")
REASONS=("Credit limit exceeded" "Price outside tolerance" "Counterparty not onboarded" "Missing settlement instructions")
WORKFLOWS=(eod-pricing intraday-pricing curve-bootstrap-pricing)
STEPS=(load-curves fetch-fixings publish-prices)
HOSTS=(prod-db-03 prod-app-11 prod-kafka-02)
SERVICES=(payment-service settlement-engine risk-gateway)

run_once() {
  local i instrument amount host value workflow step

  act "1. Rejected trades → Team Rates  (rule “Rejected trades”: HIGH, Email + SMS + UI, one alert per instrument)"
  for i in 1 2 3; do
    instrument="$(pick "${INSTRUMENTS[@]}")"; amount=$(( (RANDOM % 40 + 5) * 1000000 ))
    send_http payments-monitoring "{\"type\":\"TRADE_REJECTED\",\"desk\":\"rates\",\"instrument\":\"$instrument\",\"trade\":{\"id\":\"T-$RANDOM\",\"notional\":\"$amount\",\"currency\":\"EUR\",\"counterparty\":\"$(pick "${COUNTERPARTIES[@]}")\"},\"reason\":\"$(pick "${REASONS[@]}")\",\"timestamp\":\"$(now)\"}" \
      "Team Rates · HIGH · $instrument"
    pause
  done

  act "2. Failed workflow through Kafka → Team Pricing  (rule “Failed workflows”: CRITICAL, Email + Call + UI)"
  workflow="$(pick "${WORKFLOWS[@]}")"; step="$(pick "${STEPS[@]}")"
  send_kafka "{\"workflow\":\"$workflow\",\"team\":\"pricing\",\"status\":\"FAILED\",\"step\":\"$step\",\"error\":\"Timeout while fetching USD curve from market-data\",\"attempt\":1,\"startedAt\":\"$(now)\"}" \
    "Team Pricing · CRITICAL · $workflow"
  pause
  send_kafka "{\"workflow\":\"$workflow\",\"team\":\"pricing\",\"status\":\"FAILED\",\"step\":\"$step\",\"error\":\"Timeout while fetching USD curve from market-data\",\"attempt\":2,\"startedAt\":\"$(now)\"}" \
    "same alert, count +1 (deduplicated)"
  pause

  act "3. CPU metrics → Team Infra  (rule “High CPU”: value > 90 → HIGH, one alert per host and 5-min window)"
  host="$(pick "${HOSTS[@]}")"; value="9$(( RANDOM % 9 + 1 )).$(( RANDOM % 10 ))"
  send_http legacy-gateway "{\"metric\":\"cpu\",\"value\":$value,\"host\":\"$host\",\"region\":\"eu-west-1\",\"labels\":{\"service\":\"settlement\",\"env\":\"prod\"}}" \
    "Team Infra · HIGH · $host"
  pause
  send_http legacy-gateway "{\"metric\":\"cpu\",\"value\":42.0,\"host\":\"$(pick "${HOSTS[@]}")\",\"region\":\"eu-west-1\"}" \
    "Team Infra, but no rule fires (42 ≤ 90) — no alert"
  pause

  act "4. Application log line (file source) → Team Infra  (rule “Application errors in logs”: MEDIUM)"
  send_log "$(now) ERROR $(pick "${SERVICES[@]}"): connection pool exhausted after 30s" "Team Infra · MEDIUM"
  pause
  send_log "$(now) INFO payment-service: health check ok" "no team (no ERROR) — UNROUTED"
  pause

  act "5. Unknown event shape → no team matches → UNROUTED (Unrouted page: suggest a team, create a rule from it)"
  send_http legacy-gateway "{\"Header\":{\"MsgType\":\"DealException\",\"Origin\":\"legacy-fx-gateway\"},\"Body\":{\"Deal\":{\"Ref\":\"FX-$RANDOM\",\"Economics\":{\"Notional\":25000000,\"Ccy\":\"USD\"}},\"Severity\":\"Major\",\"Text\":\"Settlement instructions missing\"}}" \
    "UNROUTED · UNKNOWN"
  pause

  act "6. Burst: the same rejected trade 30× in a second → ONE alert, occurrences +30"
  instrument="${INSTRUMENTS[0]}"
  for i in $(seq 1 30); do
    curl -sS -o /dev/null -X POST "$BASE_URL/api/ingest/payments-monitoring" -H 'Content-Type: application/json' \
      -d "{\"type\":\"TRADE_REJECTED\",\"desk\":\"rates\",\"instrument\":\"$instrument\",\"trade\":{\"id\":\"T-BURST\",\"notional\":\"15000000\",\"currency\":\"EUR\"},\"reason\":\"Credit limit exceeded\"}" &
  done
  wait
  printf '   %s[HTTP payments-monitoring]%s 30 × TRADE_REJECTED %-37s %s→ Team Rates · one alert, count +30%s\n' "$BLUE" "$RESET" "$instrument" "$GREEN" "$RESET"
  pause

  act "7. The workflow recovers → Pricing alert resolves itself  (resolve_condition: status == OK)"
  send_kafka "{\"workflow\":\"$workflow\",\"team\":\"pricing\",\"status\":\"OK\",\"step\":\"$step\",\"startedAt\":\"$(now)\"}" \
    "Team Pricing · $workflow → RESOLVED"
  pause
}

if $LOOP; then
  round=1
  while true; do
    printf '\n%s── round %s ──%s\n' "$DIM" "$round" "$RESET"
    run_once
    round=$((round + 1))
  done
else
  run_once
  printf '\n%sDone.%s Alerts: %s/#/alerts · Unrouted: %s/#/unrouted · Deliveries: %s/#/simulator · Emails: http://localhost:8025\n' \
    "$BOLD" "$RESET" "$BASE_URL" "$BASE_URL" "$BASE_URL"
fi
