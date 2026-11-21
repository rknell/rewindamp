package dev.rewindamp;

import static dev.rewindamp.DesktopTheme.*;
import java.awt.*;
import java.awt.event.*;
import java.util.List;
import javax.swing.*;

final class DesktopWheels {
    private static void drum(Graphics graphics, int x, int y, int width, int height, boolean vertical) {
        Graphics2D g = (Graphics2D)graphics.create();
        g.translate(x, y);
        int cross = vertical ? height : width;
        g.setPaint(new LinearGradientPaint(0, 0, vertical ? 0 : Math.max(1, cross), vertical ? Math.max(1, cross) : 0,
            new float[]{0, .23f, .50f, .77f, 1},
            new Color[]{new Color(0x3d4d59), new Color(0x99abb5), new Color(0xe0eaed), new Color(0x99abb5), new Color(0x3d4d59)}));
        g.fillRect(0, 0, width, height);
        for (int i = 3; i < cross; i += 5) {
            g.setColor(new Color(0x445662));
            if (vertical) g.drawLine(0, i, width, i); else g.drawLine(i, 0, i, height);
            g.setColor(new Color(0xeff7fa));
            if (vertical) g.drawLine(0, i + 1, width, i + 1); else g.drawLine(i + 1, 0, i + 1, height);
        }
        g.dispose();
    }

    static JPanel destinations(JTabbedPane tabs) {
        JPanel frame = new JPanel(new GridLayout(1, 3, 6, 0)) {
            @Override protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                g.setColor(LCD);
                g.fillRoundRect(6, 5, getWidth() - 12, getHeight() - 10, 10, 10);
                drum(g, 11, 10, getWidth() - 22, getHeight() - 20, false);
            }

            @Override protected void paintChildren(Graphics g) {
                super.paintChildren(g);
                g.setColor(GOLD);
                int x = getWidth() / 2;
                g.fillPolygon(new int[]{x - 5, x + 5, x}, new int[]{4, 4, 10}, 3);
            }
        };
        frame.setBorder(BorderFactory.createCompoundBorder(bevel(false), BorderFactory.createEmptyBorder(13, 13, 11, 13)));
        frame.setPreferredSize(new Dimension(480, 60));
        ButtonGroup group = new ButtonGroup();
        JToggleButton[] buttons = new JToggleButton[tabs.getTabCount()];
        for (int index = 0; index < tabs.getTabCount(); index++) {
            int destination = index;
            JToggleButton button = new JToggleButton(tabs.getTitleAt(index).toUpperCase(java.util.Locale.ROOT)) {
                @Override protected void paintComponent(Graphics g) {
                    g.setColor(isSelected() ? LCD : new Color(0xe2222c35, true));
                    g.fillRect(0, 0, getWidth(), getHeight());
                    if (isSelected() || hasFocus()) {
                        g.setColor(hasFocus() ? GOLD : new Color(0x7e949e));
                        g.drawRect(0, 0, getWidth()-1, getHeight()-1);
                    }
                    super.paintComponent(g);
                }
            };
            button.setFont(MONO.deriveFont(17f));
            button.setContentAreaFilled(false);
            button.setBorderPainted(false);
            button.addActionListener(e -> tabs.setSelectedIndex(destination));
            group.add(button);
            frame.add(button);
            buttons[index] = button;
        }
        Runnable select = () -> {
            int current = tabs.getSelectedIndex();
            for (int index = 0; index < buttons.length; index++) {
                JToggleButton button = buttons[index];
                button.setSelected(current == index);
                button.setForeground(current == index ? GREEN : INK);
                frame.setComponentZOrder(button, Math.floorMod(index - current + 1, buttons.length));
            }
            frame.revalidate();
            frame.repaint();
        };
        select.run();
        tabs.addChangeListener(e -> select.run());
        frame.addMouseWheelListener(e -> tabs.setSelectedIndex(Math.floorMod(
            tabs.getSelectedIndex() + e.getWheelRotation(), tabs.getTabCount())));
        return frame;
    }

    static final class Letters extends JSlider {
        private List<String> entries = List.of();
        private int dragStart, dragValue;
        private boolean dragged;

        Letters() {
            setUI(null);
            setFocusable(true);
            setPreferredSize(new Dimension(60, 150));
            getAccessibleContext().setAccessibleName("Jump to letter");
            addChangeListener(e -> describe());
            addMouseWheelListener(e -> { move(e.getWheelRotation()); e.consume(); });
            MouseAdapter mouse = new MouseAdapter() {
                @Override public void mousePressed(MouseEvent e) {
                    requestFocusInWindow();
                    dragStart = e.getY(); dragValue = getValue(); dragged = false;
                }
                @Override public void mouseDragged(MouseEvent e) {
                    int steps = (dragStart - e.getY()) / 21;
                    dragged |= steps != 0;
                    if (dragged) setValue(dragValue + steps);
                }
                @Override public void mouseReleased(MouseEvent e) {
                    if (!dragged && Math.abs(e.getY() - getHeight() / 2) > 17) move(Integer.signum(e.getY() - getHeight() / 2));
                }
            };
            addMouseListener(mouse);
            addMouseMotionListener(mouse);
            key(KeyEvent.VK_UP, -1);
            key(KeyEvent.VK_DOWN, 1);
            addFocusListener(new FocusAdapter() {
                @Override public void focusGained(FocusEvent e) { repaint(); }
                @Override public void focusLost(FocusEvent e) { repaint(); }
            });
        }

        private void key(int key, int direction) {
            String action = "letter-" + direction;
            getInputMap().put(KeyStroke.getKeyStroke(key, 0), action);
            getActionMap().put(action, new AbstractAction() {
                @Override public void actionPerformed(ActionEvent e) { move(direction); }
            });
        }

        void entries(List<String> values, String current) {
            entries = List.copyOf(values);
            setMaximum(Math.max(0, values.size() - 1));
            setValue(Math.max(0, entries.indexOf(current)));
            describe();
        }

        void follow(String letter) {
            int index = entries.indexOf(letter);
            if (index >= 0) setValue(index);
        }

        String letter() { return entries.isEmpty() ? "" : entries.get(getValue()); }

        private void move(int steps) { setValue(getValue() + steps); }

        private void describe() {
            getAccessibleContext().setAccessibleDescription(letter());
            setToolTipText("Jump to letter · " + letter());
            repaint();
        }

        @Override protected void paintComponent(Graphics g) {
            int w = getWidth(), h = getHeight(), center = h / 2;
            g.setColor(SHELL); g.fillRect(0, 0, w, h);
            g.setColor(LCD); g.fillRoundRect(4, 4, w - 8, h - 8, 12, 12);
            drum(g, 9, 9, w - 18, h - 18, true);
            g.setColor(new Color(0xdd172129, true)); g.fillRect(19, 9, w - 38, h - 18);
            for (int i = 0; i < entries.size(); i++) {
                int offset = (i - getValue()) * 25;
                if (offset == 0 || center + offset < 15 || center + offset > h - 15) continue;
                text(g, entries.get(i), w / 2, center + offset, 14f, MUTED);
            }
            g.setColor(LCD); g.fillRoundRect(5, center - 18, w - 10, 36, 6, 6);
            g.setColor(hasFocus() ? GOLD : new Color(0x7e949e));
            g.drawRoundRect(5, center - 18, w - 11, 35, 6, 6);
            g.setColor(GOLD); g.fillPolygon(new int[]{2, 9, 2}, new int[]{center - 5, center, center + 5}, 3);
            text(g, letter(), w / 2, center, 23f, GREEN);
        }

        private void text(Graphics g, String value, int x, int y, float size, Color color) {
            g.setFont(MONO.deriveFont(size));
            var metrics = g.getFontMetrics();
            g.setColor(color);
            g.drawString(value, x - metrics.stringWidth(value) / 2, y + (metrics.getAscent() - metrics.getDescent()) / 2);
        }
    }
}
