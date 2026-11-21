package com.hackathon.alerting.runbooks.repository;

import com.hackathon.alerting.runbooks.model.Runbook;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

/**
 * Access to {@code runbooks}.
 *
 * <p>AGENTS.md: never select {@code content} in listings. Use the {@code *Summaries} queries for
 * lists and the cache and {@link #findFileById} for downloads. {@link #findById} is safe too —
 * {@code content} is lazy — but loads an entity graph that listings do not need.
 */
public interface RunbookRepository extends JpaRepository<Runbook, Long> {

    /** Every runbook (team and global) without bytes, for the runbook cache. */
    @Query("""
            select new com.hackathon.alerting.runbooks.repository.RunbookSummary(r.id, r.team.id, r.fileName, r.condition)
            from Runbook r
            order by r.id
            """)
    List<RunbookSummary> findAllSummaries();

    /** A team's own runbooks without bytes (runbooks page). Served by {@code idx_runbooks_team}. */
    @Query("""
            select new com.hackathon.alerting.runbooks.repository.RunbookSummary(r.id, r.team.id, r.fileName, r.condition)
            from Runbook r
            where r.team.id = :teamId
            order by r.id
            """)
    List<RunbookSummary> findSummariesByTeamId(Long teamId);

    /** Global runbooks ({@code team_id IS NULL}) without bytes. */
    @Query("""
            select new com.hackathon.alerting.runbooks.repository.RunbookSummary(r.id, r.team.id, r.fileName, r.condition)
            from Runbook r
            where r.team is null
            order by r.id
            """)
    List<RunbookSummary> findGlobalSummaries();

    /** One runbook's metadata without bytes. */
    @Query("""
            select new com.hackathon.alerting.runbooks.repository.RunbookSummary(r.id, r.team.id, r.fileName, r.condition)
            from Runbook r
            where r.id = :id
            """)
    Optional<RunbookSummary> findSummaryById(Long id);

    /** File name and bytes for download; the only query that reads {@code content}. */
    @Query("select new com.hackathon.alerting.runbooks.repository.RunbookFile(r.fileName, r.content) from Runbook r where r.id = :id")
    Optional<RunbookFile> findFileById(Long id);
}
