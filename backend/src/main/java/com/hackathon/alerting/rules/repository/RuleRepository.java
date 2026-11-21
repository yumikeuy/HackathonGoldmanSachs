package com.hackathon.alerting.rules.repository;

import com.hackathon.alerting.rules.model.Rule;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/** Access to {@code rules}. Event processing uses the compiled rule cache, never this repository. */
public interface RuleRepository extends JpaRepository<Rule, Long> {

    /** Active rules of all teams in evaluation order, for the cache. */
    List<Rule> findAllByActiveTrueOrderByPriorityAscIdAsc();

    /**
     * Same as {@link #findAllByActiveTrueOrderByPriorityAscIdAsc()} with the team fetched, so the
     * returned (later detached) rules can be used without a session: {@code rule.getTeam()} is initialized.
     */
    @EntityGraph(attributePaths = "team")
    List<Rule> findAllWithTeamByActiveTrueOrderByPriorityAscIdAsc();

    /** All rules of a team in evaluation order (rules page). */
    List<Rule> findAllByTeamIdOrderByPriorityAscIdAsc(Long teamId);

    /** All rules of all teams, grouped by team and in evaluation order (rules page without a team filter). */
    List<Rule> findAllByOrderByTeamIdAscPriorityAscIdAsc();
}
