/**
 * UI login and authorization (docs/security.md): JWT access tokens and rotating refresh tokens, roles
 * VIEWER &lt; OPERATOR &lt; ADMIN, WebSocket authentication, signed runbook links. Everything is enforced only when
 * {@code alerting.security.jwt.enabled} is true; otherwise every request is allowed as before. Owner: Role 1.
 */
package com.hackathon.alerting.common.security;
