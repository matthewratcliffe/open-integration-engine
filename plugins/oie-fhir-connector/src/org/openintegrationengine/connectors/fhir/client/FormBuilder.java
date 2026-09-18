/*
 * SPDX-License-Identifier: MPL-2.0
 */
package org.openintegrationengine.connectors.fhir.client;

import com.mirth.connect.client.ui.UIConstants;

import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.border.TitledBorder;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A very small layout helper for the two Swing connector panels.
 *
 * <p>The desktop Administrator is the secondary UI for this connector -- the web
 * administrator's panels are the ones most people will see -- so these panels aim to be
 * complete and legible rather than pixel-matched to the built-in connectors. Plain
 * {@link GridBagLayout} keeps them dependency-free; the bundled connectors use MigLayout,
 * which would tie this extension to another jar on the client classpath.
 */
final class FormBuilder {

    private final JPanel panel;
    private int row;

    FormBuilder(JPanel panel) {
        this.panel = panel;
        panel.setLayout(new GridBagLayout());
        panel.setBackground(UIConstants.BACKGROUND_COLOR);
    }

    /** A titled separator between groups of fields. */
    void section(String title) {
        JPanel separator = new JPanel();
        separator.setBackground(UIConstants.BACKGROUND_COLOR);
        separator.setBorder(new TitledBorder(title));
        separator.setPreferredSize(new Dimension(10, 16));

        GridBagConstraints constraints = base();
        constraints.gridx = 0;
        constraints.gridy = row++;
        constraints.gridwidth = 2;
        constraints.weightx = 1;
        constraints.fill = GridBagConstraints.HORIZONTAL;
        constraints.insets = new Insets(12, 4, 2, 4);
        panel.add(separator, constraints);
    }

    /** A label on the left, a control on the right. */
    void field(String label, JComponent control, String tooltip) {
        JLabel jLabel = new JLabel(label + ":");
        jLabel.setBackground(UIConstants.BACKGROUND_COLOR);
        if (tooltip != null) {
            jLabel.setToolTipText(tooltip);
            control.setToolTipText(tooltip);
        }

        GridBagConstraints labelConstraints = base();
        labelConstraints.gridx = 0;
        labelConstraints.gridy = row;
        labelConstraints.anchor = control instanceof JScrollPane
                ? GridBagConstraints.NORTHEAST
                : GridBagConstraints.EAST;
        panel.add(jLabel, labelConstraints);

        GridBagConstraints controlConstraints = base();
        controlConstraints.gridx = 1;
        controlConstraints.gridy = row++;
        controlConstraints.anchor = GridBagConstraints.WEST;
        controlConstraints.weightx = 1;
        controlConstraints.fill = control instanceof JScrollPane
                ? GridBagConstraints.BOTH
                : GridBagConstraints.HORIZONTAL;
        if (control instanceof JScrollPane) {
            controlConstraints.weighty = 1;
        }
        panel.add(control, controlConstraints);
    }

    private static GridBagConstraints base() {
        GridBagConstraints constraints = new GridBagConstraints();
        constraints.insets = new Insets(2, 4, 2, 4);
        return constraints;
    }

    static JTextField text(int columns) {
        JTextField field = new JTextField();
        field.setColumns(columns);
        return field;
    }

    /** A password field. Same size rules as {@link #text(int)}; the value is never echoed. */
    static JPasswordField password(int columns) {
        JPasswordField field = new JPasswordField(columns);
        // The Administrator is not a browser, but the same reasoning applies: these are
        // connector credentials, not the operator's own login.
        field.putClientProperty("JPasswordField.cutCopyAllowed", Boolean.FALSE);
        return field;
    }

    /** The text of a password field, without the char[] dance at every call site. */
    static String read(JPasswordField field) {
        char[] value = field.getPassword();
        return value == null ? "" : new String(value);
    }

    static JTextArea textArea(int rows) {
        JTextArea area = new JTextArea();
        area.setRows(rows);
        area.setLineWrap(false);
        return area;
    }

    static JScrollPane scroll(JComponent component, int height) {
        JScrollPane scrollPane = new JScrollPane(component);
        scrollPane.setPreferredSize(new Dimension(400, height));
        return scrollPane;
    }

    static JCheckBox checkBox(String label) {
        JCheckBox box = new JCheckBox(label);
        box.setBackground(UIConstants.BACKGROUND_COLOR);
        return box;
    }

    /** Marks a field as failing validation, the same way the built-in panels do. */
    static void invalid(JComponent component) {
        component.setBackground(UIConstants.INVALID_COLOR);
    }

    static void valid(JComponent component) {
        component.setBackground(Color.WHITE);
    }

    /**
     * Renders a multi-valued header or parameter map as one {@code Name: value} per line.
     *
     * <p>A text area rather than the editable table the built-in panels use. The table is
     * several hundred lines of Swing for a field whose primary editor is the web console's
     * key/value grid, and a line-per-header is something an integrator can also paste in
     * from a specification.
     */
    static String renderMap(Map<String, List<String>> map) {
        StringBuilder text = new StringBuilder();
        if (map != null) {
            for (Map.Entry<String, List<String>> entry : map.entrySet()) {
                if (entry.getValue() == null || entry.getValue().isEmpty()) {
                    text.append(entry.getKey()).append(": ").append('\n');
                    continue;
                }
                for (String value : entry.getValue()) {
                    text.append(entry.getKey()).append(": ").append(value == null ? "" : value).append('\n');
                }
            }
        }
        return text.toString();
    }

    /** The inverse of {@link #renderMap}. Lines without a colon are ignored. */
    static Map<String, List<String>> parseMap(String text) {
        Map<String, List<String>> map = new LinkedHashMap<String, List<String>>();
        if (text == null) {
            return map;
        }
        for (String line : text.split("\r\n|\r|\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            int colon = trimmed.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            String name = trimmed.substring(0, colon).trim();
            String value = trimmed.substring(colon + 1).trim();
            List<String> values = map.get(name);
            if (values == null) {
                values = new ArrayList<String>();
                map.put(name, values);
            }
            values.add(value);
        }
        return map;
    }
}
