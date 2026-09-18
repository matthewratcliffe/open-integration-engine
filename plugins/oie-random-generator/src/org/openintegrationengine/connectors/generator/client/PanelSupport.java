/*
 * SPDX-License-Identifier: MPL-2.0
 */
package org.openintegrationengine.connectors.generator.client;

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
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.JTextComponent;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;

/**
 * The small amount of Swing plumbing this connector's panel repeats: a two-column
 * label/field grid, components that mark the channel dirty when they change, and the
 * invalid-field highlighting the Administrator expects.
 *
 * <p>The same helper as the SFTP connector's, deliberately copied rather than shared: each
 * extension is loaded by its own classloader from its own jars, so a common utility would
 * have to become a third extension that both depend on -- more moving parts than a hundred
 * lines of layout code is worth.
 *
 * <p>Plain Swing components rather than Mirth's own subclasses, and a GridBagLayout built in
 * code rather than a NetBeans form: there is no {@code .form} file to keep in step, and this
 * panel has no behaviour the Mirth components would supply.
 */
final class PanelSupport {

    private PanelSupport() {
    }

    /** Marks the channel as having unsaved changes, which is what enables Save. */
    static void markDirty() {
        if (PlatformUI.MIRTH_FRAME != null) {
            PlatformUI.MIRTH_FRAME.setSaveEnabled(true);
        }
    }

    static JPanel grid() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBackground(UIConstants.BACKGROUND_COLOR);
        return panel;
    }

    /** One label/component row, appended at {@code row}. */
    static void addRow(JPanel panel, int row, String label, JComponent component) {
        GridBagConstraints labelConstraints = new GridBagConstraints();
        labelConstraints.gridx = 0;
        labelConstraints.gridy = row;
        labelConstraints.anchor = GridBagConstraints.NORTHEAST;
        labelConstraints.insets = new Insets(3, 6, 3, 6);
        panel.add(new JLabel(label), labelConstraints);

        GridBagConstraints fieldConstraints = new GridBagConstraints();
        fieldConstraints.gridx = 1;
        fieldConstraints.gridy = row;
        fieldConstraints.anchor = GridBagConstraints.NORTHWEST;
        fieldConstraints.fill = GridBagConstraints.HORIZONTAL;
        fieldConstraints.weightx = 1.0;
        fieldConstraints.insets = new Insets(3, 0, 3, 6);
        panel.add(component, fieldConstraints);
    }

    /** A row spanning both columns, for something that brings its own labels. */
    static void addWideRow(JPanel panel, int row, JComponent component) {
        GridBagConstraints constraints = new GridBagConstraints();
        constraints.gridx = 0;
        constraints.gridy = row;
        constraints.gridwidth = 2;
        constraints.anchor = GridBagConstraints.NORTHWEST;
        constraints.fill = GridBagConstraints.HORIZONTAL;
        constraints.weightx = 1.0;
        constraints.insets = new Insets(3, 6, 3, 6);
        panel.add(component, constraints);
    }

    static JTextField textField(int columns, String tooltip) {
        JTextField field = new JTextField(columns);
        field.setToolTipText(tooltip);
        field.getDocument().addDocumentListener(new DirtyListener());
        return field;
    }

    static JTextArea textArea(int rows, int columns, String tooltip) {
        JTextArea area = new JTextArea(rows, columns);
        area.setToolTipText(tooltip);
        area.setLineWrap(false);
        area.getDocument().addDocumentListener(new DirtyListener());
        return area;
    }

    static JScrollPane scroll(JTextArea area) {
        JScrollPane pane = new JScrollPane(area);
        pane.setPreferredSize(new Dimension(620, area.getRows() * 18 + 8));
        return pane;
    }

    static JCheckBox checkBox(String text, String tooltip) {
        JCheckBox box = new JCheckBox(text);
        box.setToolTipText(tooltip);
        box.setBackground(UIConstants.BACKGROUND_COLOR);
        box.addActionListener(e -> markDirty());
        return box;
    }

    static <T> JComboBox<T> comboBox(T[] values, String tooltip) {
        JComboBox<T> box = new JComboBox<T>(values);
        box.setToolTipText(tooltip);
        box.setBackground(UIConstants.COMBO_BOX_BACKGROUND);
        box.addActionListener(e -> markDirty());
        return box;
    }

    /** Highlights a field the Administrator is refusing to save, the same red as everywhere else. */
    static void setValid(JComponent component, boolean valid) {
        Color background = valid ? null : UIConstants.INVALID_COLOR;
        if (component instanceof JTextComponent || component instanceof JComboBox) {
            component.setBackground(background == null ? Color.WHITE : background);
        } else {
            component.setBackground(background);
        }
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
