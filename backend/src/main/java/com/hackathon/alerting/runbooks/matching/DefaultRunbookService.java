package com.hackathon.alerting.runbooks.matching;

import com.hackathon.alerting.rules.model.Alert;
import com.hackathon.alerting.runbooks.RunbookService;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

/**
 * STEP-0 STUB of {@link RunbookService} (owner Role 3): attaches nothing.
 * Role 3 replaces the body with cache-based selection as described in the contract.
 */
@Service
public class DefaultRunbookService implements RunbookService {

    @Override
    public void attach(Alert alert, JsonNode event) {
        // stub: no runbook is attached until Role 3 implements selection
    }
}
