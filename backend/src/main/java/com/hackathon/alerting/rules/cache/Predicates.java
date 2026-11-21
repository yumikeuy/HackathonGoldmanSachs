package com.hackathon.alerting.rules.cache;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;

import java.util.function.Predicate;

/** Evaluation guard for compiled conditions. */
final class Predicates {

    private static final Logger log = LoggerFactory.getLogger(Predicates.class);

    private Predicates() {
    }

    /**
     * Evaluates a compiled condition, treating an exception as "no match".
     * A buggy predicate must not fail every event of the stream (and send the whole topic to the DLT);
     * the problem is logged with the owner of the condition instead.
     */
    static boolean safeTest(Predicate<JsonNode> predicate, JsonNode event, Object owner) {
        try {
            return predicate.test(event);
        } catch (RuntimeException e) {
            log.warn("Condition of {} failed to evaluate, treated as no match", owner, e);
            return false;
        }
    }
}
