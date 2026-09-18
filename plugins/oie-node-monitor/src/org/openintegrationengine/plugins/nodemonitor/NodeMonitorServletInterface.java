/*
 * SPDX-License-Identifier: MPL-2.0
 */
package org.openintegrationengine.plugins.nodemonitor;

import com.mirth.connect.client.core.ClientException;
import com.mirth.connect.client.core.Operation;
import com.mirth.connect.client.core.api.BaseServletInterface;
import com.mirth.connect.client.core.api.MirthOperation;
import com.mirth.connect.client.core.api.Param;

import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.MediaType;

import java.util.Map;

/**
 * REST surface for node status, under {@code /api/nodemonitor}.
 *
 * <p>Served by every engine and answering for all of them, because every node writes its
 * own samples to the shared database and this reads them back. No engine calls another, so
 * the view works from any node, needs no credential between nodes, and still shows a node
 * that has stopped answering -- with its last sample and the time it was taken.
 */
@Path("/nodemonitor")
@Consumes({MediaType.APPLICATION_XML, MediaType.APPLICATION_JSON})
@Produces({MediaType.APPLICATION_XML, MediaType.APPLICATION_JSON})
public interface NodeMonitorServletInterface extends BaseServletInterface {

    /**
     * Every node: latest sample, derived rates, disk per volume, and the retained history.
     *
     * <p>One request for the whole page. The view refreshes on a timer, and a page that
     * issues one request per node gets slower as the cluster gets bigger.
     */
    @GET
    @Path("/status")
    @io.swagger.v3.oas.annotations.Operation(summary =
        "Health, resource use and throughput for every engine.")
    @MirthOperation(name = "nodeMonitorStatus", display = "Get node status", auditable = false)
    Map<String, Object> getStatus() throws ClientException;

    /**
     * Samples the engine serving this request, immediately.
     *
     * <p>Only that engine: every other node is on its own timer and nothing here can reach
     * it. Useful after changing the watched paths or thresholds.
     */
    @POST
    @Path("/_sample")
    @io.swagger.v3.oas.annotations.Operation(summary = "Sample this node now.")
    @MirthOperation(name = "nodeMonitorSample", display = "Sample this node",
        type = Operation.ExecuteType.ASYNC)
    Map<String, Object> sampleNow() throws ClientException;

    /** Removes a node's samples and history. Refused while it is still reporting. */
    @POST
    @Path("/nodes/_forget")
    @io.swagger.v3.oas.annotations.Operation(summary = "Forget a node that is gone.")
    @MirthOperation(name = "nodeMonitorForgetNode", display = "Forget a node",
        type = Operation.ExecuteType.ASYNC)
    Map<String, Object> forgetNode(
        @Param("nodeId")
        @io.swagger.v3.oas.annotations.Parameter(description = "Server id.", required = true)
        @QueryParam("nodeId") String nodeId) throws ClientException;

    /** Sampling interval, history length, offline threshold, warning levels, paths. */
    @POST
    @Path("/settings")
    @io.swagger.v3.oas.annotations.Operation(summary = "Update node monitor settings.")
    @MirthOperation(name = "nodeMonitorSetSettings", display = "Set node monitor settings",
        type = Operation.ExecuteType.ASYNC)
    Map<String, Object> setSettings(
        @Param("settings")
        @io.swagger.v3.oas.annotations.Parameter(description =
            "sampleIntervalSeconds, historySamples, offlineAfterSeconds, heapWarnPct, "
                + "diskWarnPct, volumePaths.", required = true)
        Map<String, String> settings) throws ClientException;
}
