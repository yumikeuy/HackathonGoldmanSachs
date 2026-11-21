/**
 * Spring Data repositories for tables {@code runbooks} and {@code audit_logs}. Owner: Role 3.
 *
 * <p>Runbook listings and the cache use {@link com.hackathon.alerting.runbooks.repository.RunbookSummary}
 * projections that never select {@code content}; bytes are read only through
 * {@link com.hackathon.alerting.runbooks.repository.RunbookRepository#findFileById}.
 */
package com.hackathon.alerting.runbooks.repository;
