package com.hackathon.alerting.rules.repository;

import com.hackathon.alerting.rules.model.Rule;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/** Access to {@code rules}. Event processing uses the compiled rule cache, never this repository. */
public interface RuleRepository extends JpaRepository<Rule, Long> {

    /** Active rules of all teams in evaluation order, for the cache. */
    List<Rule> findAllByActiveTrueOrderByPriorityAscIdAsc();

    /** All rules of a team in evaluation order (rules page). */
    List<Rule> findAllByTeamIdOrderByPriorityAscIdAsc(Long teamId);
}
