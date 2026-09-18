/*
 * SPDX-License-Identifier: MPL-2.0
 */
package org.openintegrationengine.plugins.nodemonitor;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * The timer that makes this node visible to the others: sample, store, repeat.
 */
final class NodeMonitorAgent {

    private static final Logger LOG = LogManager.getLogger(NodeMonitorAgent.class);

    private final NodeSampler sampler = new NodeSampler();

    private volatile long lastSampleAt;
    private volatile String lastError = "";

    long getLastSampleAt() {
        return lastSampleAt;
    }

    String getLastError() {
        return lastError;
    }

    /**
     * One sample. Never throws.
     *
     * <p>An exception escaping a scheduled task cancels every future run, so a node that
     * hit one transient database error would stop reporting for ever -- and would then be
     * shown as offline while serving traffic perfectly well. That is the worst possible
     * failure for a monitor to have, so the pass swallows everything and records what went
     * wrong for the view to show.
     */
    void sample() {
        try {
            NodeSample sample = sampler.take();
            NodeMonitorStore.record(sample, NodeMonitorSettings.historySamples());
            lastSampleAt = sample.getTimestamp();
            lastError = "";
        } catch (Throwable t) {
            lastError = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
            LOG.error("node monitor: sample failed", t);
        }
    }
}
