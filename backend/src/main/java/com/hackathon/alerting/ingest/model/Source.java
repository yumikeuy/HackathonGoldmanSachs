package com.hackathon.alerting.ingest.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;

import java.time.Instant;
import java.util.Objects;

/**
 * An event source (table {@code sources}).
 *
 * <p>Not linked to other tables: an event refers to its source by name only
 * ({@code events.source_name}, the message key in {@code events.ingest}).
 * Changes to a source are published to the Redis channel {@code config.changed}.
 */
@Entity
@Table(name = "sources")
public class Source {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Unique name, also the URL segment of {@code /api/ingest/{source}}: {@code payments-monitoring}. */
    @Column(name = "name", nullable = false, unique = true, length = 100)
    private String name;

    @Column(name = "type", nullable = false, length = 20)
    private SourceType type;

    /** Type-specific settings, e.g. {@code {"topic": "workflow.events"}}. Always a JSON object. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "config", nullable = false)
    private JsonNode config;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Source() {
        // for JPA
    }

    /**
     * @param name   unique source name
     * @param type   source type
     * @param config JSON object with settings; {@code null} means an empty object
     */
    public Source(String name, SourceType type, JsonNode config) {
        setName(name);
        setType(type);
        setConfig(config);
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = Objects.requireNonNull(name, "name");
    }

    public SourceType getType() {
        return type;
    }

    public void setType(SourceType type) {
        this.type = Objects.requireNonNull(type, "type");
    }

    public JsonNode getConfig() {
        return config;
    }

    /** {@code null} becomes an empty object — the column is NOT NULL and must hold an object. */
    public void setConfig(JsonNode config) {
        this.config = config == null ? JsonNodeFactory.instance.objectNode() : config;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Source other)) {
            return false;
        }
        return id != null && id.equals(other.getId());
    }

    @Override
    public int hashCode() {
        return Source.class.hashCode();
    }

    @Override
    public String toString() {
        return "Source{id=" + id + ", name='" + name + "', type=" + type + ", active=" + active + '}';
    }
}
