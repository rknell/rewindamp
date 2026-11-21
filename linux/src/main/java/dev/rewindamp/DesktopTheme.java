package dev.rewindamp;

import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.RenderingHints;
import javax.swing.*;

final class DesktopTheme {
    static final Color SHELL = new Color(35, 41, 47), LCD = new Color(15, 24, 24),
        INK = new Color(231, 236, 231), GREEN = new Color(174, 246, 120),
        GOLD = new Color(239, 195, 103), MUTED = new Color(167, 180, 181);
    static final Font MONO = new Font(Font.MONOSPACED, Font.PLAIN, 13);

    static void apply() {
        UIManager.put("Panel.background", SHELL);
        UIManager.put("Label.foreground", INK);
        UIManager.put("TabbedPane.background", SHELL);
        UIManager.put("TabbedPane.foreground", INK);
        UIManager.put("TabbedPane.selected", new Color(70, 81, 89));
        UIManager.put("List.background", LCD);
        UIManager.put("List.foreground", INK);
        UIManager.put("List.selectionBackground", new Color(52, 74, 62));
        UIManager.put("List.selectionForeground", GREEN);
        UIManager.put("ScrollPane.background", LCD);
        UIManager.put("OptionPane.background", SHELL);
        UIManager.put("OptionPane.messageForeground", INK);
        UIManager.put("TextField.background", LCD);
        UIManager.put("TextField.foreground", INK);
        UIManager.put("TextField.caretForeground", GREEN);
        UIManager.put("PasswordField.background", LCD);
        UIManager.put("PasswordField.foreground", INK);
        UIManager.put("PasswordField.caretForeground", GREEN);
        UIManager.put("CheckBox.foreground", INK);
        UIManager.put("CheckBox.background", SHELL);
        UIManager.put("ComboBox.background", LCD);
        UIManager.put("ComboBox.foreground", INK);
        UIManager.put("ComboBox.selectionBackground", new Color(52, 74, 62));
        UIManager.put("ComboBox.selectionForeground", GREEN);
        UIManager.put("ScrollBar.background", SHELL);
        UIManager.put("Slider.background", SHELL);
        UIManager.put("Slider.foreground", MUTED);
    }

    static JButton button(String text, Runnable action) {
        JButton button = new JButton(text) {
            @Override protected void paintComponent(Graphics graphics) {
                Graphics2D g = (Graphics2D) graphics.create();
                Color top = getModel().isPressed() ? new Color(59, 71, 73) : new Color(87, 97, 103);
                g.setPaint(new GradientPaint(0, 0, top, 0, getHeight(), new Color(43, 50, 57)));
                g.fillRect(0, 0, getWidth(), getHeight());
                g.dispose();
                super.paintComponent(graphics);
            }
        };
        button.setForeground(INK);
        button.setContentAreaFilled(false);
        button.setMargin(new Insets(6, 10, 6, 10));
        button.setFocusable(true);
        button.addActionListener(event -> action.run());
        return button;
    }

    static JPanel row(Component... items) {
        JPanel result = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 6, 4));
        for (Component item : items) result.add(item);
        return result;
    }

    static JPanel column(Component... items) {
        JPanel result = new JPanel();
        result.setLayout(new BoxLayout(result, BoxLayout.Y_AXIS));
        for (Component item : items) result.add(item);
        return result;
    }

    static JLabel lcd(String text, int size) {
        JLabel label = new JLabel(text);
        label.putClientProperty("html.disable", true);
        label.setForeground(GREEN);
        label.setFont(MONO.deriveFont((float)size));
        return label;
    }

    static final class Title extends JLabel {
        private long started, now;

        Title() {
            super("No audio queued");
            setForeground(GREEN);
            setFont(MONO.deriveFont(20f));
            putClientProperty("html.disable", true);
        }

        @Override public void setText(String value) {
            if (value.equals(getText())) return;
            started = now = 0;
            setToolTipText(value);
            super.setText(value);
        }

        void advance(boolean playing) {
            if (!playing) {
                if (now != 0) { started = now = 0; repaint(); }
                return;
            }
            if (getFontMetrics(getFont()).stringWidth(getText()) <= getWidth()) return;
            now = System.nanoTime();
            if (started == 0) started = now;
            repaint();
        }

        @Override public Dimension getMinimumSize() { return new Dimension(0, getPreferredSize().height); }
        @Override public Dimension getPreferredSize() { return new Dimension(280, super.getPreferredSize().height); }

        @Override protected void paintComponent(Graphics graphics) {
            var metrics = graphics.getFontMetrics(getFont());
            int width = metrics.stringWidth(getText());
            if (width <= getWidth() || now == 0) {
                super.paintComponent(graphics);
                return;
            }
            int cycle = width + 54;
            int offset = (int)(Math.max(0, now - started - 850_000_000L) / 20_000_000L % cycle);
            Graphics2D g = (Graphics2D)graphics.create();
            g.setFont(getFont());
            g.setColor(getForeground());
            int baseline = (getHeight() - metrics.getHeight()) / 2 + metrics.getAscent();
            g.drawString(getText(), -offset, baseline);
            g.drawString(getText(), cycle - offset, baseline);
            g.dispose();
        }
    }

    static final class Spectrum extends JComponent {
        float[] levels = new float[28];
        Spectrum() { setPreferredSize(new Dimension(180, 26)); setToolTipText("Live post-EQ music spectrum"); }
        @Override protected void paintComponent(Graphics g) {
            g.setColor(LCD);
            g.fillRect(0, 0, getWidth(), getHeight());
            double width = (double)getWidth() / levels.length;
            for (int i = 0; i < levels.length; i++) {
                int height = (int)(levels[i] * getHeight());
                g.setColor(i < 18 ? GREEN : GOLD);
                g.fillRect((int)(i * width), getHeight() - height, Math.max(1, (int)width - 1), height);
            }
        }
    }

    static final class Artwork extends JComponent {
        java.awt.Image picture;
        Artwork() { setPreferredSize(new Dimension(82, 82)); setToolTipText("Current artwork"); }
        @Override protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D)graphics.create();
            g.setColor(new Color(30, 44, 42));
            g.fillRect(0, 0, getWidth(), getHeight());
            if (picture != null) g.drawImage(picture, 0, 0, getWidth(), getHeight(), this);
            else {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setColor(MUTED);
                g.fillOval(15, 15, 52, 52);
                g.setColor(LCD);
                g.fillOval(33, 33, 16, 16);
            }
            g.dispose();
        }
    }
}
