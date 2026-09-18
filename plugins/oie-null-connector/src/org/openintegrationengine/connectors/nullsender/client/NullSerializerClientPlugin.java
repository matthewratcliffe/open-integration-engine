/*
 * SPDX-License-Identifier: MPL-2.0
 */
package org.openintegrationengine.connectors.nullsender.client;

import com.mirth.connect.model.converters.ObjectXMLSerializer;
import com.mirth.connect.plugins.ClientPlugin;

import java.util.Collections;
import java.util.List;

/**
 * The Administrator's half of
 * {@link org.openintegrationengine.connectors.nullsender.server.NullConnectorServicePlugin}.
 *
 * <p>The desktop client deserialises channel XML in its own JVM, with its own XStream and
 * the same allow list, so registering the package on the server alone would leave the
 * Administrator unable to read any channel using this connector -- it would report the
 * channel as invalid while the engine ran it perfectly well.
 */
public class NullSerializerClientPlugin extends ClientPlugin {

    public static final String PLUGIN_POINT_NAME = "Null Connector";

    private static final String ALLOWED_PACKAGE = "org.openintegrationengine.connectors.nullsender.**";

    public NullSerializerClientPlugin(String name) {
        super(name);
        register();
    }

    /** Also on start, for an extension enabled without restarting the client. */
    @Override
    public void start() {
        register();
    }

    private void register() {
        ObjectXMLSerializer.getInstance().allowTypes(Collections.<String>emptyList(),
                List.of(ALLOWED_PACKAGE), Collections.<String>emptyList());
    }

    @Override
    public void stop() {
    }

    @Override
    public void reset() {
    }

    @Override
    public String getPluginPointName() {
        return PLUGIN_POINT_NAME;
    }
}
