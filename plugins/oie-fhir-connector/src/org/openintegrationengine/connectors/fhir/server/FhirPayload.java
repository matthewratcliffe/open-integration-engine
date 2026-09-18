/*
 * SPDX-License-Identifier: MPL-2.0
 */
package org.openintegrationengine.connectors.fhir.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.openintegrationengine.connectors.fhir.FhirFormat;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import java.io.StringReader;

/**
 * The few facts about a FHIR body this connector needs to read for itself.
 *
 * <p>It is deliberately not a FHIR parser. Nothing here validates a resource, resolves a
 * profile or converts between releases -- that is what HAPI FHIR is for, and pulling in
 * two releases of its structure jars would add well over a hundred megabytes to an
 * extension whose job is to move bytes between a socket and a channel. What the connector
 * genuinely cannot do without is:
 *
 * <ul>
 *   <li>{@code resourceType} and {@code id}, to build the {@code Location} header a create
 *       is required to return (R4/R5 §3.1.0.1.1);
 *   <li>{@code meta.versionId} and {@code meta.lastUpdated}, for {@code ETag} and
 *       {@code Last-Modified} on a read or a create;
 *   <li>{@code Bundle.type}, which is the only thing that distinguishes a transaction from
 *       a batch when both arrive as {@code POST [base]}.
 * </ul>
 *
 * <p>Every method is total: a body that is truncated, in the other serialisation, or not
 * FHIR at all yields an empty result rather than an exception. A facade that 500s because
 * a channel returned something it could not introspect would be worse at its job than one
 * that simply omits an optional header.
 */
public final class FhirPayload {

    /** What could be read out of a body. Any field may be null. */
    public static final class Info {

        private final String resourceType;
        private final String id;
        private final String versionId;
        private final String lastUpdated;
        private final String bundleType;

        Info(String resourceType, String id, String versionId, String lastUpdated, String bundleType) {
            this.resourceType = resourceType;
            this.id = id;
            this.versionId = versionId;
            this.lastUpdated = lastUpdated;
            this.bundleType = bundleType;
        }

        public String getResourceType() {
            return resourceType;
        }

        public String getId() {
            return id;
        }

        public String getVersionId() {
            return versionId;
        }

        public String getLastUpdated() {
            return lastUpdated;
        }

        public String getBundleType() {
            return bundleType;
        }

        /** True when the body looked like a FHIR resource, i.e. it named a resource type. */
        public boolean isResource() {
            return resourceType != null && !resourceType.isEmpty();
        }
    }

    private static final Info EMPTY = new Info(null, null, null, null, null);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private FhirPayload() {
    }

    public static Info empty() {
        return EMPTY;
    }

    /**
     * Reads what it can from a body.
     *
     * @param format the serialisation to try; when null, it is sniffed from the content
     */
    public static Info inspect(String body, FhirFormat format) {
        if (body == null || body.trim().isEmpty()) {
            return EMPTY;
        }
        FhirFormat actual = format != null ? format : FhirFormat.sniff(body);
        if (actual == FhirFormat.XML) {
            return inspectXml(body);
        }
        if (actual == FhirFormat.JSON) {
            return inspectJson(body);
        }
        return EMPTY;
    }

    private static Info inspectJson(String body) {
        try {
            JsonNode root = MAPPER.readTree(body);
            if (root == null || !root.isObject()) {
                return EMPTY;
            }
            JsonNode meta = root.path("meta");
            return new Info(
                    text(root.path("resourceType")),
                    text(root.path("id")),
                    text(meta.path("versionId")),
                    text(meta.path("lastUpdated")),
                    text(root.path("type")));
        } catch (Exception e) {
            return EMPTY;
        }
    }

    private static Info inspectXml(String body) {
        try {
            Document document = parseXmlSafely(body);
            Element root = document.getDocumentElement();
            if (root == null) {
                return EMPTY;
            }
            // In FHIR XML the resource type IS the root element name, and primitives carry
            // their content in a "value" attribute rather than as text.
            String resourceType = localName(root);
            Element meta = child(root, "meta");
            return new Info(
                    resourceType,
                    value(child(root, "id")),
                    meta == null ? null : value(child(meta, "versionId")),
                    meta == null ? null : value(child(meta, "lastUpdated")),
                    value(child(root, "type")));
        } catch (Exception e) {
            return EMPTY;
        }
    }

    /**
     * A DOM parser with entity resolution turned off.
     *
     * <p>The bodies read here arrive from the network. An XML parser left at its defaults
     * will happily follow an external entity to a file on disk or a URL on the internal
     * network, which is the XXE class of vulnerability; there is no legitimate FHIR
     * document that needs a DTD, so the whole mechanism is switched off rather than
     * filtered.
     */
    private static Document parseXmlSafely(String body) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        factory.setNamespaceAware(true);
        DocumentBuilder builder = factory.newDocumentBuilder();
        builder.setEntityResolver(new org.xml.sax.EntityResolver() {
            @Override
            public InputSource resolveEntity(String publicId, String systemId) {
                return new InputSource(new StringReader(""));
            }
        });
        return builder.parse(new InputSource(new StringReader(body)));
    }

    private static String text(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull() || !node.isValueNode()) {
            return null;
        }
        String value = node.asText();
        return value == null || value.isEmpty() ? null : value;
    }

    private static String localName(Node node) {
        String local = node.getLocalName();
        return local != null ? local : node.getNodeName();
    }

    private static Element child(Element parent, String name) {
        if (parent == null) {
            return null;
        }
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node.getNodeType() == Node.ELEMENT_NODE && name.equals(localName(node))) {
                return (Element) node;
            }
        }
        return null;
    }

    private static String value(Element element) {
        if (element == null) {
            return null;
        }
        String value = element.getAttribute("value");
        return value == null || value.isEmpty() ? null : value;
    }
}
