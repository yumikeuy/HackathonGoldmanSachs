package com.hackathon.alerting.delivery.routing;

import com.hackathon.alerting.delivery.model.Channel;
import com.hackathon.alerting.delivery.model.ChannelPreference;
import com.hackathon.alerting.delivery.model.User;
import com.hackathon.alerting.delivery.model.WorkingHours;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.Collection;
import java.util.Optional;

/**
 * Picks the personal channel (EMAIL / SMS / CALL) for one user and one alert.
 *
 * <p>Only channels the rule asks for and the user can be reached on (email address for EMAIL, phone for SMS and
 * CALL) are considered. The user's {@code channelPreferences} are tried in order:
 * <ul>
 *   <li>a preference with {@code hours} applies while the current hour in the user's time zone is inside the
 *       window — also outside working hours ("SMS 08-22" reaches me in the evening);</li>
 *   <li>a preference without {@code hours} applies only during working hours.</li>
 * </ul>
 * A user without preferences gets the first suitable channel of the rule, during working hours.
 * A user without working hours is always working. No match means no personal notification —
 * the alert still reaches the team through the UI feed.
 */
public final class ChannelRouter {

    private ChannelRouter() {
    }

    public static Optional<Channel> choose(User user, Collection<Channel> ruleChannels, Instant now) {
        ZonedDateTime local = now.atZone(user.getTimezone());
        boolean working = isWorking(user.getWorkingHours(), local);

        if (user.getChannelPreferences().isEmpty()) {
            return working
                    ? ruleChannels.stream().filter(channel -> isReachable(user, channel)).findFirst()
                    : Optional.empty();
        }
        for (ChannelPreference preference : user.getChannelPreferences()) {
            Channel channel = preference.channel();
            if (!ruleChannels.contains(channel) || !isReachable(user, channel)) {
                continue;
            }
            boolean inWindow = preference.hours() == null
                    ? working
                    : HourWindow.parse(preference.hours()).contains(local.getHour());
            if (inWindow) {
                return Optional.of(channel);
            }
        }
        return Optional.empty();
    }

    /** Whether the user has the contact the channel needs. UI is never personal. */
    static boolean isReachable(User user, Channel channel) {
        return switch (channel) {
            case EMAIL -> user.getEmail() != null;
            case SMS, CALL -> user.getPhone() != null;
            case UI -> false;
        };
    }

    /** Working day and time inside {@code [from, to)}; a shift may cross midnight ({@code 22:00–06:00}). */
    static boolean isWorking(WorkingHours hours, ZonedDateTime local) {
        if (hours == null) {
            return true;
        }
        LocalTime time = local.toLocalTime();
        boolean overnight = hours.from().isAfter(hours.to());
        boolean inTime = overnight
                ? !time.isBefore(hours.from()) || time.isBefore(hours.to())
                : !time.isBefore(hours.from()) && time.isBefore(hours.to());
        if (!inTime) {
            return false;
        }
        if (hours.days().isEmpty()) {
            return true;
        }
        // After midnight of an overnight shift the shift belongs to the previous day.
        ZonedDateTime shiftDay = overnight && time.isBefore(hours.to()) ? local.minusDays(1) : local;
        return hours.days().contains(shiftDay.getDayOfWeek().getValue());
    }
}
