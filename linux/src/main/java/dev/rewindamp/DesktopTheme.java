package dev.rewindamp;

import java.awt.AlphaComposite;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.LinearGradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import javax.swing.*;

final class DesktopTheme {
    static final Color BG = new Color(Skin.BG), SHELL = new Color(Skin.PANEL), LCD = new Color(Skin.LCD),
        INK = new Color(Skin.WHITE), GREEN = new Color(Skin.GREEN), GOLD = new Color(Skin.GOLD), MUTED = new Color(Skin.MUTED);
    private static final Color FACE = new Color(0x283a46);
    private static final Font CONTROL = new Font(Font.SANS_SERIF, Font.BOLD, 12);
    static final Font MONO = new Font(Font.MONOSPACED, Font.PLAIN, 13);

    static void apply() {
        UIManager.put("Panel.background", SHELL);
        UIManager.put("Label.foreground", INK);
        UIManager.put("TabbedPane.background", SHELL);
        UIManager.put("TabbedPane.foreground", INK);
        UIManager.put("TabbedPane.selected", new Color(70, 81, 89));
        UIManager.put("List.background", LCD);
        UIManager.put("List.foreground", INK);
        UIManager.put("List.selectionBackground", new Color(0x192725));
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
        UIManager.put("ComboBox.selectionBackground", new Color(0x192725));
        UIManager.put("ComboBox.selectionForeground", GREEN);
        UIManager.put("ScrollBar.background", SHELL);
        UIManager.put("Slider.background", SHELL);
        UIManager.put("Slider.foreground", MUTED);
        UIManager.put("ScrollBar.thumb", new Color(0x65727e));
        UIManager.put("ScrollBar.track", BG);
        UIManager.put("MenuItem.background", SHELL);
        UIManager.put("MenuItem.foreground", INK);
        UIManager.put("MenuItem.selectionBackground", new Color(0x404158));
        UIManager.put("MenuItem.selectionForeground", INK);
        UIManager.put("PopupMenu.background", SHELL);
        UIManager.put("TextField.border", BorderFactory.createCompoundBorder(bevel(true), BorderFactory.createEmptyBorder(4, 6, 4, 6)));
    }

    static javax.swing.border.Border bevel(boolean inset) {
        Color lower = new Color(inset ? 0x5c6074 : 0x0a0b13);
        Color upper = new Color(inset ? 0x080812 : 0x77798b);
        return BorderFactory.createBevelBorder(inset ? 1 : 0, upper, upper, lower, lower);
    }

    static JButton button(String text, Runnable action) { return metal(text, text, action); }

    static JButton metal(String caption, String name, Runnable action) {
        JButton button = new JButton(caption) {
            @Override protected void paintComponent(Graphics graphics) {
                Graphics2D g = (Graphics2D) graphics.create();
                int w = getWidth(), h = getHeight();
                boolean pressed = getModel().isArmed() && getModel().isPressed();
                float shade = isEnabled() ? pressed ? .72f : 1f : .45f;
                g.setComposite(AlphaComposite.SrcOver.derive(shade));
                g.setPaint(new LinearGradientPaint(0, 0, 0, Math.max(1, h), new float[]{0, .54f, 1},
                    new Color[]{new Color(Skin.METAL_LIGHT), new Color(Skin.METAL_MID), new Color(Skin.METAL_BASE)}));
                g.fillRect(0, 0, w, h);
                g.setColor(new Color(0xf3fcfd));
                g.fillRect(0, 0, w, 2); g.fillRect(0, 0, 2, h);
                g.setColor(new Color(0x546775));
                g.fillRect(0, h - 2, w, 2); g.fillRect(w - 2, 0, 2, h);
                g.setColor(new Color(0x738793));
                g.fillRect(4, 4, w - 8, 1);
                g.dispose();
                super.paintComponent(graphics);
            }
        };
        button.setForeground(FACE);
        button.setFont(CONTROL);
        button.setContentAreaFilled(false);
        button.setBorder(BorderFactory.createEmptyBorder(7, 10, 6, 10));
        button.setToolTipText(name);
        button.getAccessibleContext().setAccessibleName(name);
        button.addActionListener(event -> action.run());
        return button;
    }

    static JButton transport(String symbol, String name, Runnable action) {
        JButton button = metal("", name, action);
        button.setIcon(new Icon() {
            public int getIconWidth() { return 23; }
            public int getIconHeight() { return 19; }
            public void paintIcon(Component c, Graphics g, int x, int y) {
                g.setColor(new Color(0xffedf5f7, true));
                draw(g, x + 1, y + 1);
                g.setColor(new Color(0x445968));
                draw(g, x, y);
            }

            private void draw(Graphics g, int x, int y) {
                switch (symbol) {
                    case "previous" -> {
                        g.fillRect(x + 3, y + 2, 3, 15);
                        triangle(g, x + 18, x + 7, y);
                    }
                    case "next" -> {
                        triangle(g, x + 4, x + 15, y);
                        g.fillRect(x + 16, y + 2, 3, 15);
                    }
                    case "play" -> triangle(g, x + 6, x + 18, y);
                    case "pause" -> {
                        g.fillRect(x + 6, y + 2, 4, 15);
                        g.fillRect(x + 13, y + 2, 4, 15);
                    }
                    case "stop" -> g.fillRect(x + 5, y + 3, 13, 13);
                    case "browse" -> {
                        g.fillPolygon(new int[]{x + 4, x + 11, x + 19}, new int[]{y + 12, y + 1, y + 12}, 3);
                        g.fillRect(x + 4, y + 14, 15, 3);
                    }
                }
            }

            private void triangle(Graphics g, int base, int tip, int y) {
                g.fillPolygon(new int[]{base, tip, base}, new int[]{y + 1, y + 9, y + 17}, 3);
            }
        });
        return button;
    }

    static JButton quiet(String text, Runnable action) {
        JButton button = new JButton(text);
        button.setForeground(GOLD);
        button.setFont(CONTROL);
        button.setContentAreaFilled(false);
        button.setBorder(BorderFactory.createEmptyBorder(5, 7, 5, 7));
        button.addActionListener(event -> action.run());
        return button;
    }

    static JPanel panel(String name, Component contents) {
        JPanel panel = new JPanel(new BorderLayout(0, 4));
        panel.setBorder(BorderFactory.createCompoundBorder(bevel(false), BorderFactory.createEmptyBorder(4, 6, 6, 6)));
        panel.add(new Rail(name), BorderLayout.NORTH);
        panel.add(contents);
        return panel;
    }

    static final class Rail extends JLabel {
        Rail(String name) {
            super(name, CENTER);
            setForeground(INK);
            setFont(CONTROL);
            setPreferredSize(new Dimension(100, 23));
        }

        @Override protected void paintComponent(Graphics g) {
            int gap = (getFontMetrics(getFont()).stringWidth(getText()) + 23) / 2;
            rail(g, 8, getWidth() / 2 - gap);
            rail(g, getWidth() / 2 + gap, getWidth() - 8);
            super.paintComponent(g);
        }

        private void rail(Graphics g, int start, int end) {
            int y = getHeight() / 2 - 3;
            g.setColor(new Color(Skin.RAIL_EDGE)); g.fillRect(start, y, end - start, 6);
            g.setColor(new Color(Skin.RAIL_LIGHT)); g.fillRect(start, y, end - start, 2);
            g.setColor(new Color(Skin.RAIL_MID)); g.fillRect(start, y + 3, end - start, 1);
        }
    }

    static final class Digits extends JButton {
        Digits(Runnable toggle) {
            super("0:00");
            setToolTipText("Toggle elapsed / remaining time");
            setPreferredSize(new Dimension(180, 48));
            setContentAreaFilled(false);
            setBorderPainted(false);
            setForeground(GREEN);
            addActionListener(event -> toggle.run());
        }

        @Override public Dimension getMaximumSize() { return getPreferredSize(); }

        @Override protected void paintComponent(Graphics g) {
            float units = 0;
            for (char value : getText().toCharArray()) units += value == ':' ? .4f : 1.2f;
            int height = Math.max(1, getHeight() - 8);
            int width = Math.max(1, Math.min((int)((getWidth() - 8) / units), (int)(height * .58)));
            int stroke = Math.max(2, width / 10), middle = height / 2, y = 4;
            int x = 8;
            for (char value : getText().toCharArray()) {
                if (value == ':') {
                    g.setColor(GREEN);
                    g.fillRect(x + 2, y + height * 3 / 10, stroke, stroke);
                    g.fillRect(x + 2, y + height * 7 / 10, stroke, stroke);
                    x += (int)(width * .4);
                    continue;
                }
                int mask = Skin.digit(value);
                int right = x + width - stroke;
                int[][] segments = {{x,y,width,stroke}, {right,y,stroke,middle}, {right,y+middle,stroke,height-middle},
                    {x,y+height-stroke,width,stroke}, {x,y+middle,stroke,height-middle}, {x,y,stroke,middle},
                    {x,y+middle-stroke/2,width,stroke}};
                for (int i = 0; i < segments.length; i++) {
                    int[] line = segments[i];
                    g.setColor((mask & (1 << i)) == 0 ? new Color(0x082109) : GREEN);
                    g.fillRect(line[0], line[1], line[2], line[3]);
                }
                x += (int)(width * 1.2);
            }
            if (hasFocus()) { g.setColor(GOLD); g.drawRect(0, 0, getWidth()-1, getHeight()-1); }
        }
    }

    static JSlider slider(int orientation, int min, int max, int value) {
        JSlider slider = new JSlider(orientation, min, max, value);
        slider.setOpaque(false);
        slider.setUI(new javax.swing.plaf.basic.BasicSliderUI(slider) {
            @Override protected Dimension getThumbSize() {
                return new Dimension(slider.getOrientation() == JSlider.VERTICAL ? 15 : 23, 11);
            }

            @Override public void paintTrack(Graphics g) {
                boolean vertical = slider.getOrientation() == JSlider.VERTICAL;
                g.setColor(new Color(0x080912));
                if (vertical) {
                    int x = trackRect.x + trackRect.width / 2;
                    g.fillRect(x - 3, trackRect.y, 6, trackRect.height);
                    g.setColor(GOLD);
                    g.fillRect(x - 1, trackRect.y, 2, trackRect.height);
                } else {
                    int y = trackRect.y + trackRect.height / 2;
                    g.fillRect(trackRect.x, y - 1, trackRect.width, 4);
                    g.setColor(slider.isEnabled() ? GOLD : new Color(0x626275));
                    g.fillRect(trackRect.x, y, Math.max(0, thumbRect.x + thumbRect.width / 2 - trackRect.x), 2);
                }
            }

            @Override public void paintThumb(Graphics g) {
                var r = thumbRect;
                boolean vertical = slider.getOrientation() == JSlider.VERTICAL;
                g.setColor(vertical ? new Color(0xb4c7cf) : GOLD);
                g.fillRect(r.x, r.y, r.width, r.height);
                g.setColor(vertical ? new Color(0xedf8f7) : new Color(0xf4e8a9));
                g.fillRect(r.x, r.y, r.width, 2); g.fillRect(r.x, r.y, 1, r.height);
                g.setColor(vertical ? new Color(0x45596b) : new Color(0x8d7c4e));
                g.fillRect(r.x + 3, r.y + r.height / 2, r.width - 6, 2);
            }
        });
        return slider;
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
        private static final Color UNLIT = new Color(0x0c180c), LOW = new Color(0x45b632), MID = new Color(0x99d94e);
        float[] levels = new float[28];
        Spectrum() { setPreferredSize(new Dimension(180, 26)); setToolTipText("Live post-EQ music spectrum"); }
        @Override protected void paintComponent(Graphics g) {
            g.setColor(LCD);
            g.fillRect(0, 0, getWidth(), getHeight());
            int steps = 12;
            double width = (double)getWidth() / levels.length, height = (double)getHeight() / steps;
            for (int i = 0; i < levels.length; i++) {
                for (int step = 0; step < steps; step++) {
                    g.setColor(step >= levels[i] * steps ? UNLIT : step > 8 ? GOLD : step > 5 ? MID : LOW);
                    g.fillRect((int)(i * width), getHeight() - (int)((step + 1) * height),
                        Math.max(1, (int)width - 1), Math.max(1, (int)height - 1));
                }
            }
        }
    }

    static final Icon COVER = new Icon() {
        public int getIconWidth() { return 35; }
        public int getIconHeight() { return 35; }
        public void paintIcon(Component c, Graphics g, int x, int y) { bolt(g, x, y, 35); }
    };

    private static void bolt(Graphics graphics, int x, int y, int size) {
        Graphics2D g = (Graphics2D)graphics.create();
        g.translate(x, y);
        g.scale(size / 48.0, size / 48.0);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new Color(0x272936)); g.fillRect(0, 0, 48, 48);
        g.setColor(new Color(0xb4f34b));
        g.fillPolygon(new int[]{28, 10, 22, 19, 38, 25}, new int[]{4, 27, 27, 44, 19, 19}, 6);
        g.dispose();
    }

    static final class Artwork extends JComponent {
        static final int SIZE = 80;
        java.awt.Image picture;
        Artwork() { setPreferredSize(new Dimension(SIZE, SIZE)); setToolTipText("Current artwork"); }
        @Override protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D)graphics.create();
            int size = Math.min(getWidth(), getHeight());
            if (picture != null) g.drawImage(picture, 0, 0, size, size, this);
            else bolt(g, 0, 0, size);
            g.dispose();
        }
    }
}
