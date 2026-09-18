/*
 * SPDX-License-Identifier: MPL-2.0
 */
package org.openintegrationengine.plugins.cluster;

import com.mirth.connect.client.core.api.MirthApiException;
import com.mirth.connect.server.api.MirthServlet;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.SecurityContext;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Maps {@link ClusterService} onto HTTP.
 *
 * <p>A failed operation answers {@code 200} with {@code ok:false} and a sentence, rather
 * than a 500 carrying a serialised Java exception: the console renders what it is given,
 * and a stack trace in a panel is an error message wrapped in something that makes people
 * stop reading. A malformed request is still a {@code 400} -- that one is the caller's to
 * fix, and saying so in the status line is how a script finds out.
 */
public class ClusterServlet extends MirthServlet implements ClusterServletInterface {

    public ClusterServlet(@Context HttpServletRequest request,
                          @Context SecurityContext securityContext) {
        super(request, securityContext, ClusterServicePlugin.PLUGIN_POINT_NAME);
    }

    private static ClusterService service() {
        ClusterService s = ClusterServicePlugin.service();
        if (s == null) {
            // Installed, but the ServicePlugin never started: a server fault, not a bad
            // request, so it gets a status that says so.
            throw new MirthApiException(Response.Status.SERVICE_UNAVAILABLE);
        }
        return s;
    }

    private String actor() {
        return "user " + getCurrentUserId();
    }

    private static Map<String, Object> failure(Throwable t) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", Boolean.FALSE);
        String message = t.getMessage();
        out.put("error", message == null || message.isBlank()
            ? t.getClass().getSimpleName() : message);
        return out;
    }

    private static MirthApiException badRequest(String message) {
        return new MirthApiException(Response
            .status(Response.Status.BAD_REQUEST)
            .entity(message)
            .build());
    }

    private static String field(Map<String, String> request, String name) {
        if (request == null) {
            throw badRequest("a request body is required");
        }
        String value = request.get(name);
        return value == null ? "" : value.trim();
    }

    @Override
    public Map<String, Object> getStatus() {
        try {
            return service().status();
        } catch (Exception e) {
            return failure(e);
        }
    }

    @Override
    public Map<String, Object> getDashboard() {
        try {
            return service().dashboard();
        } catch (Exception e) {
            return failure(e);
        }
    }

    @Override
    public Map<String, Object> getOrphans() {
        try {
            return service().orphans();
        } catch (Exception e) {
            return failure(e);
        }
    }

    @Override
    public Map<String, Object> setPlacement(Map<String, String> request) {
        String channelId = field(request, "channelId");
        if (channelId.isEmpty()) {
            throw badRequest("a channelId is required");
        }
        try {
            return service().setPlacement(channelId, field(request, "placement"), actor());
        } catch (Exception e) {
            return failure(e);
        }
    }

    @Override
    public Map<String, Object> setState(Map<String, String> request) {
        String channelId = field(request, "channelId");
        if (channelId.isEmpty()) {
            throw badRequest("a channelId is required");
        }
        try {
            return service().setLifecycle(channelId, field(request, "state"), actor());
        } catch (Exception e) {
            return failure(e);
        }
    }

    @Override
    public Map<String, Object> redeploy(Map<String, String> request) {
        String ids = field(request, "channelIds");
        if (ids.isEmpty()) {
            throw badRequest("channelIds is required");
        }
        List<String> channelIds = new ArrayList<>();
        for (String id : ids.split(",")) {
            if (!id.isBlank()) {
                channelIds.add(id.trim());
            }
        }
        try {
            return service().redeploy(channelIds, actor());
        } catch (Exception e) {
            return failure(e);
        }
    }

    @Override
    public Map<String, Object> seed() {
        try {
            return service().seed(actor());
        } catch (Exception e) {
            return failure(e);
        }
    }

    @Override
    public Map<String, Object> forgetNode(String nodeId) {
        if (nodeId == null || nodeId.isBlank()) {
            throw badRequest("a nodeId is required");
        }
        try {
            return service().forgetNode(nodeId, actor());
        } catch (Exception e) {
            return failure(e);
        }
    }

    @Override
    public Map<String, Object> resolveOrphans(Map<String, String> request) {
        String action = field(request, "action");
        if (action.isEmpty()) {
            throw badRequest("an action is required: adopt or discard");
        }
        try {
            return service().resolveOrphans(action, field(request, "serverId"),
                field(request, "channelId"), field(request, "targetNodeId"), actor());
        } catch (Exception e) {
            return failure(e);
        }
    }

    @Override
    public Map<String, Object> setSettings(Map<String, String> settings) {
        try {
            return service().saveSettings(settings);
        } catch (Exception e) {
            return failure(e);
        }
    }
}
