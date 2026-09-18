/*
 * SPDX-License-Identifier: MPL-2.0
 */
package org.openintegrationengine.plugins.volumemonitor;

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
 * REST surface for the volume monitor.
 *
 * <p>Registered under {@code /api/volumemonitor}. Everything returns a map, and every
 * list inside one is a list of tab-separated strings -- see the note on
 * {@link VolumeMonitorService} for why that is not just laziness.
 */
@Path("/volumemonitor")
@Consumes({MediaType.APPLICATION_XML, MediaType.APPLICATION_JSON})
@Produces({MediaType.APPLICATION_XML, MediaType.APPLICATION_JSON})
public interface VolumeMonitorServletInterface extends BaseServletInterface {

    /**
     * The state of every rule.
     *
     * <p>{@code evaluate=false} reports the last scheduled pass, which costs nothing. The
     * page uses that on load and {@code evaluate=true} only when someone asks for a fresh
     * check, because each evaluation is a date-ranged count per rule against the message
     * tables and those are the most expensive queries this plugin makes.
     */
    @GET
    @Path("/status")
    @io.swagger.v3.oas.annotations.Operation(summary = "State of every volume rule.")
    @MirthOperation(name = "volumeMonitorStatus", display = "Get volume monitor status",
        auditable = false)
    Map<String, Object> getStatus(
        @Param("evaluate")
        @io.swagger.v3.oas.annotations.Parameter(description =
            "Count now instead of reporting the last scheduled pass.")
        @QueryParam("evaluate") boolean evaluate) throws ClientException;

    /**
     * Fault count only.
     *
     * <p>Exists for the navigation badge, which is drawn on every page load: it must not
     * be the reason the console feels slow, so it never counts anything.
     */
    @GET
    @Path("/summary")
    @io.swagger.v3.oas.annotations.Operation(summary = "Fault count, without evaluating.")
    @MirthOperation(name = "volumeMonitorSummary", display = "Get volume monitor summary",
        auditable = false)
    Map<String, Object> getSummary() throws ClientException;

    @GET
    @Path("/rules")
    @io.swagger.v3.oas.annotations.Operation(summary = "List volume rules.")
    @MirthOperation(name = "volumeMonitorGetRules", display = "List volume rules",
        auditable = false)
    Map<String, Object> getRules() throws ClientException;

    /**
     * Creates a rule, or updates the one named by {@code id}.
     *
     * <p>Takes a map body rather than query parameters because a rule has ten fields and
     * a schedule among them. The body must be XStream-shaped
     * ({@code <map><entry><string>k</string><string>v</string></entry></map>}); a plain
     * JSON object is rejected by the engine's deserialiser with a 500.
     */
    @POST
    @Path("/rules")
    @io.swagger.v3.oas.annotations.Operation(summary = "Create or update a volume rule.")
    @MirthOperation(name = "volumeMonitorSaveRule", display = "Save a volume rule",
        type = Operation.ExecuteType.ASYNC)
    Map<String, Object> saveRule(
        @Param("rule")
        @io.swagger.v3.oas.annotations.Parameter(description =
            "Rule fields. Unsupplied keys are left as they were.", required = true)
        Map<String, String> rule) throws ClientException;

    @POST
    @Path("/rules/_delete")
    @io.swagger.v3.oas.annotations.Operation(summary = "Delete a volume rule.")
    @MirthOperation(name = "volumeMonitorDeleteRule", display = "Delete a volume rule",
        type = Operation.ExecuteType.ASYNC)
    Map<String, Object> deleteRule(
        @Param("id")
        @io.swagger.v3.oas.annotations.Parameter(description = "Rule id.", required = true)
        @QueryParam("id") String id) throws ClientException;

    /** Channels on this server, with their deployed state, for the rule editor. */
    @GET
    @Path("/channels")
    @io.swagger.v3.oas.annotations.Operation(summary = "List channels and their state.")
    @MirthOperation(name = "volumeMonitorChannels", display = "List channels",
        auditable = false)
    Map<String, Object> getChannels() throws ClientException;

    @POST
    @Path("/settings")
    @io.swagger.v3.oas.annotations.Operation(summary = "Update monitor settings.")
    @MirthOperation(name = "volumeMonitorSetSettings", display = "Set volume monitor settings",
        type = Operation.ExecuteType.ASYNC)
    Map<String, Object> setSettings(
        @Param("settings")
        @io.swagger.v3.oas.annotations.Parameter(description =
            "intervalSeconds and writeServerEvents.", required = true)
        Map<String, String> settings) throws ClientException;
}
