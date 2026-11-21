package com.hackathon.alerting.rules.alert;

import com.hackathon.alerting.common.web.CursorPage;
import com.hackathon.alerting.rules.model.AlertStatus;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Alert feed, card, ack and resolve ({@code /api/alerts}, tag {@code alerts} in {@code frontend/openapi.yaml}). */
@RestController
@RequestMapping("/api/alerts")
public class AlertController {

    private final AlertService alerts;

    public AlertController(AlertService alerts) {
        this.alerts = alerts;
    }

    @GetMapping
    public CursorPage<AlertSummary> feed(@RequestParam(required = false) Long teamId,
                                         @RequestParam(defaultValue = "false") boolean unrouted,
                                         @RequestParam(name = "status", required = false) List<AlertStatus> statuses,
                                         @RequestParam(required = false) String cursor,
                                         @RequestParam(required = false) Integer limit) {
        return alerts.feed(teamId, unrouted, statuses, cursor, limit);
    }

    @GetMapping("/{id}")
    public AlertDetail get(@PathVariable long id) {
        return alerts.get(id);
    }

    @PostMapping("/{id}/ack")
    public AlertDetail acknowledge(@PathVariable long id, @Valid @RequestBody(required = false) ActorRequest request) {
        return alerts.acknowledge(id, ActorRequest.actorOf(request));
    }

    @PostMapping("/{id}/resolve")
    public AlertDetail resolve(@PathVariable long id, @Valid @RequestBody(required = false) ActorRequest request) {
        return alerts.resolve(id, ActorRequest.actorOf(request));
    }
}
