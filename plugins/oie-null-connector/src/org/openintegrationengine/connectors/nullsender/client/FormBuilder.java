/*
 * SPDX-License-Identifier: MPL-2.0
 */
package org.openintegrationengine.connectors.nullsender.client;

import com.mirth.connect.client.ui.PlatformUI;
import com.mirth.connect.client.ui.UIConstants;

import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.border.TitledBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.JTextComponent;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;

/**
 * The small amount of Swing plumbing the settings panel needs: a two-column label/control
 * grid, controls that mark the channel dirty when they change, and the invalid-field
 * highlighting the Administrator expects.
 *
 * <p>Plain {@link GridBagLayout} and plain Swing components, built in code. There is no
 * NetBeans {@code .form} file to keep in step, and the built-in panels' MigLayout would tie
 * this extension to another jar on the client classpath for a form with five fields on it.
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

    /** A paragraph of explanation spanning both columns. */
    void note(String html) {
        JLabel label = new JLabel(html);
        label.setBackground(UIConstants.BACKGROUND_COLOR);

        GridBagConstraints constraints = base();
        constraints.gridx = 0;
        constraints.gridy = row++;
        constraints.gridwidth = 2;
        constraints.weightx = 1;
        constraints.anchor = GridBagConstraints.WEST;
        constraints.fill = GridBagConstraints.HORIZONTAL;
        constraints.insets = new Insets(2, 8, 6, 8);
        panel.add(label, constraints);
    }

    private static GridBagConstraints base() {
        GridBagConstraints constraints = new GridBagConstraints();
        constraints.insets = new Insets(2, 4, 2, 4);
        return constraints;
    }

    /** Marks the channel as having unsaved changes, which is what enables Save. */
    static void markDirty() {
        if (PlatformUI.MIRTH_FRAME != null) {
            PlatformUI.MIRTH_FRAME.setSaveEnabled(true);
        }
    }

    static JTextField text(int columns) {
        JTextField field = new JTextField();
        field.setColumns(columns);
        field.getDocument().addDocumentListener(new DirtyListener());
        return field;
    }

    static JTextArea textArea(int rows) {
        JTextArea area = new JTextArea();
        area.setRows(rows);
        area.setLineWrap(false);
        area.getDocument().addDocumentListener(new DirtyListener());
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
        box.addActionListener(e -> markDirty());
        return box;
    }

    static <T> JComboBox<T> comboBox(T[] values) {
        JComboBox<T> box = new JComboBox<T>(values);
        box.setBackground(UIConstants.COMBO_BOX_BACKGROUND);
        box.addActionListener(e -> markDirty());
        return box;
    }

    /** Marks a field as failing validation, the same way the built-in panels do. */
    static void invalid(JComponent component) {
        component.setBackground(UIConstants.INVALID_COLOR);
    }

    static void valid(JComponent component) {
        component.setBackground(Color.WHITE);
    }

    static String text(JTextComponent component) {
        String value = component.getText();
        return value == null ? "" : value;
    }

    private static class DirtyListener implements DocumentListener {

        @Override
        public void insertUpdate(DocumentEvent e) {
            markDirty();
        }

        @Override
        public void removeUpdate(DocumentEvent e) {
            markDirty();
        }

        @Override
        public void changedUpdate(DocumentEvent e) {
            markDirty();
        }
    }
}
