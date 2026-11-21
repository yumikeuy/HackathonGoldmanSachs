package com.hackathon.alerting.delivery.simulator;

import com.hackathon.alerting.common.web.CursorPage;
import com.hackathon.alerting.common.web.Cursors;
import com.hackathon.alerting.delivery.model.Channel;
import com.hackathon.alerting.delivery.repository.OutboxTaskRepository;
import com.hackathon.alerting.delivery.simulator.ChannelSwitchboard.ChannelState;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Delivery simulator: the outbox newest first, and channel switches. Live attempts go to {@code /topic/simulator}. */
@RestController
@RequestMapping("/api/simulator")
public class SimulatorController {

    private final OutboxTaskRepository outbox;
    private final ChannelSwitchboard switchboard;

    public SimulatorController(OutboxTaskRepository outbox, ChannelSwitchboard switchboard) {
        this.outbox = outbox;
        this.switchboard = switchboard;
    }

    @GetMapping("/deliveries")
    @Transactional(readOnly = true)
    public CursorPage<Delivery> deliveries(@RequestParam(required = false) String cursor,
                                           @RequestParam(required = false) Integer limit) {
        return CursorPage.of(outbox.findAllByOrderByIdDesc(Cursors.decode(cursor), Cursors.limit(limit)), Delivery::from);
    }

    @GetMapping("/channels")
    public List<ChannelState> channels() {
        return switchboard.states();
    }

    @PostMapping("/channels/{channel}/toggle")
    public ChannelState toggle(@PathVariable Channel channel) {
        return switchboard.toggle(channel);
    }
}
