package com.hackathon.alerting.delivery.routing;

import com.hackathon.alerting.delivery.model.Channel;
import com.hackathon.alerting.delivery.model.ChannelPreference;
import com.hackathon.alerting.delivery.model.Team;
import com.hackathon.alerting.delivery.model.User;
import com.hackathon.alerting.delivery.model.WorkingHours;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChannelRouterTest {

    /** Monday 2026-11-23 10:00 in Warsaw (UTC+1). */
    private static final Instant MONDAY_10 = Instant.parse("2026-11-23T09:00:00Z");
    /** Monday 2026-11-23 21:00 in Warsaw. */
    private static final Instant MONDAY_21 = Instant.parse("2026-11-23T20:00:00Z");
    /** Saturday 2026-11-21 10:00 in Warsaw. */
    private static final Instant SATURDAY_10 = Instant.parse("2026-11-21T09:00:00Z");

    private static final WorkingHours OFFICE = new WorkingHours(LocalTime.of(8, 0), LocalTime.of(20, 0), List.of(1, 2, 3, 4, 5));
    private static final List<Channel> EMAIL_SMS_UI = List.of(Channel.EMAIL, Channel.SMS, Channel.UI);

    @Test
    void firstPreferenceThatTheRuleAllowsWinsDuringWorkingHours() {
        User user = user(OFFICE, ChannelPreference.anyTime(Channel.CALL), ChannelPreference.anyTime(Channel.SMS),
                ChannelPreference.anyTime(Channel.EMAIL));

        assertThat(ChannelRouter.choose(user, EMAIL_SMS_UI, MONDAY_10)).contains(Channel.SMS);
    }

    @Test
    void preferenceWithoutHoursIsSilentOutsideWorkingHours() {
        User user = user(OFFICE, ChannelPreference.anyTime(Channel.EMAIL));

        assertThat(ChannelRouter.choose(user, EMAIL_SMS_UI, MONDAY_21)).isEmpty();
        assertThat(ChannelRouter.choose(user, EMAIL_SMS_UI, SATURDAY_10)).isEmpty();
    }

    @Test
    void explicitHoursApplyEvenOutsideWorkingHours() {
        User user = user(OFFICE, new ChannelPreference(Channel.SMS, "08-22"), ChannelPreference.anyTime(Channel.EMAIL));

        assertThat(ChannelRouter.choose(user, EMAIL_SMS_UI, MONDAY_21)).contains(Channel.SMS);
        assertThat(ChannelRouter.choose(user, EMAIL_SMS_UI, Instant.parse("2026-11-23T22:30:00Z"))).isEmpty();
    }

    @Test
    void usesTheUsersTimeZone() {
        User user = user(null, new ChannelPreference(Channel.SMS, "08-20"));
        user.setTimezone(ZoneId.of("America/New_York"));

        // 09:00 UTC is 04:00 in New York
        assertThat(ChannelRouter.choose(user, EMAIL_SMS_UI, MONDAY_10)).isEmpty();
    }

    @Test
    void skipsChannelsTheUserCannotBeReachedOn() {
        User user = user(OFFICE, ChannelPreference.anyTime(Channel.SMS), ChannelPreference.anyTime(Channel.EMAIL));
        user.setPhone(null);

        assertThat(ChannelRouter.choose(user, EMAIL_SMS_UI, MONDAY_10)).contains(Channel.EMAIL);
    }

    @Test
    void withoutPreferencesTheRulesFirstPersonalChannelIsUsedDuringWorkingHours() {
        User user = user(OFFICE);

        assertThat(ChannelRouter.choose(user, List.of(Channel.UI, Channel.SMS, Channel.EMAIL), MONDAY_10)).contains(Channel.SMS);
        assertThat(ChannelRouter.choose(user, List.of(Channel.UI), MONDAY_10)).isEmpty();
    }

    @Test
    void overnightShiftBelongsToTheDayItStarted() {
        WorkingHours nights = new WorkingHours(LocalTime.of(22, 0), LocalTime.of(6, 0), List.of(5));
        User user = user(nights, ChannelPreference.anyTime(Channel.EMAIL));

        // Saturday 03:00 Warsaw is still Friday's night shift
        assertThat(ChannelRouter.choose(user, EMAIL_SMS_UI, Instant.parse("2026-11-21T02:00:00Z"))).contains(Channel.EMAIL);
        // Sunday 03:00 is Saturday's night — not a working day
        assertThat(ChannelRouter.choose(user, EMAIL_SMS_UI, Instant.parse("2026-11-22T02:00:00Z"))).isEmpty();
    }

    @Test
    void hourWindowsParseAndWrapAroundMidnight() {
        assertThat(HourWindow.parse("22-06").contains(23)).isTrue();
        assertThat(HourWindow.parse("22-06").contains(5)).isTrue();
        assertThat(HourWindow.parse("22-06").contains(6)).isFalse();
        assertThat(HourWindow.parse("00-24").contains(23)).isTrue();
        assertThat(HourWindow.isValid("8-20")).isFalse();
        assertThat(HourWindow.isValid("25-26")).isFalse();
        assertThatThrownBy(() -> HourWindow.parse("10-10")).isInstanceOf(IllegalArgumentException.class);
    }

    private static User user(WorkingHours hours, ChannelPreference... preferences) {
        User user = new User(new Team("Team"), "Anna");
        user.setEmail("anna@bank.com");
        user.setPhone("+48600000000");
        user.setWorkingHours(hours);
        user.setChannelPreferences(List.of(preferences));
        return user;
    }
}
