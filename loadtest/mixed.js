// Mixed traffic: the sample events from payloads/ at a constant rate, to measure ingest latency and processing lag.
//
//   docker run --rm --network host -v "$PWD/loadtest":/scripts grafana/k6 run /scripts/mixed.js
//
// Env: BASE_URL (default http://localhost:8080), RATE events/s (100), DURATION (1m), MAX_VUS (200).
import { check } from 'k6';
import http from 'k6/http';
import { BASE_URL, createSourceAndWait, ingest, runId } from './lib.js';

const RATE = Number(__ENV.RATE || 100);
const DURATION = __ENV.DURATION || '1m';

const PAYLOADS = [
  JSON.parse(open('./payloads/rates-trade-rejected.json')),
  JSON.parse(open('./payloads/pricing-workflow-failed.json')),
  JSON.parse(open('./payloads/infra-cpu-high.json')),
  JSON.parse(open('./payloads/alien-schema.json')),
];

export const options = {
  scenarios: {
    mixed: {
      executor: 'constant-arrival-rate',
      rate: RATE,
      timeUnit: '1s',
      duration: DURATION,
      preAllocatedVUs: Math.min(RATE, 50),
      maxVUs: Number(__ENV.MAX_VUS || 200),
    },
  },
  thresholds: {
    'http_req_failed{phase:ingest}': ['rate<0.01'],
    'http_req_duration{phase:ingest}': ['p(95)<250', 'p(99)<500'],
    dropped_iterations: ['count==0'],
  },
  setupTimeout: '60s',
};

export function setup() {
  const source = `k6-mixed-${runId()}`;
  createSourceAndWait(source);
  return { source };
}

export default function (data) {
  const event = Object.assign({}, PAYLOADS[Math.floor(Math.random() * PAYLOADS.length)], { k6seq: __ITER });
  check(ingest(data.source, event), { 'ingest answered 202': (r) => r.status === 202 }, { phase: 'ingest' });
}

export function teardown() {
  const lag = http.get(`${BASE_URL}/api/ingest/lag`);
  console.log(`consumer lag after the run: ${lag.body}`);
}
