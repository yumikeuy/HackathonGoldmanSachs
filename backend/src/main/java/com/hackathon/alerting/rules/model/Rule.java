package com.hackathon.alerting.rules.model;

import com.hackathon.alerting.delivery.model.Channel;
import com.hackathon.alerting.delivery.model.Team;
import com.hackathon.alerting.runbooks.model.Runbook;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * A team's rule (table {@code rules}): WHEN an event of the team becomes an alert and HOW
 * that alert is graded, grouped, rendered and delivered.
 *
 * <p>Rules are compiled into the in-memory cache and reloaded on {@code config.changed};
 * the event hot path never reads this entity from the database.
 */
@Entity
@Table(name = "rules")
public class Rule {

    /** Default of {@code rules.dedup_window_sec}. */
    public static final int DEFAULT_DEDUP_WINDOW_SEC = 300;
    /** Default of {@code rules.priority}. */
    public static final int DEFAULT_PRIORITY = 100;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "team_id", nullable = false)
    private Team team;

    /** Condition tree (AGENTS.md §5); {@code null} matches every event of the team. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "condition")
    private JsonNode condition;

    /** Evaluation order when several rules of a team match (all of them fire). */
    @Column(name = "priority", nullable = false)
    private int priority = DEFAULT_PRIORITY;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    @Enumerated(EnumType.STRING)
    @Column(name = "assigned_severity", nullable = false, length = 10)
    private Severity assignedSeverity = Severity.MEDIUM;

    /** Channels to notify through, e.g. {@code ["EMAIL","UI"]}. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "channels", nullable = false)
    private List<Channel> channels = List.of(Channel.UI);

    /** Event paths whose values identify "the same" alert, e.g. {@code ["instrument"]}. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "group_by_paths", nullable = false)
    private List<String> groupByPaths = List.of();

    @Column(name = "group_mode", nullable = false, length = 12)
    private GroupMode groupMode = GroupMode.WHILE_OPEN;

    /** Deduplication window in seconds; always positive. */
    @Column(name = "dedup_window_sec", nullable = false)
    private int dedupWindowSec = DEFAULT_DEDUP_WINDOW_SEC;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "presentation", nullable = false)
    private Presentation presentation = Presentation.DEFAULT;

    /** Condition on an incoming event that automatically resolves the open alert; {@code null} — never. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "resolve_condition")
    private JsonNode resolveCondition;

    /** Runbook always attached to this rule's alerts, bypassing runbook conditions. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "override_runbook_id")
    private Runbook overrideRunbook;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Rule() {
        // for JPA
    }

    public Rule(Team team, String name) {
        this.team = Objects.requireNonNull(team, "team");
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

    /** The owning team. A rule never moves between teams, so there is no setter. */
    public Team getTeam() {
        return team;
    }

    public JsonNode getCondition() {
        return condition;
    }

    public void setCondition(JsonNode condition) {
        this.condition = condition;
    }

    public int getPriority() {
        return priority;
    }

    public void setPriority(int priority) {
        this.priority = priority;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public Severity getAssignedSeverity() {
        return assignedSeverity;
    }

    /** @throws IllegalArgumentException for {@link Severity#UNKNOWN}, which only unrouted alerts have */
    public void setAssignedSeverity(Severity assignedSeverity) {
        Objects.requireNonNull(assignedSeverity, "assignedSeverity");
        if (!assignedSeverity.isAssignableByRule()) {
            throw new IllegalArgumentException("A rule cannot assign severity " + assignedSeverity);
        }
        this.assignedSeverity = assignedSeverity;
    }

    public List<Channel> getChannels() {
        return channels;
    }

    /** Stores an immutable copy; {@code null} means no channels. */
    public void setChannels(List<Channel> channels) {
        this.channels = channels == null ? List.of() : List.copyOf(channels);
    }

    public List<String> getGroupByPaths() {
        return groupByPaths;
    }

    /** Stores an immutable copy; {@code null} means no grouping paths (one alert per rule). */
    public void setGroupByPaths(List<String> groupByPaths) {
        this.groupByPaths = groupByPaths == null ? List.of() : List.copyOf(groupByPaths);
    }

    public GroupMode getGroupMode() {
        return groupMode;
    }

    public void setGroupMode(GroupMode groupMode) {
        this.groupMode = Objects.requireNonNull(groupMode, "groupMode");
    }

    public int getDedupWindowSec() {
        return dedupWindowSec;
    }

    /** The deduplication window as a {@link Duration}. */
    public Duration getDedupWindow() {
        return Duration.ofSeconds(dedupWindowSec);
    }

    /** @throws IllegalArgumentException if not positive */
    public void setDedupWindowSec(int dedupWindowSec) {
        if (dedupWindowSec <= 0) {
            throw new IllegalArgumentException("dedupWindowSec must be positive, got " + dedupWindowSec);
        }
        this.dedupWindowSec = dedupWindowSec;
    }

    public Presentation getPresentation() {
        return presentation;
    }

    /** {@code null} means {@link Presentation#DEFAULT}. */
    public void setPresentation(Presentation presentation) {
        this.presentation = presentation == null ? Presentation.DEFAULT : presentation;
    }

    public JsonNode getResolveCondition() {
        return resolveCondition;
    }

    public void setResolveCondition(JsonNode resolveCondition) {
        this.resolveCondition = resolveCondition;
    }

    public Runbook getOverrideRunbook() {
        return overrideRunbook;
    }

    public void setOverrideRunbook(Runbook overrideRunbook) {
        this.overrideRunbook = overrideRunbook;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Rule other)) {
            return false;
        }
        return id != null && id.equals(other.getId());
    }

    @Override
    public int hashCode() {
        return Rule.class.hashCode();
    }

    @Override
    public String toString() {
        return "Rule{id=" + id + ", name='" + name + "', severity=" + assignedSeverity + ", active=" + active + '}';
    }
}
