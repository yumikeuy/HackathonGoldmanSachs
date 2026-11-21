package com.hackathon.alerting.rules.api;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Rules CRUD and dry run ({@code /api/rules}, tag {@code rules} in {@code frontend/openapi.yaml}). */
@RestController
@RequestMapping("/api/rules")
public class RuleController {

    private final RuleService rules;
    private final DryRunService dryRun;

    public RuleController(RuleService rules, DryRunService dryRun) {
        this.rules = rules;
        this.dryRun = dryRun;
    }

    @GetMapping
    public List<RuleDto> list(@RequestParam(required = false) Long teamId) {
        return rules.list(teamId);
    }

    @GetMapping("/{id}")
    public RuleDto get(@PathVariable long id) {
        return rules.get(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public RuleDto create(@Valid @RequestBody RuleInput input) {
        return rules.create(input);
    }

    @PutMapping("/{id}")
    public RuleDto update(@PathVariable long id, @Valid @RequestBody RuleInput input) {
        return rules.update(id, input);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable long id) {
        rules.delete(id);
    }

    @PostMapping("/dry-run")
    public DryRunResult dryRun(@Valid @RequestBody DryRunRequest request) {
        return dryRun.run(request);
    }
}
