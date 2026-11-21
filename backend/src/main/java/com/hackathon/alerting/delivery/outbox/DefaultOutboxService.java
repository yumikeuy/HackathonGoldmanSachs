package com.hackathon.alerting.delivery.outbox;

import com.hackathon.alerting.delivery.model.Channel;
import com.hackathon.alerting.delivery.model.OutboxTask;
import com.hackathon.alerting.delivery.model.User;
import com.hackathon.alerting.delivery.repository.OutboxTaskRepository;
import com.hackathon.alerting.delivery.routing.ChannelRouter;
import com.hackathon.alerting.delivery.routing.UserDirectory;
import com.hackathon.alerting.rules.model.Alert;
import com.hackathon.alerting.rules.model.Rule;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * {@link OutboxService}: turns a new alert into {@code delivery_outbox} rows in the caller's transaction.
 *
 * <ul>
 *   <li>{@code UI} among the rule's channels → one row for the team feed ({@code user_id = NULL});</li>
 *   <li>personal channels (EMAIL / SMS / CALL) → for every active member of the team at most one row, on the
 *       channel {@link ChannelRouter} picks from the user's preferences, working hours and time zone.</li>
 * </ul>
 * Users come from {@link UserDirectory} (cache), messages are rendered by {@link MessageComposer}.
 */
@Service
public class DefaultOutboxService implements OutboxService {

    private final OutboxTaskRepository outbox;
    private final UserDirectory users;
    private final MessageComposer messages;
    private final Clock clock;

    public DefaultOutboxService(OutboxTaskRepository outbox, UserDirectory users, MessageComposer messages, Clock clock) {
        this.outbox = outbox;
        this.users = users;
        this.messages = messages;
        this.clock = clock;
    }

    @Override
    public void enqueue(Alert alert, Rule rule) {
        if (alert.getTeam() == null) {
            return;
        }
        List<Channel> channels = rule.getChannels();
        List<OutboxTask> tasks = new ArrayList<>();
        if (channels.contains(Channel.UI)) {
            tasks.add(OutboxTask.toTeamFeed(alert, alert.getTeam(), messages.forTeamFeed(alert)));
        }
        if (channels.stream().anyMatch(Channel::isPersonal)) {
            Instant now = clock.instant();
            for (User user : users.activeMembers(alert.getTeam().getId())) {
                ChannelRouter.choose(user, channels, now).ifPresent(channel ->
                        tasks.add(OutboxTask.toUser(alert, user, channel, messages.forUser(alert, user, channel))));
            }
        }
        outbox.saveAll(tasks);
    }
}
