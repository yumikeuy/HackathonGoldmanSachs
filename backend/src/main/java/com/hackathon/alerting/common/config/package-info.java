/**
 * Cross-module configuration and constants: WebSocket (STOMP) setup and destinations, Kafka topics and listener error handling
 * (DLT, retry forever while Postgres/Redis are down), the application clock, configuration-change notification over Redis ({@link com.hackathon.alerting.common.config.ConfigChangedPublisher}).
 * Owner: Role 1.
 */
package com.hackathon.alerting.common.config;
