/*
 * SPDX-License-Identifier: MPL-2.0
 */
package org.openintegrationengine.plugins.volumemonitor;

import com.mirth.connect.client.core.api.MirthApiException;
import com.mirth.connect.server.api.MirthServlet;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.SecurityContext;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Maps {@link VolumeMonitorService} onto HTTP.
 *
 * <p>A failed operation answers {@code 200} with {@code ok:false} and a readable message
 * rather than a 500 carrying a serialised Java exception. The console renders whatever it
 * is given, and a stack trace in a web page is not an error message -- it is an error
 * message wrapped in something that makes people stop reading.
 */
public class VolumeMonitorServlet extends MirthServlet implements VolumeMonitorServletInterface {

    public VolumeMonitorServlet(@Context HttpServletRequest request,
                                @Context SecurityContext securityContext) {
        super(request, securityContext, VolumeMonitorServicePlugin.PLUGIN_POINT_NAME);
    }

    private static VolumeMonitorService service() {
        VolumeMonitorService s = VolumeMonitorServicePlugin.service();
        if (s == null) {
            // Installed, but the ServicePlugin never started: a server fault, not a bad
            // request, so it gets a status that says so.
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

    private static MirthApiException badRequest(String message) {
        return new MirthApiException(Response
            .status(Response.Status.BAD_REQUEST)
            .entity(message)
            .build());
    }

    @Override
    public Map<String, Object> getStatus(boolean evaluate) {
        try {
            return service().status(evaluate);
        } catch (Exception e) {
            return failure(e);
        }
    }

    @Override
    public Map<String, Object> getSummary() {
        try {
            return service().summary();
        } catch (Exception e) {
            return failure(e);
        }
    }

    @Override
    public Map<String, Object> getRules() {
        try {
            return service().rules();
        } catch (Exception e) {
            return failure(e);
        }
    }

    @Override
    public Map<String, Object> saveRule(Map<String, String> rule) {
        try {
            return service().saveRule(rule);
        } catch (Exception e) {
            return failure(e);
        }
    }

    @Override
    public Map<String, Object> deleteRule(String id) {
        if (id == null || id.isBlank()) {
            throw badRequest("a rule id is required");
        }
        try {
            return service().deleteRule(id);
        } catch (Exception e) {
            return failure(e);
        }
    }

    @Override
    public Map<String, Object> getChannels() {
        try {
            return service().channels();
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
