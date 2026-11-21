package com.hackathon.alerting.runbooks.model;

import com.hackathon.alerting.delivery.model.Team;
import jakarta.persistence.Basic;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import tools.jackson.databind.JsonNode;

import java.util.Objects;

/**
 * A runbook (table {@code runbooks}): an instruction file of any format, stored as is.
 * The service never looks inside the file; the {@code Content-Type} on download is derived
 * from {@link #getFileName()} via {@code MediaTypeFactory}.
 *
 * <p><b>{@link #getContent() content} is lazy</b> (up to 10 MB). Thanks to Hibernate bytecode
 * enhancement it is fetched only when the getter is called. Even so, listings and the cache must
 * use projections selecting {@code id, team_id, file_name, condition} only — never the entity
 * graph — so the bytes are never touched by accident.
 */
@Entity
@Table(name = "runbooks")
public class Runbook {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Owning team; {@code null} means a global runbook. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "team_id")
    private Team team;

    /**
     * When to attach the runbook to a new alert (AGENTS.md §5).
     * {@code null} — only through {@code rules.override_runbook_id}.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "condition")
    private JsonNode condition;

    /** Original file name, e.g. {@code restart-workflow.pdf}. */
    @Column(name = "file_name", nullable = false)
    private String fileName;

    /** Raw file bytes (bytea). Lazy — see class docs. */
    @Basic(fetch = FetchType.LAZY)
    @Column(name = "content", nullable = false)
    private byte[] content;

    protected Runbook() {
        // for JPA
    }

    /**
     * @param team     owning team, {@code null} for a global runbook
     * @param fileName original file name
     * @param content  file bytes as uploaded
     */
    public Runbook(Team team, String fileName, byte[] content) {
        this.team = team;
        replaceFile(fileName, content);
    }

    public Long getId() {
        return id;
    }

    public Team getTeam() {
        return team;
    }

    public void setTeam(Team team) {
        this.team = team;
    }

    /** Whether the runbook is global (not owned by a team). */
    public boolean isGlobal() {
        return team == null;
    }

    public JsonNode getCondition() {
        return condition;
    }

    public void setCondition(JsonNode condition) {
        this.condition = condition;
    }

    public String getFileName() {
        return fileName;
    }

    /** Raw file bytes. Triggers a separate SELECT of the {@code content} column on first access. */
    public byte[] getContent() {
        return content;
    }

    /** Replace the file (name and bytes together, since the name defines the content type). */
    public void replaceFile(String fileName, byte[] content) {
        this.fileName = Objects.requireNonNull(fileName, "fileName");
        this.content = Objects.requireNonNull(content, "content");
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Runbook other)) {
            return false;
        }
        return id != null && id.equals(other.getId());
    }

    @Override
    public int hashCode() {
        return Runbook.class.hashCode();
    }

    /** Never includes {@code content}, so logging does not load the file. */
    @Override
    public String toString() {
        return "Runbook{id=" + id + ", fileName='" + fileName + "'}";
    }
}
