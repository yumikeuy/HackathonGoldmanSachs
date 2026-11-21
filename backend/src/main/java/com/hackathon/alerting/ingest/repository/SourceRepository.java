package com.hackathon.alerting.ingest.repository;

import com.hackathon.alerting.ingest.model.Source;
import com.hackathon.alerting.ingest.model.SourceType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/** Access to {@code sources}. */
public interface SourceRepository extends JpaRepository<Source, Long> {

    /** Source by its unique name, i.e. the {@code {source}} segment of {@code POST /api/ingest/{source}}. */
    Optional<Source> findByName(String name);

    boolean existsByName(String name);

    /** Active sources of one type, e.g. the external Kafka topics to subscribe to. */
    List<Source> findAllByTypeAndActiveTrueOrderByIdAsc(SourceType type);

    /** All sources for the cache and the admin page. */
    List<Source> findAllByOrderByIdAsc();
}
