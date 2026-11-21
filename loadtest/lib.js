// Shared helpers for the k6 scenarios: REST calls against the running service and test-data setup.
import http from 'k6/http';
import { sleep } from 'k6';

export const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const JSON_PARAMS = { headers: { 'Content-Type': 'application/json' }, tags: { phase: 'setup' } };

/** POSTs JSON and fails setup loudly on an unexpected status. */
export function postJson(path, body, expectedStatus) {
  const res = http.post(`${BASE_URL}${path}`, JSON.stringify(body), JSON_PARAMS);
  if (expectedStatus && res.status !== expectedStatus) {
    throw new Error(`POST ${path} -> ${res.status}: ${res.body}`);
  }
  return res;
}

/** A unique id for this run, used in names and in a field every event carries. */
export function runId() {
  return `${Date.now()}-${Math.floor(Math.random() * 1e6)}`;
}

/** Creates a team that owns events with `field == value`, and one catch-all rule; returns their ids. */
export function createTeamAndRule(name, field, value, rule = {}) {
  const team = postJson('/api/teams', { name, condition: { path: field, op: '==', value } }, 201).json();
  const created = postJson('/api/rules', { name: `${name} rule`, teamId: team.id, ...rule }, 201).json();
  return { teamId: team.id, ruleId: created.id };
}

/**
 * Creates an HTTP source and waits until the service knows it. Configuration reaches the caches asynchronously
 * (Redis Pub/Sub, in order), so: probe with an invalid body until the answer changes from 404 (unknown source) to
 * 400 (bad JSON) — by then teams and rules created before the source are loaded too.
 */
export function createSourceAndWait(name) {
  postJson('/api/sources', { name, type: 'http' }, 201);
  for (let i = 0; i < 60; i++) {
    const probe = http.post(`${BASE_URL}/api/ingest/${name}`, '{', JSON_PARAMS);
    if (probe.status === 400) {
      sleep(0.5); // let the listeners that reload teams and rules finish
      return;
    }
    sleep(0.5);
  }
  throw new Error(`source ${name} did not become active`);
}

/** Sends one event to a source; tagged so thresholds can single out ingestion. */
export function ingest(source, event) {
  return http.post(`${BASE_URL}/api/ingest/${source}`, JSON.stringify(event), {
    headers: { 'Content-Type': 'application/json' },
    tags: { phase: 'ingest', name: 'POST /api/ingest/{source}' },
  });
}
