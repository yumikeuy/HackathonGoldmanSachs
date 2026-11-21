package com.hackathon.alerting.delivery;

import com.hackathon.alerting.delivery.model.Channel;
import com.hackathon.alerting.delivery.model.OutboxTask;
import com.hackathon.alerting.delivery.model.Team;
import com.hackathon.alerting.delivery.model.User;
import com.hackathon.alerting.delivery.repository.OutboxTaskRepository;
import com.hackathon.alerting.delivery.repository.TeamRepository;
import com.hackathon.alerting.delivery.repository.UserRepository;
import com.hackathon.alerting.rules.model.Alert;
import com.hackathon.alerting.support.JpaRepositoryTest;
import com.hackathon.alerting.support.TestData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.ScrollPosition;
import org.springframework.data.domain.Window;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static com.hackathon.alerting.support.TestData.json;
import static org.assertj.core.api.Assertions.assertThat;

@JpaRepositoryTest
class DeliveryRepositoriesTest {

    private static final Instant NOW = Instant.now().truncatedTo(ChronoUnit.MICROS);

    @Autowired
    private TeamRepository teams;

    @Autowired
    private UserRepository users;

    @Autowired
    private OutboxTaskRepository outbox;

    @Autowired
    private TestEntityManager em;

    private TestData data;
    private Team team;
    private User user;
    private Alert alert;

    @BeforeEach
    void setUp() {
        data = new TestData(em);
        team = data.team();
        user = data.user(team);
        alert = data.alert(data.rule(team), NOW);
    }

    @Test
    void findsTeamsAndUsers() {
        User inactive = data.user(team);
        inactive.setActive(false);
        em.flush();

        assertThat(teams.findByName(team.getName())).contains(team);
        assertThat(teams.existsByName(team.getName())).isTrue();
        assertThat(users.findByEmail(user.getEmail())).contains(user);
        assertThat(users.findAllByTeamIdOrderByIdAsc(team.getId())).containsExactly(user, inactive);
        assertThat(users.findAllByActiveTrueOrderByIdAsc()).contains(user).doesNotContain(inactive);
    }

    @Test
    void locksOnlyDuePendingRowsInDueOrderUpToTheLimit() {
        OutboxTask dueLater = task(NOW.minusSeconds(1));
        OutboxTask dueFirst = task(NOW.minusSeconds(10));
        OutboxTask dueSecond = task(NOW.minusSeconds(5));
        task(NOW.plusSeconds(60));                   // not due yet
        OutboxTask sent = task(NOW.minusSeconds(20));
        sent.markSent(NOW);
        em.flush();

        assertThat(outbox.lockDueBatch(NOW, 2)).containsExactly(dueFirst, dueSecond);
        assertThat(outbox.lockDueBatch(NOW, 100)).containsExactly(dueFirst, dueSecond, dueLater);
    }

    @Test
    void deletesOnlySentRowsOlderThanTheCutoff() {
        OutboxTask oldSent = task(NOW);
        oldSent.markSent(NOW.minus(2, ChronoUnit.DAYS));
        OutboxTask freshSent = task(NOW);
        freshSent.markSent(NOW);
        OutboxTask pending = task(NOW);
        em.flush();

        assertThat(outbox.deleteSentBefore(NOW.minus(1, ChronoUnit.DAYS))).isEqualTo(1);
        em.clear();
        assertThat(outbox.findAllByAlertIdOrderByIdAsc(alert.getId()))
                .extracting(OutboxTask::getId)
                .containsExactly(freshSent.getId(), pending.getId());
    }

    @Test
    void pagesDeliveriesNewestFirst() {
        OutboxTask first = task(NOW);
        OutboxTask second = task(NOW);
        OutboxTask third = task(NOW);
        em.flush();

        Window<OutboxTask> page1 = outbox.findAllByOrderByIdDesc(ScrollPosition.keyset(), Limit.of(2));
        Window<OutboxTask> page2 = outbox.findAllByOrderByIdDesc(page1.positionAt(page1.size() - 1), Limit.of(2));

        assertThat(page1.getContent()).containsExactly(third, second);
        assertThat(page1.hasNext()).isTrue();
        assertThat(page2.getContent()).containsExactly(first);
        assertThat(page2.hasNext()).isFalse();
    }

    /** A PENDING email task that becomes due at {@code nextRetryAt}. Every test rolls back, so the table holds only these. */
    private OutboxTask task(Instant nextRetryAt) {
        OutboxTask task = OutboxTask.toUser(alert, user, Channel.EMAIL, json("{}"));
        task.scheduleRetry("test", nextRetryAt);
        return em.persist(task);
    }
}
