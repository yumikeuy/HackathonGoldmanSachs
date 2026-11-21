package com.hackathon.alerting.dev;

import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /api/dev/generate} (tag {@code dev} in {@code frontend/openapi.yaml}). Exists only while
 * {@code alerting.dev.generator-enabled=true} (env {@code DEV_GENERATOR_ENABLED}); otherwise the path answers 404.
 */
@RestController
@ConditionalOnProperty(name = "alerting.dev.generator-enabled", havingValue = "true")
public class GeneratorController {

    private final EventGenerator generator;

    public GeneratorController(EventGenerator generator) {
        this.generator = generator;
    }

    @PostMapping("/api/dev/generate")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public GenerateAccepted generate(@Valid @RequestBody GenerateRequest request) {
        return generator.start(request);
    }
}
