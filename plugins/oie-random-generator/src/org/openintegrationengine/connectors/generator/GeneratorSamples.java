/*
 * SPDX-License-Identifier: MPL-2.0
 */
package org.openintegrationengine.connectors.generator;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The sample template for each {@link MessageType}, read from the shared jar.
 *
 * <p>The samples are files rather than string constants so there is exactly one copy of
 * each: the engine reads them from here when a channel leaves the template blank, the
 * Administrator's panel reads them from here for its Load Sample button, and the web
 * console's panel is given the same text inlined into its plugin.js by build.sh. Editing a
 * sample is editing {@code samples/<TYPE>.hl7} and nothing else.
 *
 * <p>Line endings are normalised to {@code \n} here, not to HL7's {@code \r}, because this
 * text is shown in an editor. The conversion to {@code \r} happens once, on the way out,
 * in {@link org.openintegrationengine.connectors.generator.server.MessageGenerator} -- so a
 * template pasted from a mail client or a Windows editor lands in the same place as one of
 * these.
 */
public final class GeneratorSamples {

    private static final Map<MessageType, String> CACHE = new ConcurrentHashMap<MessageType, String>();

    private GeneratorSamples() {
    }

    /**
     * The sample for a type, or an empty string if the resource is missing -- which can
     * only happen if the jar was built without {@code samples/}, and is not worth failing a
     * deploy over when the connector is perfectly able to run someone's own template.
     */
    public static String forType(MessageType type) {
        if (type == null) {
            return "";
        }
        String cached = CACHE.get(type);
        if (cached != null) {
            return cached;
        }
        String loaded = load(type);
        CACHE.put(type, loaded);
        return loaded;
    }

    private static String load(MessageType type) {
        String resource = "samples/" + type.getSampleResource();
        InputStream in = GeneratorSamples.class.getResourceAsStream(resource);
        if (in == null) {
            return "";
        }
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            return normalise(new String(out.toByteArray(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            return "";
        } finally {
            try {
                in.close();
            } catch (IOException ignored) {
                // Reading a resource out of our own jar; nothing useful to do here.
            }
        }
    }

    /** Every flavour of line ending to {@code \n}, with no trailing blank line. */
    private static String normalise(String text) {
        return text.replace("\r\n", "\n").replace('\r', '\n').replaceAll("\n+$", "");
    }
}
