package com.hackathon.alerting.runbooks.model;

import com.hackathon.alerting.rules.model.Alert;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;

import java.time.Instant;
import java.util.Objects;

/**
 * An audit record (table {@code audit_logs}). Append-only: {@link Immutable} makes Hibernate
 * ignore any changes; rows go away only together with their alert (ON DELETE CASCADE).
 *
 * <p>IDs come from a sequence stepping by 50 (pooled optimizer) so records written in the alert's
 * transaction are inserted in JDBC batches — see V2__constraints_and_indexes.sql.
 */
@Entity
@Immutable
@Table(name = "audit_logs")
public class AuditLog {

    /** Actor for actions performed by the service itself. */
    public static final String SYSTEM_ACTOR = "system";
    /** Length of {@code audit_logs.actor}. */
    public static final int ACTOR_MAX_LENGTH = 100;
    /** Length of {@code audit_logs.action_type}. */
    public static final int ACTION_TYPE_MAX_LENGTH = 50;

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "audit_logs_id_gen")
    @SequenceGenerator(name = "audit_logs_id_gen", sequenceName = "audit_logs_id_seq", allocationSize = 50)
    private Long id;

    /** The alert this record is about; {@code null} for actions not tied to an alert. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "alert_id")
    private Alert alert;

    /** Who did it: a user email, {@link #SYSTEM_ACTOR} or {@code rule:7} (see {@link #ruleActor}). */
    @Column(name = "actor", nullable = false, length = ACTOR_MAX_LENGTH)
    private String actor;

    /** What was done, e.g. {@link AuditActions#ACK}; free-form, up to 50 characters. */
    @Column(name = "action_type", nullable = false, length = ACTION_TYPE_MAX_LENGTH)
    private String actionType;

    /** Free-form details of the action. Always a JSON object. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "details", nullable = false)
    private JsonNode details;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected AuditLog() {
        // for JPA
    }

    /**
     * @param alert      the alert, or {@code null}
     * @param actor      who performed the action
     * @param actionType what was done, see {@link AuditActions}
     * @param details    extra data; {@code null} means an empty object
     * @throws IllegalArgumentException if actor or actionType is blank or longer than its column
     */
    public AuditLog(Alert alert, String actor, String actionType, JsonNode details) {
        this.alert = alert;
        this.actor = requireText(actor, "actor", ACTOR_MAX_LENGTH);
        this.actionType = requireText(actionType, "actionType", ACTION_TYPE_MAX_LENGTH);
        this.details = details == null ? JsonNodeFactory.instance.objectNode() : details;
    }

    /** Actor string for an action performed by a rule: {@code rule:7}. */
    public static String ruleActor(long ruleId) {
        return "rule:" + ruleId;
    }

    public Long getId() {
        return id;
    }

    public Alert getAlert() {
        return alert;
    }

    public String getActor() {
        return actor;
    }

    public String getActionType() {
        return actionType;
    }

    public JsonNode getDetails() {
        return details;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    private static String requireText(String value, String name, int maxLength) {
        Objects.requireNonNull(value, name);
        if (value.isBlank() || value.length() > maxLength) {
            throw new IllegalArgumentException(name + " must be 1.." + maxLength + " characters, got '" + value + "'");
        }
        return value;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof AuditLog other)) {
            return false;
        }
        return id != null && id.equals(other.getId());
    }

    @Override
    public int hashCode() {
        return AuditLog.class.hashCode();
    }

    @Override
    public String toString() {
        return "AuditLog{id=" + id + ", actor='" + actor + "', actionType='" + actionType + "'}";
    }
}
