# Security

> Owner: **Role 1**. Threat model, what protects against what, how to configure and how to show it in the demo.
> Code: `common.security` (login, tokens, roles), `common.web` (headers, safe downloads, errors),
> `common.config` (CORS, WebSocket origins), `ingest.source` (payload limits, rate limit, source API keys).

## Threat model

The service receives events from other systems over the network, stores files that people upload and shows both
in a browser. The realistic attacks, and what stops them:

| # | Threat | Where | Protection |
|---|---|---|---|
| 1 | **Fake alerts** — anyone who can reach the service posts events in a source's name | `POST /api/ingest/{source}` | per-source **API key** (`X-Api-Key`), stored as SHA-256 only → 401 |
| 2 | **Stored XSS through a runbook** — upload `evil.html` / an SVG with script, an operator opens it, the script runs on our origin with full API access | `GET /api/runbooks/{id}/file` | **safe serving**: only passive types inline, everything else as attachment; content sniffing; `CSP: sandbox`; `nosniff` |
| 3 | **Flooding** — one sender floods Kafka and the processor | ingest | **rate limit** per source (1000 events/s, 429 + `Retry-After`) |
| 4 | **Resource exhaustion** with one huge or deeply nested event | ingest (HTTP, Kafka, file) | **body ≤ 1 MB** (413), **JSON nesting ≤ 64** (400); Kafka/file payloads over the limits are kept as truncated text, never parsed |
| 5 | **Cross-site reading** of the live alert feed or the API from a foreign page in an operator's browser | `/ws`, `/api/**` | **origin allow-list** for WebSocket and CORS (`alerting.security.allowed-origins`) |
| 6 | **Clickjacking**, MIME confusion | all pages | `X-Frame-Options: DENY`, `frame-ancestors 'none'`, `nosniff`, `Referrer-Policy: no-referrer`, strict CSP on `/api/**` |
| 7 | **Information leakage** through management endpoints | `/actuator` | only `health` (no details) and `prometheus` exposed; `info` and everything else off |
| 8 | **Anyone changes rules or reads alerts** through the UI/API | `/api/**`, `/ws` | **login with JWT** and **roles** VIEWER / OPERATOR / ADMIN (when `JWT_ENABLED=true`) |
| 9 | **Stolen refresh token** reused | `/api/auth/refresh` | refresh tokens are **one-time** (rotation), stored as SHA-256, revoked on logout and when the user is deactivated |
| 10 | **Password guessing** | `/api/auth/login` | BCrypt hashes, 10 attempts per email per minute (429), same timing for unknown emails |

Accepted for the hackathon, not covered here: TLS (terminated by the platform in front of the service), secrets
management (compose uses development defaults — set `JWT_SECRET` and `ADMIN_PASSWORD`).

## Source API keys

A source accepts events from anyone until it gets a key:

```bash
curl -X POST localhost:8080/api/sources/1/api-key
# {"sourceName":"payments-monitoring","apiKey":"src_q0J8vR…","header":"X-Api-Key"}   ← shown once

curl -X POST localhost:8080/api/ingest/payments-monitoring -H 'Content-Type: application/json' -d '{}'           # 401
curl -X POST localhost:8080/api/ingest/payments-monitoring -H 'Content-Type: application/json' \
     -H 'X-Api-Key: src_q0J8vR…' -d '{}'                                                                           # 202

curl -X DELETE localhost:8080/api/sources/1/api-key    # open again
```

- 32 random bytes from `SecureRandom`; the database keeps only the SHA-256 in `sources.config.apiKeySha256`
  (no schema change). The hash is never returned by the API; `Source.apiKeyRequired` says whether a key is set.
- Comparison is constant-time. Calling `POST …/api-key` again rotates the key; `PUT /api/sources/{id}` keeps it
  and ignores a hash sent by the client.
- Only for `http` sources. Kafka and file sources are reached through infrastructure we control (topic ACLs,
  file system permissions).

## Safe runbook downloads

The task requires runbooks of **any** format (§2.6), so uploads are not restricted by type — a judge's file must
always be accepted. Instead, files are **served** so they cannot run code (`SafeFileDownloadAdvice`):

| File | `Content-Disposition` |
|---|---|
| PDF, plain text, Markdown, CSV, YAML, PNG/JPEG/GIF/WebP/BMP | `inline` (viewed in the browser) |
| HTML, SVG, XML, JavaScript, anything unknown | `attachment` (downloaded, never rendered on our origin) |
| a "text" file whose content starts like markup (`<html`, `<script`, `<svg`, `<?xml`, …) | `attachment` |

Every file response also gets `Content-Security-Policy: sandbox; default-src 'none'` — even when shown inline,
scripts, forms and plugins are disabled — and `X-Content-Type-Options: nosniff`. The runbooks module needed no
change: the advice only rewrites response headers.

For the UI (Role 4): render event payloads and runbook Markdown as text or through a sanitizer, never with
`dangerouslySetInnerHTML` on raw content.

## Configuration

| Property | Env | Default | |
|---|---|---|---|
| `alerting.ingest.max-body-size` | — | `1MB` | larger HTTP bodies → 413 |
| `alerting.ingest.max-json-depth` | — | `64` | deeper JSON → 400 |
| `alerting.ingest.rate-limit-per-second` | `INGEST_RATE_LIMIT` | `1000` | per HTTP source; `0` disables |
| `alerting.security.allowed-origins` | `ALLOWED_ORIGINS` | `http://localhost:*,http://127.0.0.1:*` | browser origins allowed cross-origin on `/api/**` and `/ws`; the UI served by the service is same-origin |
| `alerting.security.jwt.enabled` | `JWT_ENABLED` | `false` | enforce login on `/api/**` and `/ws` |
| `alerting.security.jwt.secret` | `JWT_SECRET` | dev value (warning logged) | HS256 key, ≥ 32 bytes; also keys the signed runbook links |
| `alerting.security.jwt.access-ttl` / `refresh-ttl` | — | `15m` / `7d` | token lifetimes |
| `alerting.security.admin.email` / `password` | `ADMIN_EMAIL` / `ADMIN_PASSWORD` | `admin@alerting.local` / `admin` (warning logged) | built-in administrator |

Rejections are counted in `alerting.ingest.rejected{reason}` with reasons `unauthorized`, `rate_limited`,
`too_large`, `invalid_json`, `unknown_source`, `kafka_unavailable`.

## Showing it in the demo

1. `POST /api/sources/{id}/api-key` on `payments-monitoring` → an event without the key gets **401**, with it **202**.
2. Upload `evil.html` (`<script>alert(document.cookie)</script>`) as a runbook → opening the link **downloads** it,
   nothing runs; a normal `.txt` runbook opens inline.
3. `curl` a 2 MB event → **413**; `[[[[…]]]]` 200 levels deep → **400**; the service keeps working.
4. A burst over the rate limit → **429** with `Retry-After`, other sources unaffected.
5. `JWT_ENABLED=true docker compose up -d` → the API answers **401** without a token; log in as Piotr (VIEWER) → can
   see alerts, `POST /api/rules` → **403**; as Anna (OPERATOR) → can ack; refresh token used twice → second time **401**.

## Login: JWT access + refresh tokens

Switched by `JWT_ENABLED` (`alerting.security.jwt.enabled`, default **false**). Off: every request is allowed as
before, but the login API already works, so the UI can be built against it. On: `/api/**` needs
`Authorization: Bearer <access token>`.

```bash
curl -X POST localhost:8080/api/auth/login -H 'Content-Type: application/json' \
     -d '{"email":"admin@alerting.local","password":"admin"}'
# {"accessToken":"eyJ…","refreshToken":"rt_…","tokenType":"Bearer","expiresIn":900,"user":{"role":"ADMIN",…}}

curl localhost:8080/api/teams -H 'Authorization: Bearer eyJ…'
curl -X POST localhost:8080/api/auth/refresh -H 'Content-Type: application/json' -d '{"refreshToken":"rt_…"}'
curl -X POST localhost:8080/api/auth/logout  -H 'Authorization: Bearer eyJ…' -H 'Content-Type: application/json' \
     -d '{"refreshToken":"rt_…"}'
```

| Token | Lifetime | Stored | Notes |
|---|---|---|---|
| access (JWT, HS256) | 15 min | nowhere — verified by signature | claims `sub`, `role`, `email`, `name`, `teamId`, `iss`, `exp`, `jti` |
| refresh (random 256 bit) | 7 days | Redis `auth:refresh:{sha256}` → user | **one-time**: `/refresh` returns a new pair and deletes the old one (`GETDEL`); `/logout` deletes it; a deactivated user or one without a password cannot refresh |

**Who logs in:** users with an email and a password (`password` in `POST/PUT /api/users`, stored as BCrypt;
`""` disables login; it is never returned — `canLogIn` instead), plus the built-in administrator
`ADMIN_EMAIL` / `ADMIN_PASSWORD` for the first login on an empty database.

**Roles** (each includes the ones below):

| Role | May |
|---|---|
| `VIEWER` | every `GET /api/**`, rule dry-run, live feed |
| `OPERATOR` | + ack, resolve, replay failed events |
| `ADMIN` | + every other change: sources, teams, users, rules, runbooks, simulator switches, generator |

Always open: `POST /api/auth/login|refresh`, `POST /api/ingest/{source}` (source API keys instead), UI pages, Swagger,
`/actuator/health`. Errors: 401 (no/invalid token) and 403 (role too low) as problem+json.

**WebSocket:** browsers cannot set headers on the handshake, so the token goes into the STOMP `CONNECT` frame as the
native header `Authorization: Bearer …`; without it the connection is refused.

**Runbook links:** a link in an email has no token, so with login enforced notifications carry a **signed link**
(`/api/runbooks/{id}/file?exp=…&sig=…`, HMAC-SHA256, 7 days, only that file). The UI gets a 5-minute one from
`GET /api/auth/runbook-links/{id}` to open a file in a new tab; or it downloads with the bearer token.

For the UI (Role 4): keep the access token in memory, the refresh token in `sessionStorage` (or memory), call
`/refresh` on a 401 once, then send the user to the login page.

Also recommended to Role 3: a time limit on `regex` conditions (catastrophic backtracking, ReDoS) and rejecting
control characters in uploaded file names.
