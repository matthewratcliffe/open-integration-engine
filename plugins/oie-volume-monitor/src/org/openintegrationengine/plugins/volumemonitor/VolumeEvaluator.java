/*
 * SPDX-License-Identifier: MPL-2.0
 */
package org.openintegrationengine.plugins.volumemonitor;

import com.mirth.connect.donkey.model.channel.DeployedState;
import com.mirth.connect.model.DashboardStatus;
import com.mirth.connect.model.ServerEvent;
import com.mirth.connect.model.filters.MessageFilter;
import com.mirth.connect.server.controllers.ControllerFactory;
import com.mirth.connect.server.controllers.EngineController;
import com.mirth.connect.server.controllers.EventController;
import com.mirth.connect.server.controllers.MessageController;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Counts messages against rules and decides what is wrong.
 *
 * <p>Counts messages that <b>arrived</b>, from the source connector, whatever became of
 * them afterwards. A channel receiving its usual traffic and erroring on all of it is a
 * different fault with different handling, and the engine already alerts on it; conflating
 * the two here would mean a volume alert that cannot be read without further digging.
 *
 * <p>Evaluation state lives in memory, not in the database. It is entirely derived -- every
 * count is recomputed from scratch each cycle -- so persisting it would add a write path
 * and a migration for no information. The one consequence is that a restart re-notifies an
 * ongoing breach once, which is the right way round: better a duplicate than silence.
 */
public final class VolumeEvaluator {

    private static final Logger LOG = LogManager.getLogger(VolumeEvaluator.class);

    /** Where a rule stands. Several are non-faults that still need to be visible. */
    public enum State {
        /** Threshold met. */
        OK,
        /** Below threshold, with enough history for that to mean something. */
        BREACH,
        /** The channel is not running, so there is nothing to count. Reported as a fault. */
        CHANNEL_STOPPED,
        /** Below threshold, but the channel has no history older than the window. */
        WARMING_UP,
        /** Outside the rule's active hours; not evaluated. */
        OUTSIDE_SCHEDULE,
        /** Switched off by an administrator. */
        DISABLED,
        /** The rule names a channel this server does not have. */
        UNKNOWN_CHANNEL,
        /** The count itself failed. Never silently an OK. */
        ERROR
    }

    /** The outcome of evaluating one rule. */
    public static final class Result {
        public String ruleId = "";
        public String name = "";
        public String channelId = "";
        public String channelName = "";
        public State state = State.OK;
        public String detail = "";
        public long count;
        public int minCount;
        public String rule = "";
        public String schedule = "";
        public long evaluatedAt;
        public long windowStart;
        /** When the current run of faults began, or 0 when healthy. */
        public long faultSince;

        /** Whether this state is something someone should act on. */
        public boolean isFault() {
            return state == State.BREACH || state == State.CHANNEL_STOPPED
                || state == State.ERROR;
        }
    }

    /** Per-rule memory, so a continuing breach is not re-announced every cycle. */
    private static final class Tracker {
        State lastState = State.OK;
        long faultSince;
        long lastNotifiedAt;
    }

    private final Map<String, Tracker> trackers = new ConcurrentHashMap<>();
    private final ZoneId zone;

    /** Last full pass, kept so the UI can render without triggering a fresh count. */
    private volatile List<Result> lastPass = List.of();
    private volatile long lastPassAt;

    public VolumeEvaluator() {
        this(ZoneId.systemDefault());
    }

    VolumeEvaluator(ZoneId zone) {
        this.zone = zone;
    }

    public List<Result> getLastPass() {
        return lastPass;
    }

    public long getLastPassAt() {
        return lastPassAt;
    }

    /**
     * Evaluates every rule and notifies on any change.
     *
     * <p>Notification happens here rather than in the caller so that an on-demand
     * evaluation from the UI behaves identically to the scheduled one. A breach found by
     * someone pressing a button is the same breach.
     */
    public synchronized List<Result> evaluateAll(boolean notify) {
        List<VolumeRule> rules = VolumeRuleStore.load();
        Map<String, DashboardStatus> channels = channelStatuses();
        ZonedDateTime now = ZonedDateTime.now(zone);

        List<Result> results = new ArrayList<>();
        for (VolumeRule rule : rules) {
            Result r = evaluate(rule, channels, now);
            if (notify) {
                notifyIfNeeded(rule, r, now);
            }
            results.add(r);
        }

        // Rules deleted since the last pass would otherwise keep their tracker for the
        // lifetime of the server, and a rule recreated with the same id would inherit a
        // stale "already notified".
        trackers.keySet().retainAll(rules.stream().map(VolumeRule::getId).toList());

        lastPass = List.copyOf(results);
        lastPassAt = System.currentTimeMillis();
        return lastPass;
    }

    private Map<String, DashboardStatus> channelStatuses() {
        Map<String, DashboardStatus> out = new HashMap<>();
        try {
            EngineController engine = ControllerFactory.getFactory().createEngineController();
            for (DashboardStatus s : engine.getChannelStatusList()) {
                out.put(s.getChannelId(), s);
            }
        } catch (Exception e) {
            LOG.error("volume monitor could not list channel statuses", e);
        }
        return out;
    }

    Result evaluate(VolumeRule rule, Map<String, DashboardStatus> channels, ZonedDateTime now) {
        Result r = new Result();
        r.ruleId = rule.getId();
        r.name = rule.getName();
        r.channelId = rule.getChannelId();
        r.minCount = rule.getMinCount();
        r.rule = rule.describe();
        r.schedule = describeSchedule(rule);
        r.evaluatedAt = now.toInstant().toEpochMilli();
        r.windowStart = now.minusMinutes(rule.windowMinutes()).toInstant().toEpochMilli();

        DashboardStatus status = channels.get(rule.getChannelId());
        r.channelName = status == null ? "" : status.getName();

        if (!rule.isEnabled()) {
            r.state = State.DISABLED;
            r.detail = "Rule is switched off.";
            return r;
        }
        if (status == null) {
            r.state = State.UNKNOWN_CHANNEL;
            r.detail = "No channel on this server has that id. It may have been deleted, "
                + "or the rule may have come from another environment.";
            return r;
        }
        if (status.getState() != DeployedState.STARTED) {
            // Reported as a fault, not skipped. A channel someone bothered to write a
            // volume rule for is one that is meant to be running, and "stopped" is a more
            // actionable sentence than "0 of 500 messages".
            r.state = State.CHANNEL_STOPPED;
            r.detail = "The channel is " + String.valueOf(status.getState()).toLowerCase()
                + ", so no messages can arrive.";
            return r;
        }
        if (!rule.isActiveAt(now)) {
            r.state = State.OUTSIDE_SCHEDULE;
            r.detail = "Outside the rule's active hours (" + r.schedule + ").";
            return r;
        }

        try {
            r.count = countBetween(rule.getChannelId(), r.windowStart, r.evaluatedAt);
        } catch (Exception e) {
            r.state = State.ERROR;
            r.detail = "Could not count messages: " + e.getMessage();
            LOG.error("volume monitor could not count messages for channel {}",
                rule.getChannelId(), e);
            return r;
        }

        if (r.count >= rule.getMinCount()) {
            r.state = State.OK;
            r.detail = r.count + " in the last " + humanWindow(rule) + ".";
            return r;
        }

        // Below the threshold. Before calling that a breach, check the channel has been
        // running longer than the window: a channel deployed an hour ago cannot yet have
        // met a daily target, and reporting that as a fault on its first day is how a
        // monitor loses its audience.
        try {
            if (countBetween(rule.getChannelId(), 0L, r.windowStart) == 0L) {
                r.state = State.WARMING_UP;
                r.detail = r.count + " in the last " + humanWindow(rule)
                    + ", but this channel has no messages older than that, so the window "
                    + "is not yet meaningful.";
                return r;
            }
        } catch (Exception e) {
            // Not fatal: fall through and report the breach. Failing to establish history
            // is not a reason to stay quiet about a feed that has gone quiet.
            LOG.warn("volume monitor could not check message history for channel {}: {}",
                rule.getChannelId(), e.toString());
        }

        r.state = State.BREACH;
        r.detail = r.count + " in the last " + humanWindow(rule) + ", expected at least "
            + rule.getMinCount() + " (" + (rule.getMinCount() - r.count) + " short).";
        return r;
    }

    /** Messages that arrived on a channel's source connector in a period. */
    private long countBetween(String channelId, long fromEpochMs, long toEpochMs) {
        MessageFilter filter = new MessageFilter();
        if (fromEpochMs > 0) {
            filter.setStartDate(calendar(fromEpochMs));
        }
        filter.setEndDate(calendar(toEpochMs));
        // The source connector only. A channel with three destinations would otherwise
        // count each message once per connector and report four times its real traffic.
        filter.setIncludedMetaDataIds(List.of(0));

        Long count = ControllerFactory.getFactory()
            .createMessageController()
            .getMessageCount(filter, channelId);
        return count == null ? 0L : count;
    }

    private Calendar calendar(long epochMs) {
        GregorianCalendar c = new GregorianCalendar();
        c.setTimeInMillis(epochMs);
        return c;
    }

    // ------------------------------------------------------------------
    // Notification
    // ------------------------------------------------------------------

    private void notifyIfNeeded(VolumeRule rule, Result r, ZonedDateTime now) {
        Tracker t = trackers.computeIfAbsent(rule.getId(), k -> new Tracker());
        long nowMs = now.toInstant().toEpochMilli();

        if (!r.isFault()) {
            if (t.lastState == State.BREACH || t.lastState == State.CHANNEL_STOPPED
                || t.lastState == State.ERROR) {
                // Recovery is worth saying: it closes the loop for whoever saw the alert
                // and stops them chasing something that has already fixed itself.
                LOG.warn("volume monitor recovered: channel '{}' ({}) meets {} again -- {}",
                    label(r), r.channelId, rule.describe(), r.detail);
                writeEvent(r, rule, true);
            }
            t.lastState = r.state;
            t.faultSince = 0;
            t.lastNotifiedAt = 0;
            return;
        }

        boolean isNew = t.faultSince == 0;
        if (isNew) {
            t.faultSince = nowMs;
        }
        r.faultSince = t.faultSince;

        boolean stateChanged = t.lastState != r.state;
        boolean dueAgain = rule.getRenotifyMinutes() > 0
            && t.lastNotifiedAt > 0
            && nowMs - t.lastNotifiedAt >= rule.getRenotifyMinutes() * 60_000L;

        t.lastState = r.state;

        if (!(isNew || stateChanged || dueAgain)) {
            return;
        }
        t.lastNotifiedAt = nowMs;

        LOG.warn("volume monitor {}: channel '{}' ({}) expected {} -- {}",
            r.state == State.CHANNEL_STOPPED ? "channel not running" : "below threshold",
            label(r), r.channelId, rule.describe(), r.detail);
        writeEvent(r, rule, false);
    }

    /**
     * What to call this rule in a log line or an event.
     *
     * <p>The rule's own name wins when it has one: "Pathology results overnight" says
     * more to whoever is woken up than a channel name, and one channel can carry several
     * rules that would otherwise be indistinguishable in the log.
     */
    private String label(Result r) {
        if (!r.name.isEmpty()) {
            return r.channelName.isEmpty() ? r.name : r.name + "' on '" + r.channelName;
        }
        return r.channelName.isEmpty() ? r.channelId : r.channelName;
    }

    /**
     * Writes the breach into the engine's own event log.
     *
     * <p>Chosen over a bespoke notification path because the engine already has one: the
     * Events view, retention, and alerts that watch server events. A plugin that sent its
     * own email would be a second thing to configure and a second thing to forget.
     */
    private void writeEvent(Result r, VolumeRule rule, boolean recovered) {
        if (!VolumeRuleStore.writeServerEvents()) {
            return;
        }
        try {
            EventController events = ControllerFactory.getFactory().createEventController();
            String name = recovered
                ? "Message volume recovered"
                : r.state == State.CHANNEL_STOPPED
                    ? "Monitored channel is not running"
                    : "Message volume below threshold";

            Map<String, String> attributes = new LinkedHashMap<>();
            if (!r.name.isEmpty()) {
                attributes.put("rule name", r.name);
            }
            attributes.put("channel",
                r.channelName.isEmpty() ? r.channelId : r.channelName);
            attributes.put("channelId", r.channelId);
            attributes.put("rule", rule.describe());
            attributes.put("observed", Long.toString(r.count));
            attributes.put("expected", Integer.toString(rule.getMinCount()));
            attributes.put("window", humanWindow(rule));
            attributes.put("detail", r.detail);

            ServerEvent event = new ServerEvent();
            event.setName(name);
            event.setLevel(recovered ? ServerEvent.Level.INFORMATION : ServerEvent.Level.WARNING);
            event.setOutcome(recovered ? ServerEvent.Outcome.SUCCESS : ServerEvent.Outcome.FAILURE);
            event.setChannelId(r.channelId);
            event.setEventTime(calendar(r.evaluatedAt));
            event.setAttributes(attributes);
            events.insertEvent(event);
        } catch (Exception e) {
            // A monitor that cannot log must still monitor. The server log line above has
            // already been written, so the breach is not lost.
            LOG.error("volume monitor could not write a server event", e);
        }
    }

    // ------------------------------------------------------------------
    // Text
    // ------------------------------------------------------------------

    static String humanWindow(VolumeRule rule) {
        String unit = rule.getWindowUnit().name().toLowerCase(java.util.Locale.ROOT);
        return rule.getWindowCount() == 1 ? unit : rule.getWindowCount() + " " + unit + "s";
    }

    static String describeSchedule(VolumeRule rule) {
        if (rule.isAlwaysActive()) {
            return "always";
        }
        StringBuilder sb = new StringBuilder();
        if (rule.getActiveDays().isEmpty()) {
            sb.append("every day");
        } else {
            List<String> names = new ArrayList<>();
            for (java.time.DayOfWeek d : java.time.DayOfWeek.values()) {
                if (rule.getActiveDays().contains(d)) {
                    names.add(d.name().substring(0, 1) + d.name().substring(1, 3).toLowerCase());
                }
            }
            sb.append(String.join(" ", names));
        }
        if (rule.getActiveFromMinute() != rule.getActiveUntilMinute()
            && !(rule.getActiveFromMinute() == 0
                 && rule.getActiveUntilMinute() == VolumeRule.DAY_MINUTES)) {
            sb.append(' ').append(hhmm(rule.getActiveFromMinute()))
              .append('-').append(hhmm(rule.getActiveUntilMinute()));
        }
        return sb.toString();
    }

    static String hhmm(int minuteOfDay) {
        int m = minuteOfDay % (24 * 60);
        return String.format("%02d:%02d", m / 60, m % 60);
    }

    /** Epoch millis as an ISO instant, for the API. */
    static String iso(long epochMs) {
        return epochMs <= 0 ? "" : Instant.ofEpochMilli(epochMs).toString();
    }
}
