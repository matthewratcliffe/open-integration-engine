/*
 * SPDX-License-Identifier: MPL-2.0
 */
package org.openintegrationengine.connectors.sftp.client;

import com.mirth.connect.model.converters.ObjectXMLSerializer;
import com.mirth.connect.plugins.ClientPlugin;

import java.util.Collections;
import java.util.List;

/**
 * The Administrator's half of
 * {@link org.openintegrationengine.connectors.sftp.server.SftpSerializerPlugin}.
 *
 * <p>The desktop client deserialises channel XML in its own JVM, with its own XStream and
 * the same allow list, so registering the package on the server alone would leave the
 * Administrator unable to read any channel using these connectors -- it would report the
 * channel as invalid while the engine ran it perfectly well.
 */
public class SftpSerializerClientPlugin extends ClientPlugin {

    public static final String PLUGIN_POINT_NAME = "SFTP Connector";

    private static final String ALLOWED_PACKAGE = "org.openintegrationengine.connectors.sftp.**";

    public SftpSerializerClientPlugin(String name) {
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
