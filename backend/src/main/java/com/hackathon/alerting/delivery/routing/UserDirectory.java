package com.hackathon.alerting.delivery.routing;

import com.hackathon.alerting.common.config.ConfigChangedEvent;
import com.hackathon.alerting.common.config.ConfigKinds;
import com.hackathon.alerting.delivery.model.User;
import com.hackathon.alerting.delivery.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Active users grouped by team, kept in memory so that {@code OutboxService.enqueue} never queries the database
 * for configuration (AGENTS.md §10). Reloaded at startup and on {@link ConfigKinds#USERS} / {@link ConfigKinds#TEAMS}.
 *
 * <p>The cached {@link User} entities are detached, with their team initialised; they are only used as
 * references for new {@code delivery_outbox} rows and must not be modified.
 */
@Component
public class UserDirectory {

    private static final Logger log = LoggerFactory.getLogger(UserDirectory.class);

    private final UserRepository users;
    private volatile Map<Long, List<User>> byTeam = Map.of();
    private volatile boolean loaded;

    public UserDirectory(UserRepository users) {
        this.users = users;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        reload();
    }

    @EventListener
    public void onConfigChanged(ConfigChangedEvent event) {
        if (event.is(ConfigKinds.USERS) || event.is(ConfigKinds.TEAMS)) {
            reload();
        }
    }

    public synchronized void reload() {
        byTeam = users.findAllActiveWithTeam().stream()
                .collect(Collectors.groupingBy(user -> user.getTeam().getId(), Collectors.toUnmodifiableList()));
        loaded = true;
        log.info("Loaded active users of {} teams", byTeam.size());
    }

    /** Active users of a team, in id order. */
    public List<User> activeMembers(long teamId) {
        if (!loaded) {
            reload();
        }
        return byTeam.getOrDefault(teamId, List.of());
    }
}
