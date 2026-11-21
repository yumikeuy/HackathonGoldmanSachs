#!/usr/bin/env bash
# Seeds a running service with demo configuration over the REST API: sources, teams, users, rules, runbooks.
#
#   docker compose up --build -d && infra/seed/seed.sh
#
# Idempotent: anything that already exists (same source/team name, user email, rule name in the team,
# runbook file name) is left alone, so a second run changes nothing. Needs bash and curl only.
# BASE_URL (default http://localhost:8080) points at the service.
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
SEED_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SEED_DIR/../.." && pwd)"
RUNBOOK_DIR="$ROOT_DIR/backend/src/test/resources/runbooks"
TMP="$(mktemp)"
trap 'rm -f "$TMP"' EXIT

created=0
skipped=0

say()  { printf '%s\n' "$*"; }
fail() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }

# request METHOD PATH [curl args...] — body goes to $TMP, prints the HTTP status
request() {
  local method="$1" path="$2"; shift 2
  curl -sS -o "$TMP" -w '%{http_code}' -X "$method" "$BASE_URL$path" "$@"
}

# post_json PATH FILE [TEAM_ID] — POST a JSON body, replacing __TEAM_ID__; prints the HTTP status
post_json() {
  local path="$1" file="$2" team_id="${3:-}"
  sed "s/__TEAM_ID__/${team_id}/g" "$file" \
    | request POST "$path" -H 'Content-Type: application/json' --data-binary @-
}

# id_of_name PATH NAME — id of the list element {"id":N,"name":"NAME",...} (our DTOs start with id, name)
id_of_name() {
  local path="$1" name="$2"
  [ "$(request GET "$path")" = "200" ] || fail "GET $path failed: $(cat "$TMP")"
  grep -o "\"id\":[0-9]*,\"name\":\"$name\"" "$TMP" | head -n1 | grep -o '[0-9][0-9]*' | head -n1 || true
}

name_in() { grep -o '"name"[[:space:]]*:[[:space:]]*"[^"]*"' "$1" | head -n1 | sed 's/.*:[[:space:]]*"\(.*\)"/\1/'; }

wait_for_service() {
  say "Waiting for $BASE_URL ..."
  for _ in $(seq 1 60); do
    if curl -sf "$BASE_URL/actuator/health" > /dev/null 2>&1; then return 0; fi
    sleep 2
  done
  fail "service at $BASE_URL is not up (docker compose up --build -d)"
}

seed_source() {
  local file="$1" name status
  name="$(name_in "$file")"
  status="$(post_json /api/sources "$file")"
  case "$status" in
    201) say "  + source $name"; created=$((created + 1)) ;;
    409) say "  = source $name (exists)"; skipped=$((skipped + 1)) ;;
    *)   fail "source $name: HTTP $status $(cat "$TMP")" ;;
  esac
}

# seed_team FILE — prints nothing, sets TEAM_ID
seed_team() {
  local file="$1" name status
  name="$(name_in "$file")"
  status="$(post_json /api/teams "$file")"
  case "$status" in
    201) say "  + team $name"; created=$((created + 1)) ;;
    409) say "  = team $name (exists)"; skipped=$((skipped + 1)) ;;
    *)   fail "team $name: HTTP $status $(cat "$TMP")" ;;
  esac
  TEAM_ID="$(id_of_name /api/teams "$name")"
  [ -n "$TEAM_ID" ] || fail "could not find the id of team $name"
}

seed_user() {
  local file="$1" team_id="$2" name status
  name="$(name_in "$file")"
  status="$(post_json /api/users "$file" "$team_id")"
  case "$status" in
    201) say "    + user $name"; created=$((created + 1)) ;;
    409) say "    = user $name (exists)"; skipped=$((skipped + 1)) ;;
    *)   fail "user $name: HTTP $status $(cat "$TMP")" ;;
  esac
}

seed_rule() {
  local file="$1" team_id="$2" name status
  name="$(name_in "$file")"
  [ -n "$(id_of_name "/api/rules?teamId=$team_id" "$name")" ] && {
    say "    = rule $name (exists)"; skipped=$((skipped + 1)); return; }
  status="$(post_json /api/rules "$file" "$team_id")"
  case "$status" in
    201) say "    + rule $name"; created=$((created + 1)) ;;
    *)   fail "rule $name: HTTP $status $(cat "$TMP")" ;;
  esac
}

# seed_runbook FILE TEAM_ID(or empty for global) CONDITION_JSON
seed_runbook() {
  local file="$1" team_id="$2" condition="$3" name status args
  name="$(basename "$file")"
  status="$(request GET /api/runbooks)"
  if [ "$status" != "200" ]; then
    say "    ! runbooks API not available (HTTP $status) — skipping $name"; return
  fi
  if grep -q "\"fileName\":\"$name\"" "$TMP"; then
    say "    = runbook $name (exists)"; skipped=$((skipped + 1)); return
  fi
  args=(-F "file=@$file" -F "condition=$condition")
  [ -n "$team_id" ] && args+=(-F "teamId=$team_id")
  status="$(request POST /api/runbooks "${args[@]}")"
  case "$status" in
    200|201) say "    + runbook $name"; created=$((created + 1)) ;;
    *)       say "    ! runbook $name: HTTP $status $(cat "$TMP") — continuing" ;;
  esac
}

wait_for_service

say "Sources"
for file in "$SEED_DIR"/sources/*.json; do seed_source "$file"; done

say "Team Rates"
seed_team "$SEED_DIR/teams/team-rates.json"; RATES="$TEAM_ID"
seed_user "$SEED_DIR/users/anna.json" "$RATES"
seed_user "$SEED_DIR/users/piotr.json" "$RATES"
seed_rule "$SEED_DIR/rules/rejected-trades.json" "$RATES"
seed_runbook "$RUNBOOK_DIR/trade-rejection.md" "$RATES" '{"path":"type","op":"==","value":"TRADE_REJECTED"}'

say "Team Pricing"
seed_team "$SEED_DIR/teams/team-pricing.json"; PRICING="$TEAM_ID"
seed_user "$SEED_DIR/users/maria.json" "$PRICING"
seed_rule "$SEED_DIR/rules/failed-workflows.json" "$PRICING"
seed_runbook "$RUNBOOK_DIR/pricing-workflow-restart.txt" "$PRICING" '{"path":"status","op":"==","value":"FAILED"}'

say "Team Infra"
seed_team "$SEED_DIR/teams/team-infra.json"; INFRA="$TEAM_ID"
seed_user "$SEED_DIR/users/john.json" "$INFRA"
seed_rule "$SEED_DIR/rules/high-cpu.json" "$INFRA"
seed_rule "$SEED_DIR/rules/application-errors.json" "$INFRA"
seed_runbook "$RUNBOOK_DIR/high-cpu.yaml" "" '{"path":"metric","op":"in","value":["cpu","memory"]}'

mkdir -p "$ROOT_DIR/infra/logs" && touch "$ROOT_DIR/infra/logs/app.log"

say ""
say "Done: $created created, $skipped already existed."
say "UI $BASE_URL  ·  Swagger $BASE_URL/swagger-ui.html  ·  MailHog http://localhost:8025"
