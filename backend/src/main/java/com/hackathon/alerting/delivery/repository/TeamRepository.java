package com.hackathon.alerting.delivery.repository;

import com.hackathon.alerting.delivery.model.Team;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/** Access to {@code teams}. Event processing reads teams from the cache, never from here. */
public interface TeamRepository extends JpaRepository<Team, Long> {

    Optional<Team> findByName(String name);

    boolean existsByName(String name);

    /** All teams in a stable order, for the cache and the admin page. */
    List<Team> findAllByOrderByIdAsc();
}
