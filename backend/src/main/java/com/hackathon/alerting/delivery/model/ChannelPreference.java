package com.hackathon.alerting.delivery.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Objects;

/**
 * An element of {@code users.channel_preferences}: {@code {"channel":"SMS","hours":"08-20"}}.
 *
 * <p>The list is ordered by descending priority. How the channel is actually chosen
 * given the hours and {@link WorkingHours} is decided by {@code delivery.routing}.
 *
 * @param channel the channel
 * @param hours   hour window in the user's time zone, format {@code HH-HH} (end exclusive),
 *                {@code null} means any time
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChannelPreference(Channel channel, String hours) {

    public ChannelPreference {
        Objects.requireNonNull(channel, "channel");
    }

    /** A preference without an hour restriction. */
    public static ChannelPreference anyTime(Channel channel) {
        return new ChannelPreference(channel, null);
    }
}
