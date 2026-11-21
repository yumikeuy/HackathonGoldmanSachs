package com.hackathon.alerting.rules.unrouted;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** {@code GET /api/alerts/{id}/team-suggestions} (tag {@code alerts} in {@code frontend/openapi.yaml}). */
@RestController
public class TeamSuggestionController {

    private final TeamSuggestionService suggestions;

    public TeamSuggestionController(TeamSuggestionService suggestions) {
        this.suggestions = suggestions;
    }

    @GetMapping("/api/alerts/{id}/team-suggestions")
    public List<TeamSuggestion> suggest(@PathVariable long id) {
        return suggestions.suggest(id);
    }
}
