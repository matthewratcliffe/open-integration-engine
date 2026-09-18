/*
 * SPDX-License-Identifier: MPL-2.0
 */
package org.openintegrationengine.plugins.volumemonitor;

import com.mirth.connect.server.controllers.ConfigurationController;
import com.mirth.connect.server.controllers.ControllerFactory;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Properties;

/**
 * Persistence for rules and for the monitor's own settings.
 *
 * <p>Stored through {@code ConfigurationController.saveProperty}, which writes to the
 * {@code configuration} table. That matters in this deployment specifically: the image
 * resets {@code conf/} and {@code extensions/} from pristine copies on every boot, so
 * anything written to a file inside the extension directory would not survive a restart.
 *
 * <p>One property per rule, keyed {@code rule.<id>}, rather than one property holding all
 * of them. Deleting a rule is then {@code removeProperty} instead of a read-modify-write
 * of a single blob, so two administrators editing different rules at the same time cannot
 * silently drop each other's work.
 */
public final class VolumeRuleStore {

    static final String GROUP = "Volume Monitor";

    private static final String RULE_PREFIX = "rule.";
    private static final String K_INTERVAL = "evaluateIntervalSeconds";
    private static final String K_EVENTS = "writeServerEvents";

    private static final Logger LOG = LogManager.getLogger(VolumeRuleStore.class);

    private VolumeRuleStore() {
    }

    private static ConfigurationController config() {
        return ControllerFactory.getFactory().createConfigurationController();
    }

    private static Properties stored() {
        Properties p = config().getPropertiesForGroup(GROUP);
        return p == null ? new Properties() : p;
    }

    /** Every rule, ordered by channel then window so the UI list is stable. */
    public static List<VolumeRule> load() {
        Properties p = stored();
        List<VolumeRule> out = new ArrayList<>();
        for (String key : p.stringPropertyNames()) {
            if (!key.startsWith(RULE_PREFIX)) {
                continue;
            }
            VolumeRule r = VolumeRule.parse(p.getProperty(key));
            if (r == null) {
                // Dropped rather than fatal: one unreadable row must not take out
                // monitoring for every other channel on the instance.
                LOG.warn("volume monitor: ignoring unreadable rule '{}'", key);
                continue;
            }
            out.add(r);
        }
        out.sort(Comparator.comparing(VolumeRule::getChannelId)
            .thenComparing(VolumeRule::windowMinutes)
            .thenComparing(VolumeRule::getId));
        return out;
    }

    public static VolumeRule find(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        return VolumeRule.parse(stored().getProperty(RULE_PREFIX + id.trim()));
    }

    public static void save(VolumeRule rule) {
        config().saveProperty(GROUP, RULE_PREFIX + rule.getId(), rule.serialise());
    }

    public static void delete(String id) {
        if (id != null && !id.isBlank()) {
            config().removeProperty(GROUP, RULE_PREFIX + id.trim());
        }
    }

    // ------------------------------------------------------------------
    // Monitor settings
    // ------------------------------------------------------------------

    /** How often the evaluator runs. Floored at 60s; the queries are not free. */
    public static int evaluateIntervalSeconds() {
        int v = VolumeRule.parseInt(stored().getProperty(K_INTERVAL, "300"), 300);
        return Math.max(60, v);
    }

    public static void setEvaluateIntervalSeconds(int seconds) {
        config().saveProperty(GROUP, K_INTERVAL, Integer.toString(Math.max(60, seconds)));
    }

    /** Whether a breach also writes a WARNING into the engine's own event log. */
    public static boolean writeServerEvents() {
        return !"false".equalsIgnoreCase(stored().getProperty(K_EVENTS, "true"));
    }

    public static void setWriteServerEvents(boolean write) {
        config().saveProperty(GROUP, K_EVENTS, Boolean.toString(write));
    }
}
