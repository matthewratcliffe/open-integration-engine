/*
 * SPDX-License-Identifier: MPL-2.0
 */
package org.openintegrationengine.connectors.sftp.server;

import com.mirth.connect.model.ExtensionPermission;
import com.mirth.connect.model.converters.ObjectXMLSerializer;
import com.mirth.connect.plugins.ServicePlugin;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * Makes this connector's properties readable.
 *
 * <p>XStream refuses to instantiate any class outside its allow list, and the engine's
 * list covers {@code com.mirth.connect.**} and nothing else. Without this plugin the
 * engine happily <em>writes</em> a channel using the SFTP connectors and then cannot read
 * it back: {@code PUT /channels/{id}} answers {@code 200}, the channel is stored as
 * Mirth's {@code InvalidChannel}, it never appears on the dashboard, and nothing anywhere
 * says why. That is the failure mode {@code oie-config-push.sh} exists to catch, and it
 * is what any third-party connector hits the first time it is deployed.
 *
 * <p>So the extension registers its own package rather than expecting an operator to set
 * {@code xstream.allowtypes} in {@code mirth.properties} -- which works, is undocumented,
 * and is one more thing to forget on the next environment.
 *
 * <p>The scope is exactly this connector's package. It adds no class the engine could not
 * already be made to load, and it does not widen the list for anything else.
 */
public class SftpSerializerPlugin implements ServicePlugin {

    public static final String PLUGIN_POINT_NAME = "SFTP Connector";

    /** Registered as a wildcard so the enums and the nested settings objects are covered. */
    private static final String ALLOWED_PACKAGE = "org.openintegrationengine.connectors.sftp.**";

    private static final Logger logger = LogManager.getLogger(SftpSerializerPlugin.class);

    /**
     * Runs during server startup, before any channel is deserialised -- channels are read
     * when they deploy, and deploys happen after the plugins have started.
     */
    @Override
    public void init(Properties properties) {
        allowTypes();
    }

    /**
     * Also on start, because a plugin that is stopped and started again (an extension
     * enable/disable, or a reinstall without a restart) must not leave the serializer
     * without the registration.
     */
    @Override
    public void start() {
        allowTypes();
    }

    private void allowTypes() {
        try {
            ObjectXMLSerializer.getInstance().allowTypes(Collections.<String>emptyList(),
                    List.of(ALLOWED_PACKAGE), Collections.<String>emptyList());
            logger.debug("registered {} with the channel serializer", ALLOWED_PACKAGE);
        } catch (Throwable t) {
            // Loud, because every channel using this connector is about to be stored as an
            // invalid channel and nothing else will say so.
            logger.error("Could not register the SFTP connector's classes with the channel"
                    + " serializer. Channels using the SFTP Listener or SFTP Sender will be"
                    + " stored as invalid channels. Set xstream.allowtypes="
                    + ALLOWED_PACKAGE + " in mirth.properties as a workaround.", t);
        }
    }

    @Override
    public void stop() {
        // Nothing to undo: the allowance is a property of the serializer, and revoking it
        // would break reading channels that are still deployed.
    }

    @Override
    public void update(Properties properties) {
    }

    @Override
    public Properties getDefaultProperties() {
        return new Properties();
    }

    /**
     * The connector service is reachable by anyone who may edit a channel, which is the
     * same permission the built-in connectors' test operations use, so no extra permission
     * is declared.
     */
    @Override
    public ExtensionPermission[] getExtensionPermissions() {
        return new ExtensionPermission[] {};
    }

    @Override
    public String getPluginPointName() {
        return PLUGIN_POINT_NAME;
    }

    @Override
    public Map<String, Object> getObjectsForSwaggerExamples() {
        return Map.of();
    }
}
