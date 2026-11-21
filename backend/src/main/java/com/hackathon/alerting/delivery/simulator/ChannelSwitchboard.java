package com.hackathon.alerting.delivery.simulator;

import com.hackathon.alerting.delivery.model.Channel;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * On/off switches of the simulated providers ({@code POST /api/simulator/channels/{c}/toggle}). While a channel is
 * down every attempt on it fails and is retried by the outbox, so nothing is lost when it comes back.
 * State is per instance and resets on restart (all channels up).
 */
@Component
public class ChannelSwitchboard {

    private final Map<Channel, Boolean> enabled = new EnumMap<>(Channel.class);

    public ChannelSwitchboard() {
        for (Channel channel : Channel.values()) {
            enabled.put(channel, true);
        }
    }

    public synchronized boolean isEnabled(Channel channel) {
        return enabled.get(channel);
    }

    /** Flips the channel and returns its new state. */
    public synchronized ChannelState toggle(Channel channel) {
        enabled.put(channel, !enabled.get(channel));
        return new ChannelState(channel, enabled.get(channel));
    }

    public synchronized List<ChannelState> states() {
        return enabled.entrySet().stream().map(e -> new ChannelState(e.getKey(), e.getValue())).toList();
    }

    /** {@code ChannelState} of openapi.yaml. */
    public record ChannelState(Channel channel, boolean enabled) {
    }
}
