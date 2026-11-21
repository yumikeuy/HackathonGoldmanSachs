package com.hackathon.alerting.delivery.repository;

import com.hackathon.alerting.delivery.model.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * Access to {@code users}. Delivery routing reads users from the cache; these queries serve
 * cache reloads and the admin API.
 */
public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);

    /** Members of a team, active or not (admin page). Served by index {@code idx_users_team}. */
    List<User> findAllByTeamIdOrderByIdAsc(Long teamId);

    /** Everyone who can receive notifications, for the cache. */
    List<User> findAllByActiveTrueOrderByIdAsc();
}
