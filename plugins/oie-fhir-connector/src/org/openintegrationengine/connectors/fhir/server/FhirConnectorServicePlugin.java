/*
 * SPDX-License-Identifier: MPL-2.0
 */
package org.openintegrationengine.connectors.fhir.server;

import com.mirth.connect.model.ExtensionPermission;
import com.mirth.connect.model.converters.ObjectXMLSerializer;
import com.mirth.connect.plugins.ServicePlugin;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.openintegrationengine.connectors.fhir.FhirDispatcherProperties;
import org.openintegrationengine.connectors.fhir.FhirReceiverProperties;

import java.util.Arrays;
import java.util.List;
import java.util.Properties;

/**
 * Registers the connector's properties classes with the engine's XStream allowlist.
 *
 * <p>This is what makes the extension work without a configuration change, and it is the
 * whole reason the extension ships a service plugin at all -- the same shape the core TCP
 * connector uses ("This plugin is required for correct use of the TCP connectors").
 *
 * <p>The engine deserialises channel XML through an XStream configured with
 * {@code NoTypePermission}: nothing is deserialisable unless it has been explicitly
 * allowed. The core connectors are covered by a built-in rule for the
 * {@code com.mirth.connect} package space, and an administrator can extend the list in
 * {@code mirth.properties} with {@code xstream.allowtypes}. A connector living in its own
 * package and relying on that property would be installable but not usable: without the
 * property, every channel using it deserialises into an InvalidChannel described only as
 * "This channel is invalid. Verify all required extensions are loaded correctly." -- with
 * the extension, in fact, loaded perfectly well. That failure is silent, is reported
 * nowhere in the API, and looks exactly like a version mismatch.
 *
 * <p>So the extension allows its own two classes, and nothing else, at server start.
 * Plugins are initialised before the engine deploys channels, so the allowlist is in place
 * by the time any channel XML is read.
 *
 * <p>The alternative -- naming the classes {@code com.mirth.connect.connectors.fhir} so
 * the built-in rule covers them -- is what a good deal of third-party Mirth code does. It
 * is rejected here: a class's package is a claim about who maintains it, it is baked into
 * every channel that ever uses this connector, and three lines of registration is a small
 * price for not making that claim falsely.
 */
public class FhirConnectorServicePlugin implements ServicePlugin {

    public static final String PLUGIN_POINT_NAME = "FHIR Connector Service Plugin";

    private static final Logger LOG = LogManager.getLogger(FhirConnectorServicePlugin.class);

    @Override
    public String getPluginPointName() {
        return PLUGIN_POINT_NAME;
    }

    @Override
    public void init(Properties properties) {
        List<String> allowed = Arrays.asList(
                FhirReceiverProperties.class.getName(),
                FhirDispatcherProperties.class.getName());

        // Exact class names, not a wildcard for the package: an allowlist that grows to
        // cover classes nobody has thought about is not much of an allowlist.
        ObjectXMLSerializer.getInstance().allowTypes(allowed, null, null);
        LOG.info("FHIR connector: allowed {} properties classes for channel deserialisation",
                allowed.size());
    }

    @Override
    public void start() {
    }

    @Override
    public void stop() {
    }

    @Override
    public void update(Properties properties) {
    }

    @Override
    public Properties getDefaultProperties() {
        return new Properties();
    }

    /**
     * No permissions of its own. The plugin has no API and no UI; the connectors it
     * supports are governed by the channel permissions that already exist.
     */
    @Override
    public ExtensionPermission[] getExtensionPermissions() {
        return new ExtensionPermission[0];
    }
}
