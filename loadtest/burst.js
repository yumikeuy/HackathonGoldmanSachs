// AGENTS.md §2.5: 500 identical events within one minute must become ONE alert with occurrencesCount = 500.
//
//   docker run --rm --network host -v "$PWD/loadtest":/scripts grafana/k6 run /scripts/burst.js
//
// Env: BASE_URL (default http://localhost:8080), EVENTS (500), WINDOW_SEC (60), VUS (10).
// The run fails (non-zero exit) if any event is rejected or the alert count is not exactly EVENTS.
import { check, sleep } from 'k6';
import http from 'k6/http';
import { BASE_URL, createSourceAndWait, createTeamAndRule, ingest, runId } from './lib.js';

const EVENTS = Number(__ENV.EVENTS || 500);
const WINDOW_SEC = Number(__ENV.WINDOW_SEC || 60);
const VUS = Number(__ENV.VUS || 10);

export const options = {
  scenarios: {
    burst: {
      // A fixed number of iterations (not an arrival rate), so the expected counter is exact.
      executor: 'shared-iterations',
      vus: VUS,
      iterations: EVENTS,
      maxDuration: `${WINDOW_SEC * 3}s`,
    },
  },
  thresholds: {
    'http_req_failed{phase:ingest}': ['rate==0'],
    'http_req_duration{phase:ingest}': ['p(95)<300'],
    'checks{phase:verify}': ['rate==1'],
  },
  setupTimeout: '60s',
  teardownTimeout: '120s',
};

export function setup() {
  const run = runId();
  const ids = createTeamAndRule(`k6 burst ${run}`, 'k6run', run);
  const source = `k6-burst-${run}`;
  createSourceAndWait(source);
  return { run, source, ...ids };
}

export default function (data) {
  const res = ingest(data.source, { k6run: data.run, type: 'TRADE_REJECTED', instrument: 'EUR-IRS-10Y' });
  check(res, { 'ingest answered 202': (r) => r.status === 202 }, { phase: 'ingest' });
  // Spread EVENTS over WINDOW_SEC across all VUs.
  sleep((WINDOW_SEC * VUS) / EVENTS);
}

export function teardown(data) {
  // Repeats are flushed to Postgres once a second: poll until the counter settles.
  let alerts = [];
  for (let i = 0; i < 60; i++) {
    alerts = http.get(`${BASE_URL}/api/alerts?teamId=${data.teamId}`, { tags: { phase: 'verify' } }).json('items') || [];
    if (alerts.length === 1 && alerts[0].occurrencesCount >= EVENTS) break;
    sleep(1);
  }
  console.log(`alerts for team ${data.teamId}: ${JSON.stringify(alerts.map((a) => ({ id: a.id, count: a.occurrencesCount })))}`);
  check(alerts, {
    'exactly one alert': (a) => a.length === 1,
    [`occurrencesCount == ${EVENTS}`]: (a) => a.length === 1 && a[0].occurrencesCount === EVENTS,
  }, { phase: 'verify' });
}
