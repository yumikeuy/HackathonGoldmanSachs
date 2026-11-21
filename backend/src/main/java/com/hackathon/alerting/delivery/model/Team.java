package com.hackathon.alerting.delivery.model;

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

import java.time.Instant;
import java.util.Objects;

/**
 * A team (table {@code teams}) — the unit of routing: an event belongs to a team
 * when it matches the team's {@link #getCondition() condition}.
 *
 * <p>A team has no contacts, channels or working hours — those belong to {@link User}.
 * Associations with users and rules are unidirectional (owned by {@code users.team_id}
 * and {@code rules.team_id}); there are intentionally no collections here — the hot path works from the cache
 * and lists are read with repository queries.
 */
@Entity
@Table(name = "teams")
public class Team {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Unique name: {@code Team Rates}. */
    @Column(name = "name", nullable = false, unique = true, length = 100)
    private String name;

    /**
     * Condition tree (format: AGENTS.md §5) selecting the events that belong to the team.
     * {@code null} means no event belongs to the team.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "condition")
    private JsonNode condition;

    /** Conditions of the team and its rules match exact paths and types only. */
    @Column(name = "strict", nullable = false)
    private boolean strict;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Team() {
        // for JPA
    }

    public Team(String name) {
        setName(name);
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

    public JsonNode getCondition() {
        return condition;
    }

    public void setCondition(JsonNode condition) {
        this.condition = condition;
    }

    public boolean isStrict() {
        return strict;
    }

    public void setStrict(boolean strict) {
        this.strict = strict;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Team other)) {
            return false;
        }
        return id != null && id.equals(other.getId());
    }

    @Override
    public int hashCode() {
        return Team.class.hashCode();
    }

    @Override
    public String toString() {
        return "Team{id=" + id + ", name='" + name + "', strict=" + strict + '}';
    }
}
