/*
 * SPDX-License-Identifier: MPL-2.0
 */
package org.openintegrationengine.plugins.cluster;

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
 * REST surface for the cluster, under {@code /api/cluster}.
 *
 * <p>Served by every node and answering for all of them, because every view is built from
 * the shared database rather than from calls between engines. That is what lets the console
 * stay a same-origin app talking to whichever engine served it -- no CORS, no second login,
 * no peer credential to distribute, and a correct answer even when a node is down.
 *
 * <p>Deployment is deliberately <em>not</em> here. Channels are deployed the way they
 * always were, through the engine's own API and both administrators; this extension
 * observes that and makes the rest of the cluster follow. A second way to deploy would be a
 * second thing to keep in step with the first.
 */
@Path("/cluster")
@Consumes({MediaType.APPLICATION_XML, MediaType.APPLICATION_JSON})
@Produces({MediaType.APPLICATION_XML, MediaType.APPLICATION_JSON})
public interface ClusterServletInterface extends BaseServletInterface {

    /** Nodes, intent, and each node's progress against it, from one read. */
    @GET
    @Path("/status")
    @io.swagger.v3.oas.annotations.Operation(summary =
        "Cluster nodes, channel intent and each node's progress towards it.")
    @MirthOperation(name = "clusterStatus", display = "Get cluster status", auditable = false)
    Map<String, Object> getStatus() throws ClientException;

    /** Message counts per channel, summed across nodes and broken down by node. */
    @GET
    @Path("/dashboard")
    @io.swagger.v3.oas.annotations.Operation(summary =
        "Message statistics per channel, summed across every node.")
    @MirthOperation(name = "clusterDashboard", display = "Get cluster dashboard",
        auditable = false)
    Map<String, Object> getDashboard() throws ClientException;

    /**
     * Queued and unfinished messages owned by engines that are not here.
     *
     * <p>Counts only. Nothing is moved or changed by asking.
     */
    @GET
    @Path("/orphans")
    @io.swagger.v3.oas.annotations.Operation(summary =
        "Queued work belonging to nodes that are no longer present.")
    @MirthOperation(name = "clusterOrphans", display = "List orphaned queues",
        auditable = false)
    Map<String, Object> getOrphans() throws ClientException;

    /** {@code ALL}, {@code SINGLETON} or {@code PINNED:<nodeId>}. */
    @POST
    @Path("/placement")
    @io.swagger.v3.oas.annotations.Operation(summary = "Set where a channel runs.")
    @MirthOperation(name = "clusterSetPlacement", display = "Set channel placement",
        type = Operation.ExecuteType.ASYNC)
    Map<String, Object> setPlacement(
        @Param("request")
        @io.swagger.v3.oas.annotations.Parameter(description =
            "channelId and placement.", required = true)
        Map<String, String> request) throws ClientException;

    /** Makes one state -- STARTED, PAUSED or STOPPED -- the cluster's state for a channel. */
    @POST
    @Path("/state")
    @io.swagger.v3.oas.annotations.Operation(summary =
        "Start, pause or stop a channel on every node.")
    @MirthOperation(name = "clusterSetState", display = "Set cluster channel state",
        type = Operation.ExecuteType.ASYNC)
    Map<String, Object> setState(
        @Param("request")
        @io.swagger.v3.oas.annotations.Parameter(description =
            "channelId and state.", required = true)
        Map<String, String> request) throws ClientException;

    /** Asks every node to deploy the named channels again. */
    @POST
    @Path("/redeploy")
    @io.swagger.v3.oas.annotations.Operation(summary = "Redeploy channels on every node.")
    @MirthOperation(name = "clusterRedeploy", display = "Redeploy across the cluster",
        type = Operation.ExecuteType.ASYNC)
    Map<String, Object> redeploy(
        @Param("request")
        @io.swagger.v3.oas.annotations.Parameter(description =
            "channelIds, comma separated.", required = true)
        Map<String, String> request) throws ClientException;

    /**
     * Takes this node's deployed set as the cluster's intent.
     *
     * <p>The adoption path, for switching an existing engine into a cluster. It overwrites
     * the intent for every channel this node currently runs, so it is an explicit action
     * rather than something the agent does whenever intent looks incomplete.
     */
    @POST
    @Path("/seed")
    @io.swagger.v3.oas.annotations.Operation(summary =
        "Record this node's deployed channels as the cluster's intent.")
    @MirthOperation(name = "clusterSeed", display = "Seed cluster intent",
        type = Operation.ExecuteType.ASYNC)
    Map<String, Object> seed() throws ClientException;

    /** Removes a node that is gone for good from the registry. Its messages are untouched. */
    @POST
    @Path("/nodes/_forget")
    @io.swagger.v3.oas.annotations.Operation(summary = "Forget a node that is gone.")
    @MirthOperation(name = "clusterForgetNode", display = "Forget a cluster node",
        type = Operation.ExecuteType.ASYNC)
    Map<String, Object> forgetNode(
        @Param("nodeId")
        @io.swagger.v3.oas.annotations.Parameter(description = "Server id.", required = true)
        @QueryParam("nodeId") String nodeId) throws ClientException;

    /**
     * Acts on one departed node's work on one channel: {@code adopt} moves it to a live
     * node, {@code discard} marks the queued messages as errored and leaves them in the
     * message store.
     *
     * <p>One channel and one node per call. Both actions write into message history and
     * cannot be undone, so each one is a separate decision taken with that channel's own
     * count in view.
     */
    @POST
    @Path("/orphans/_resolve")
    @io.swagger.v3.oas.annotations.Operation(summary =
        "Adopt or discard one node's queued messages on one channel.")
    @MirthOperation(name = "clusterResolveOrphans", display = "Resolve an orphaned queue",
        type = Operation.ExecuteType.ASYNC)
    Map<String, Object> resolveOrphans(
        @Param("request")
        @io.swagger.v3.oas.annotations.Parameter(description =
            "action (adopt|discard), serverId, channelId and, for adopt, targetNodeId.",
            required = true)
        Map<String, String> request) throws ClientException;

    /** Cluster-wide behaviour: convergence interval, staleness threshold, state handling. */
    @POST
    @Path("/settings")
    @io.swagger.v3.oas.annotations.Operation(summary = "Update cluster settings.")
    @MirthOperation(name = "clusterSetSettings", display = "Set cluster settings",
        type = Operation.ExecuteType.ASYNC)
    Map<String, Object> setSettings(
        @Param("settings")
        @io.swagger.v3.oas.annotations.Parameter(description =
            "convergeIntervalSeconds, nodeStaleSeconds, convergeChannelState.",
            required = true)
        Map<String, String> settings) throws ClientException;
}
