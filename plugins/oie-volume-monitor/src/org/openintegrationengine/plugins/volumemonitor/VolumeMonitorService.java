/*
 * SPDX-License-Identifier: MPL-2.0
 */
package org.openintegrationengine.plugins.volumemonitor;

import com.mirth.connect.model.DashboardStatus;
import com.mirth.connect.server.controllers.ControllerFactory;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.time.DayOfWeek;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What the servlet calls. Turns rules and evaluations into shapes the console can read.
 *
 * <p>Every list returned to the browser is a list of tab-separated strings rather than a
 * list of objects, and every list is a plain {@link ArrayList}. Both are the scars of the
 * same problem: the engine serialises API responses with XStream, whose rendering of an
 * immutable list is undecodable and whose rendering of a list of maps the console's
 * decoder cannot reliably unpick. A flat list of strings survives the round trip, and the
 * browser splits on the tab.
 */
public final class VolumeMonitorService {

    private static final Logger LOG = LogManager.getLogger(VolumeMonitorService.class);

    private final VolumeEvaluator evaluator;

    public VolumeMonitorService(VolumeEvaluator evaluator) {
        this.evaluator = evaluator;
    }

    public VolumeEvaluator evaluator() {
        return evaluator;
    }

    /**
     * Strips the field separator out of a value.
     *
     * <p>Channel names come from users and could contain a tab; a single stray tab would
     * otherwise shift every later field by one and silently corrupt the row.
     */
    private static String flat(String s) {
        return s == null ? "" : s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ');
    }

    // ------------------------------------------------------------------
    // Status
    // ------------------------------------------------------------------

    /**
     * The current state of every rule.
     *
     * @param evaluate true to count now; false to report the last scheduled pass
     */
    public Map<String, Object> status(boolean evaluate) {
        List<VolumeEvaluator.Result> results = evaluate
            ? evaluator.evaluateAll(true)
            : evaluator.getLastPass();

        // Never evaluated yet -- the server has only just started and the first scheduled
        // pass has not run. Counting once here is better than showing an empty page that
        // looks like "no rules configured".
        if (!evaluate && results.isEmpty() && evaluator.getLastPassAt() == 0
            && !VolumeRuleStore.load().isEmpty()) {
            results = evaluator.evaluateAll(true);
        }

        int faults = 0;
        List<String> rows = new ArrayList<>();
        for (VolumeEvaluator.Result r : results) {
            if (r.isFault()) {
                faults++;
            }
            rows.add(String.join("\t",
                flat(r.ruleId),
                flat(r.channelId),
                flat(r.channelName),
                r.state.name(),
                Long.toString(r.count),
                Integer.toString(r.minCount),
                flat(r.rule),
                flat(r.schedule),
                flat(r.detail),
                Long.toString(r.faultSince),
                Long.toString(r.evaluatedAt),
                // Appended rather than inserted: the rows are positional, so a new
                // field at the end leaves every existing index where the browser
                // already expects it.
                flat(r.name)));
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", Boolean.TRUE);
        out.put("faults", faults);
        out.put("ruleCount", results.size());
        out.put("lastPassAt", evaluator.getLastPassAt());
        out.put("intervalSeconds", VolumeRuleStore.evaluateIntervalSeconds());
        out.put("writeServerEvents", VolumeRuleStore.writeServerEvents());
        out.put("results", rows);
        return out;
    }

    /** Just the fault count, for the navigation badge. Cheap: no counting. */
    public Map<String, Object> summary() {
        int faults = 0;
        for (VolumeEvaluator.Result r : evaluator.getLastPass()) {
            if (r.isFault()) {
                faults++;
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", Boolean.TRUE);
        out.put("faults", faults);
        out.put("ruleCount", VolumeRuleStore.load().size());
        out.put("lastPassAt", evaluator.getLastPassAt());
        return out;
    }

    // ------------------------------------------------------------------
    // Rules
    // ------------------------------------------------------------------

    public Map<String, Object> rules() {
        List<String> rows = new ArrayList<>();
        Map<String, String> names = channelNames();
        for (VolumeRule r : VolumeRuleStore.load()) {
            rows.add(serialiseForUi(r, names.getOrDefault(r.getChannelId(), "")));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", Boolean.TRUE);
        out.put("rules", rows);
        return out;
    }

    private String serialiseForUi(VolumeRule r, String channelName) {
        List<String> days = new ArrayList<>();
        for (DayOfWeek d : DayOfWeek.values()) {
            if (r.getActiveDays().contains(d)) {
                days.add(d.name());
            }
        }
        return String.join("\t",
            flat(r.getId()),
            flat(r.getChannelId()),
            flat(channelName),
            Boolean.toString(r.isEnabled()),
            Integer.toString(r.getMinCount()),
            Integer.toString(r.getWindowCount()),
            r.getWindowUnit().name(),
            days.isEmpty() ? "*" : String.join(",", days),
            Integer.toString(r.getActiveFromMinute()),
            Integer.toString(r.getActiveUntilMinute()),
            Integer.toString(r.getRenotifyMinutes()),
            flat(r.getName()));
    }

    /**
     * Creates or updates a rule.
     *
     * <p>Rejects rather than saving-and-warning, unlike the git sync settings form. There
     * the point of a tolerant save was that a half-filled connection could be come back
     * to; a rule with no channel or a threshold of zero is not a work in progress, it is a
     * rule that would sit in the list pretending to monitor something.
     */
    public Map<String, Object> saveRule(Map<String, String> incoming) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (incoming == null) {
            incoming = Map.of();
        }

        String id = incoming.get("id");
        VolumeRule rule = (id == null || id.isBlank()) ? new VolumeRule() : VolumeRuleStore.find(id);
        if (rule == null) {
            out.put("ok", Boolean.FALSE);
            out.put("error", "That rule no longer exists; it may have been deleted.");
            return out;
        }

        if (incoming.containsKey("name")) {
            rule.setName(incoming.get("name"));
        }
        if (incoming.containsKey("channelId")) {
            rule.setChannelId(incoming.get("channelId"));
        }
        if (incoming.containsKey("enabled")) {
            rule.setEnabled(Boolean.parseBoolean(incoming.get("enabled")));
        }
        if (incoming.containsKey("minCount")) {
            rule.setMinCount(VolumeRule.parseInt(incoming.get("minCount"), rule.getMinCount()));
        }
        if (incoming.containsKey("windowCount")) {
            rule.setWindowCount(
                VolumeRule.parseInt(incoming.get("windowCount"), rule.getWindowCount()));
        }
        if (incoming.containsKey("windowUnit")) {
            rule.setWindowUnit(VolumeRule.parseWindow(incoming.get("windowUnit")));
        }
        if (incoming.containsKey("activeDays")) {
            rule.setActiveDays(VolumeRule.parseDays(incoming.get("activeDays")));
        }
        if (incoming.containsKey("activeFromMinute")) {
            rule.setActiveFromMinute(
                VolumeRule.parseInt(incoming.get("activeFromMinute"), rule.getActiveFromMinute()));
        }
        if (incoming.containsKey("activeUntilMinute")) {
            rule.setActiveUntilMinute(VolumeRule.parseInt(
                incoming.get("activeUntilMinute"), rule.getActiveUntilMinute()));
        }
        if (incoming.containsKey("renotifyMinutes")) {
            rule.setRenotifyMinutes(
                VolumeRule.parseInt(incoming.get("renotifyMinutes"), rule.getRenotifyMinutes()));
        }

        List<String> problems = new ArrayList<>(rule.problems());
        if (!problems.isEmpty()) {
            out.put("ok", Boolean.FALSE);
            out.put("error", "This rule is not complete.");
            out.put("problems", problems);
            return out;
        }

        VolumeRuleStore.save(rule);
        LOG.info("volume monitor rule '{}' saved: channel {} expects {} ({})",
            rule.getName().isEmpty() ? rule.getChannelId() : rule.getName(),
            rule.getChannelId(), rule.describe(), VolumeEvaluator.describeSchedule(rule));

        // Evaluate straight away so the list shows the real state of the new rule rather
        // than nothing until the next scheduled pass, which may be minutes away.
        evaluator.evaluateAll(true);

        out.put("ok", Boolean.TRUE);
        out.put("id", rule.getId());
        out.put("saved", Boolean.TRUE);
        return out;
    }

    public Map<String, Object> deleteRule(String id) {
        Map<String, Object> out = new LinkedHashMap<>();
        VolumeRule existing = VolumeRuleStore.find(id);
        if (existing == null) {
            // Idempotent: a delete of something already gone is the state the caller asked
            // for, and reporting it as an error only makes a double-click look broken.
            out.put("ok", Boolean.TRUE);
            out.put("deleted", Boolean.FALSE);
            return out;
        }
        VolumeRuleStore.delete(id);
        LOG.info("volume monitor rule deleted for channel {}", existing.getChannelId());
        evaluator.evaluateAll(false);
        out.put("ok", Boolean.TRUE);
        out.put("deleted", Boolean.TRUE);
        return out;
    }

    // ------------------------------------------------------------------
    // Channels and settings
    // ------------------------------------------------------------------

    private Map<String, String> channelNames() {
        Map<String, String> out = new LinkedHashMap<>();
        try {
            for (DashboardStatus s : ControllerFactory.getFactory()
                    .createEngineController().getChannelStatusList()) {
                out.put(s.getChannelId(), s.getName());
            }
        } catch (Exception e) {
            LOG.warn("volume monitor could not list channels: {}", e.toString());
        }
        return out;
    }

    /** Deployed channels, for the rule editor's picker. */
    public Map<String, Object> channels() {
        List<String> rows = new ArrayList<>();
        try {
            List<DashboardStatus> statuses = new ArrayList<>(ControllerFactory.getFactory()
                .createEngineController().getChannelStatusList());
            statuses.sort(Comparator.comparing(s -> s.getName() == null ? "" : s.getName()));
            for (DashboardStatus s : statuses) {
                rows.add(String.join("\t",
                    flat(s.getChannelId()),
                    flat(s.getName()),
                    String.valueOf(s.getState())));
            }
        } catch (Exception e) {
            LOG.warn("volume monitor could not list channels: {}", e.toString());
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", Boolean.TRUE);
        out.put("channels", rows);
        return out;
    }

    public Map<String, Object> saveSettings(Map<String, String> incoming) {
        if (incoming == null) {
            incoming = Map.of();
        }
        if (incoming.containsKey("intervalSeconds")) {
            VolumeRuleStore.setEvaluateIntervalSeconds(
                VolumeRule.parseInt(incoming.get("intervalSeconds"), 300));
        }
        if (incoming.containsKey("writeServerEvents")) {
            VolumeRuleStore.setWriteServerEvents(
                Boolean.parseBoolean(incoming.get("writeServerEvents")));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", Boolean.TRUE);
        out.put("intervalSeconds", VolumeRuleStore.evaluateIntervalSeconds());
        out.put("writeServerEvents", VolumeRuleStore.writeServerEvents());
        // The scheduler reads its period once when it starts, so a changed interval only
        // takes effect on restart. Said plainly rather than left for someone to discover.
        out.put("note", "A changed interval takes effect when the server restarts.");
        return out;
    }

    /** The day tokens the UI offers, so both sides cannot drift apart. */
    public static Set<String> dayTokens() {
        return Set.of("MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY",
            "SATURDAY", "SUNDAY");
    }
}
