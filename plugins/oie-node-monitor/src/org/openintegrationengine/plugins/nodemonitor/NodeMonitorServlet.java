/*
 * SPDX-License-Identifier: MPL-2.0
 */
package org.openintegrationengine.plugins.nodemonitor;

import com.mirth.connect.client.core.api.MirthApiException;
import com.mirth.connect.server.api.MirthServlet;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.SecurityContext;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Maps {@link NodeMonitorService} onto HTTP.
 *
 * <p>A failed operation answers {@code 200} with {@code ok:false} and a sentence rather
 * than a 500 carrying a serialised Java exception: the console renders what it is given,
 * and a stack trace in a panel is an error message wrapped in something that makes people
 * stop reading. A malformed request is still a {@code 400} -- that one is the caller's to
 * fix.
 */
public class NodeMonitorServlet extends MirthServlet implements NodeMonitorServletInterface {

    public NodeMonitorServlet(@Context HttpServletRequest request,
                              @Context SecurityContext securityContext) {
        super(request, securityContext, NodeMonitorServicePlugin.PLUGIN_POINT_NAME);
    }

    private static NodeMonitorService service() {
        NodeMonitorService s = NodeMonitorServicePlugin.service();
        if (s == null) {
            // Installed, but the ServicePlugin never started: a server fault rather than a
            // bad request, so it gets a status that says so.
            throw new MirthApiException(Response.Status.SERVICE_UNAVAILABLE);
        }
        return s;
    }

    private static Map<String, Object> failure(Throwable t) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", Boolean.FALSE);
        String message = t.getMessage();
        out.put("error", message == null || message.isBlank()
            ? t.getClass().getSimpleName() : message);
        return out;
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
    public Map<String, Object> sampleNow() {
        try {
            return service().sampleNow();
        } catch (Exception e) {
            return failure(e);
        }
    }

    @Override
    public Map<String, Object> forgetNode(String nodeId) {
        if (nodeId == null || nodeId.isBlank()) {
            throw new MirthApiException(Response
                .status(Response.Status.BAD_REQUEST)
                .entity("a nodeId is required")
                .build());
        }
        try {
            return service().forgetNode(nodeId);
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
