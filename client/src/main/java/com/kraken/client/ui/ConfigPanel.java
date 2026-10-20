package com.kraken.client.ui;

import com.kraken.api.eventbus.EventBus;
import com.kraken.api.eventbus.Subscriber;
import com.kraken.client.events.ConfigChanged;
import com.kraken.client.plugins.config.Config;
import com.kraken.client.plugins.config.ConfigDescriptor;
import com.kraken.client.plugins.config.ConfigGroup;
import com.kraken.client.plugins.config.ConfigItem;
import com.kraken.client.plugins.config.ConfigItemDescriptor;
import com.kraken.client.plugins.config.ConfigManager;
import com.kraken.client.plugins.config.ConfigObject;
import com.kraken.client.plugins.config.ConfigSectionDescriptor;
import com.kraken.client.plugins.config.Range;
import org.kordamp.ikonli.materialdesign2.MaterialDesignA;
import org.kordamp.ikonli.materialdesign2.MaterialDesignC;
import org.kordamp.ikonli.materialdesign2.MaterialDesignR;
import org.kordamp.ikonli.materialdesign2.MaterialDesignT;
import org.kordamp.ikonli.swing.FontIcon;

import java.awt.*;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.text.ParseException;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javax.swing.*;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.border.MatteBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.DefaultFormatterFactory;
import javax.swing.text.JTextComponent;

import static com.formdev.flatlaf.FlatClientProperties.*;

/**
 * A settings page built from a {@link Config}'s {@link ConfigDescriptor}: items and collapsible sections in position
 * order, one control per item by its type, and a Reset button.
 *
 * <table>
 *   <caption>Controls</caption>
 *   <tr><td>{@code boolean}</td><td>switch</td></tr>
 *   <tr><td>{@code int}, {@code long}, {@code float}, {@code double}</td><td>spinner, bounded by {@code @Range},
 *       suffixed by {@code @Units}</td></tr>
 *   <tr><td>{@code String}</td><td>text area (a password field if {@code secret}), saved after a pause in typing or
 *       when it loses focus</td></tr>
 *   <tr><td>enum</td><td>drop-down</td></tr>
 *   <tr><td>{@code Set} of an enum</td><td>a checkbox per constant</td></tr>
 *   <tr><td>{@code Color}</td><td>swatch opening a colour picker, with transparency if {@code @Alpha}</td></tr>
 *   <tr><td>{@code Dimension}</td><td>two spinners</td></tr>
 * </table>
 *
 * <p>An item with a {@code warning} asks before its value changes. Hidden items aren't shown, and Reset leaves them
 * alone (plugins keep their own state in them). Changes made elsewhere (a plugin's setter) show up while the page is
 * open. EDT only.
 */
public final class ConfigPanel extends JPanel {

    private static final Icon EXPANDED = FontIcon.of(MaterialDesignC.CHEVRON_DOWN, 14, Theme.LIGHT);
    private static final Icon COLLAPSED = FontIcon.of(MaterialDesignC.CHEVRON_RIGHT, 14, Theme.LIGHT);
    private static final int TEXT_SAVE_DELAY_MS = 600;

    /**
     * Which sections are open, by group and section key, kept while Kraken runs. EDT only.
     */
    private static final Map<String, Boolean> OPEN_SECTIONS = new HashMap<>();

    private final ConfigManager configManager;
    private final EventBus eventBus;
    private final Config config;
    private final Map<String, Method> getters = new HashMap<>();
    private final ConfigDescriptor descriptor;
    private final String group;
    private final String title;
    private final Map<String, Runnable> loaders = new LinkedHashMap<>();
    private Subscriber subscriber;
    private boolean applying;
    private boolean loading;

    /**
     * Builds the page.
     *
     * @param configManager where the settings are stored
     * @param eventBus      where changes made elsewhere are heard of
     * @param config        the config to edit, from {@link ConfigManager#getConfig}
     * @param title         the page's title
     * @param onBack        what the back arrow does, or null for a page without one
     */
    public ConfigPanel(ConfigManager configManager, EventBus eventBus, Config config, String title, Runnable onBack) {
        super(new BorderLayout());
        this.configManager = configManager;
        this.eventBus = eventBus;
        this.config = config;
        this.descriptor = configManager.getConfigDescriptor(config);
        for (Class<?> type : config.getClass().getInterfaces()) {
            if (type.getAnnotation(ConfigGroup.class) != null) {
                for (Method method : type.getMethods()) {
                    ConfigItem item = method.getAnnotation(ConfigItem.class);
                    if (item != null && method.getParameterCount() == 0) {
                        // A plugin's config interface may be package-private
                        method.setAccessible(true);
                        getters.put(item.keyName(), method);
                    }
                }
            }
        }
        this.group = descriptor.getGroup().value();
        this.title = title;
        setBorder(new EmptyBorder(12, 12, 12, 12));
        add(header(onBack), BorderLayout.NORTH);
        add(ScrollablePanel.scroll(body()), BorderLayout.CENTER);
        add(footer(), BorderLayout.SOUTH);
        load();
    }

    @Override
    public void addNotify() {
        super.addNotify();
        if (subscriber == null) {
            subscriber = eventBus.register(ConfigChanged.class, this::changed, 0);
            load();
        }
    }

    @Override
    public void removeNotify() {
        if (subscriber != null) {
            eventBus.unregister(subscriber);
            subscriber = null;
        }
        super.removeNotify();
    }

    private JComponent header(Runnable onBack) {
        JPanel header = new JPanel(new BorderLayout(6, 0));
        header.setBorder(new EmptyBorder(0, 0, 12, 0));
        if (onBack != null) {
            JButton back = new JButton(FontIcon.of(MaterialDesignA.ARROW_LEFT, 18, Theme.LIGHT));
            back.putClientProperty(BUTTON_TYPE, BUTTON_TYPE_TOOLBAR_BUTTON);
            back.setToolTipText("Back to the plugin list");
            back.setFocusable(false);
            back.addActionListener(e -> onBack.run());
            header.add(back, BorderLayout.WEST);
        }
        JLabel label = new JLabel(title);
        label.putClientProperty(STYLE_CLASS, "h3");
        header.add(label, BorderLayout.CENTER);
        return header;
    }

    private JComponent body() {
        Map<String, JPanel> sectionBodies = new HashMap<>();
        Map<ConfigObject, JComponent> topLevel = new LinkedHashMap<>();
        for (ConfigSectionDescriptor section : descriptor.getSections()) {
            JPanel contents = column();
            sectionBodies.put(section.getKey(), contents);
            topLevel.put(section, section(section, contents));
        }
        for (ConfigItemDescriptor item : descriptor.getItems()) {
            if (item.getItem().hidden()) {
                continue;
            }
            JComponent row = item(item);
            JPanel section = sectionBodies.get(item.getItem().section());
            if (section != null) {
                section.add(row);
            } else {
                topLevel.put(item, row);
            }
        }
        JPanel column = column();
        topLevel.entrySet().stream()
                .sorted(Map.Entry.comparingByKey(Comparator.comparingInt(ConfigObject::getPosition)
                        .thenComparing(ConfigObject::getName)))
                .forEach(entry -> column.add(entry.getValue()));
        if (column.getComponentCount() == 0) {
            JLabel none = new JLabel("No settings");
            none.setForeground(Theme.LIGHT);
            column.add(none);
        }
        return column;
    }

    private JComponent footer() {
        JButton reset = new JButton("Reset", FontIcon.of(MaterialDesignR.RESTORE, 16, Theme.LIGHT));
        reset.setToolTipText("Put every setting on this page back to its default");
        reset.setFocusable(false);
        reset.addActionListener(e -> reset());
        JPanel footer = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        footer.setBorder(new EmptyBorder(12, 0, 0, 0));
        footer.add(reset);
        return footer;
    }

    private JComponent section(ConfigSectionDescriptor section, JPanel contents) {
        String key = group + "." + section.getKey();
        JLabel chevron = new JLabel();
        JLabel name = new JLabel(section.getName());
        name.setFont(name.getFont().deriveFont(Font.BOLD));
        JPanel heading = new JPanel(new BorderLayout(4, 0));
        heading.setBorder(new CompoundBorder(new MatteBorder(0, 0, 1, 0, Theme.MEDIUM), new EmptyBorder(10, 0, 4, 0)));
        heading.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        heading.setToolTipText(tooltip(section.getSection().description()));
        heading.add(chevron, BorderLayout.WEST);
        heading.add(name, BorderLayout.CENTER);
        contents.setBorder(new EmptyBorder(4, 8, 4, 0));

        Runnable show = () -> {
            boolean open = OPEN_SECTIONS.getOrDefault(key, !section.getSection().closedByDefault());
            contents.setVisible(open);
            chevron.setIcon(open ? EXPANDED : COLLAPSED);
            revalidate();
            repaint();
        };
        heading.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (SwingUtilities.isLeftMouseButton(e)) {
                    OPEN_SECTIONS.put(key, !contents.isVisible());
                    show.run();
                }
            }
        });
        show.run();

        JPanel panel = new JPanel(new BorderLayout());
        panel.setAlignmentX(LEFT_ALIGNMENT);
        panel.add(heading, BorderLayout.NORTH);
        panel.add(contents, BorderLayout.CENTER);
        return panel;
    }

    /**
     * One item: its name, and its control beside or below it.
     *
     * @param item the item
     * @return the row
     */
    private JComponent item(ConfigItemDescriptor item) {
        WrapLabel name = new WrapLabel(item.getName());
        name.setToolTipText(tooltip(item.getItem().description()));
        Control control = control(item);
        loaders.put(item.getKey(), control.load());

        JPanel row = new JPanel(new BorderLayout(8, 4));
        row.setBorder(new EmptyBorder(4, 0, 4, 0));
        row.setAlignmentX(LEFT_ALIGNMENT);
        if (control.below()) {
            row.add(name, BorderLayout.NORTH);
            row.add(control.component(), BorderLayout.CENTER);
        } else {
            row.add(name, BorderLayout.CENTER);
            JPanel east = new JPanel(new GridBagLayout());
            east.add(control.component());
            row.add(east, BorderLayout.EAST);
        }
        return new JPanel(new BorderLayout()) {
            {
                setAlignmentX(LEFT_ALIGNMENT);
                add(row);
            }

            @Override
            public Dimension getMaximumSize() {
                return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
            }
        };
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Control control(ConfigItemDescriptor item) {
        Type type = item.getType();
        if (type == boolean.class || type == Boolean.class) {
            return toggle(item);
        }
        if (type == int.class || type == Integer.class || type == long.class || type == Long.class
                || type == double.class || type == Double.class || type == float.class || type == Float.class) {
            return number(item, (Class<?>) type);
        }
        if (type == String.class) {
            return text(item);
        }
        if (type == Color.class) {
            return colour(item);
        }
        if (type == Dimension.class) {
            return dimension(item);
        }
        if (type instanceof Class<?> clazz && clazz.isEnum()) {
            return choice(item, (Class) clazz);
        }
        if (type instanceof ParameterizedType set && set.getRawType() == Set.class
                && set.getActualTypeArguments()[0] instanceof Class<?> element && element.isEnum()) {
            return checkboxes(item, (Class) element);
        }
        JLabel unsupported = new JLabel("Unsupported type");
        unsupported.setForeground(Theme.ERROR);
        unsupported.setToolTipText(type.getTypeName() + " can't be edited here");
        return new Control(unsupported, false, () -> {
        });
    }

    private Control toggle(ConfigItemDescriptor item) {
        JToggleButton toggle = new JToggleButton(FontIcon.of(MaterialDesignT.TOGGLE_SWITCH_OFF_OUTLINE, 24,
                Theme.LIGHT));
        toggle.setSelectedIcon(FontIcon.of(MaterialDesignT.TOGGLE_SWITCH, 24, Theme.ACCENT));
        toggle.putClientProperty(BUTTON_TYPE, BUTTON_TYPE_TOOLBAR_BUTTON);
        toggle.setFocusable(false);
        Runnable load = () -> toggle.setSelected(Boolean.TRUE.equals(value(item)));
        toggle.addActionListener(e -> apply(item, toggle.isSelected(), load));
        return new Control(toggle, false, load);
    }

    private Control number(ConfigItemDescriptor item, Class<?> type) {
        Range range = item.getRange();
        boolean isInt = type == int.class || type == Integer.class;
        boolean isLong = type == long.class || type == Long.class;
        Number initial = value(item) instanceof Number number ? number : 0;
        SpinnerNumberModel model;
        if (isInt) {
            int min = range != null ? range.min() : 0;
            int max = range != null ? range.max() : Integer.MAX_VALUE;
            int value = initial.intValue();
            model = new SpinnerNumberModel(value, Math.min(min, value), Math.max(max, value), 1);
        } else if (isLong) {
            long min = range != null ? range.min() : 0;
            long max = range != null ? range.max() : Long.MAX_VALUE;
            long value = initial.longValue();
            model = new SpinnerNumberModel(Long.valueOf(value), Long.valueOf(Math.min(min, value)),
                    Long.valueOf(Math.max(max, value)), Long.valueOf(1));
        } else {
            double min = range != null ? range.min() : 0;
            double max = range != null ? range.max() : Double.MAX_VALUE;
            double value = initial.doubleValue();
            model = new SpinnerNumberModel(value, Math.min(min, value), Math.max(max, value), 0.1);
        }
        JSpinner spinner = new JSpinner(model);
        String units = item.getUnits() != null ? item.getUnits().value() : "";
        JFormattedTextField field = ((JSpinner.DefaultEditor) spinner.getEditor()).getTextField();
        field.setFormatterFactory(new DefaultFormatterFactory(new UnitFormatter(units, type)));
        field.setColumns(Math.max(4, units.length() + 3));
        Runnable load = () -> {
            if (value(item) instanceof Number number) {
                Number value = convert(number, isInt ? Integer.class : isLong ? Long.class : Double.class);
                widen(model, value);
                spinner.setValue(value);
            }
        };
        spinner.addChangeListener(e -> apply(item, convert((Number) spinner.getValue(), type), load));
        return new Control(spinner, false, load);
    }

    /**
     * Stretches a spinner's bounds to take a value set outside them, as a setter or the settings file can.
     *
     * @param model the spinner's model
     * @param value the value
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void widen(SpinnerNumberModel model, Number value) {
        Comparable comparable = (Comparable) value;
        if (comparable.compareTo(model.getMinimum()) < 0) {
            model.setMinimum(comparable);
        }
        if (comparable.compareTo(model.getMaximum()) > 0) {
            model.setMaximum(comparable);
        }
    }

    private Control text(ConfigItemDescriptor item) {
        JTextComponent field;
        if (item.getItem().secret()) {
            field = new JPasswordField();
        } else {
            JTextArea area = new JTextArea(2, 0);
            area.setLineWrap(true);
            area.setWrapStyleWord(true);
            field = area;
        }
        Runnable load = () -> {
            Object value = value(item);
            String text = value == null ? "" : value.toString();
            if (!text.equals(field.getText())) {
                field.setText(text);
            }
        };
        Runnable save = () -> {
            if (!field.getText().equals(Objects.toString(value(item), ""))) {
                apply(item, field.getText(), load);
            }
        };
        Timer pause = new Timer(TEXT_SAVE_DELAY_MS, e -> save.run());
        pause.setRepeats(false);
        field.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                typed();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                typed();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                typed();
            }

            private void typed() {
                if (!loading) {
                    pause.restart();
                }
            }
        });
        field.addFocusListener(new FocusAdapter() {
            @Override
            public void focusLost(FocusEvent e) {
                pause.stop();
                save.run();
            }
        });
        JComponent shown = field instanceof JTextArea ? new JScrollPane(field) {
            {
                setHorizontalScrollBarPolicy(HORIZONTAL_SCROLLBAR_NEVER);
            }
        } : field;
        return new Control(shown, true, load);
    }

    private <E extends Enum<E>> Control choice(ConfigItemDescriptor item, Class<E> type) {
        JComboBox<E> box = new JComboBox<>(type.getEnumConstants());
        box.setFocusable(false);
        Runnable load = () -> box.setSelectedItem(value(item));
        box.addActionListener(e -> {
            if (box.getSelectedItem() != null) {
                apply(item, box.getSelectedItem(), load);
            }
        });
        return new Control(box, false, load);
    }

    private <E extends Enum<E>> Control checkboxes(ConfigItemDescriptor item, Class<E> type) {
        JPanel boxes = column();
        Map<E, JCheckBox> byConstant = new LinkedHashMap<>();
        Runnable load = () -> {
            Object value = value(item);
            Set<?> selected = value instanceof Set<?> set ? set : Set.of();
            byConstant.forEach((constant, box) -> box.setSelected(selected.contains(constant)));
        };
        for (E constant : type.getEnumConstants()) {
            JCheckBox box = new JCheckBox(constant.toString());
            box.setFocusable(false);
            box.addActionListener(e -> {
                EnumSet<E> selected = EnumSet.noneOf(type);
                byConstant.forEach((c, b) -> {
                    if (b.isSelected()) {
                        selected.add(c);
                    }
                });
                apply(item, selected, load);
            });
            byConstant.put(constant, box);
            boxes.add(box);
        }
        return new Control(boxes, true, load);
    }

    private Control colour(ConfigItemDescriptor item) {
        boolean alpha = item.getAlpha() != null;
        Color[] shown = {Color.WHITE};
        JButton swatch = new JButton(new Swatch(() -> shown[0]));
        swatch.setFocusable(false);
        Runnable load = () -> {
            Color value = (Color) value(item);
            shown[0] = value != null ? value : Color.WHITE;
            swatch.setToolTipText(alpha ? String.format("#%08X", shown[0].getRGB())
                    : String.format("#%06X", shown[0].getRGB() & 0xffffff));
            swatch.repaint();
        };
        swatch.addActionListener(e -> {
            Color picked = JColorChooser.showDialog(this, item.getName(), shown[0], alpha);
            if (picked != null) {
                apply(item, alpha ? picked : new Color(picked.getRGB() & 0xffffff), load);
                // The swatch isn't the picker, so show what was saved (or kept, if the user said no)
                load.run();
            }
        });
        return new Control(swatch, false, load);
    }

    private Control dimension(ConfigItemDescriptor item) {
        JSpinner width = new JSpinner(new SpinnerNumberModel(0, 0, Integer.MAX_VALUE, 1));
        JSpinner height = new JSpinner(new SpinnerNumberModel(0, 0, Integer.MAX_VALUE, 1));
        for (JSpinner spinner : new JSpinner[]{width, height}) {
            ((JSpinner.DefaultEditor) spinner.getEditor()).getTextField().setColumns(4);
        }
        Runnable load = () -> {
            Dimension value = (Dimension) value(item);
            width.setValue(value != null ? Math.max(0, value.width) : 0);
            height.setValue(value != null ? Math.max(0, value.height) : 0);
        };
        Consumer<Object> changed = e -> apply(item, new Dimension((Integer) width.getValue(),
                (Integer) height.getValue()), load);
        width.addChangeListener(changed::accept);
        height.addChangeListener(changed::accept);
        JPanel both = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        both.add(width);
        both.add(new JLabel("×"));
        both.add(height);
        return new Control(both, false, load);
    }

    /**
     * Saves a value the user chose, asking first if the item has a warning.
     *
     * @param item   the item
     * @param value  the new value
     * @param revert puts the control back to the stored value if the user says no
     */
    private void apply(ConfigItemDescriptor item, Object value, Runnable revert) {
        if (loading) {
            return;
        }
        String warning = item.getItem().warning();
        if (!warning.isEmpty() && !Objects.equals(value, value(item))) {
            int answer = JOptionPane.showConfirmDialog(this, warning, "Are you sure?", JOptionPane.YES_NO_OPTION,
                    JOptionPane.WARNING_MESSAGE);
            if (answer != JOptionPane.YES_OPTION) {
                loading(revert);
                return;
            }
        }
        applying = true;
        try {
            configManager.setConfiguration(group, item.getKey(), value);
        } finally {
            applying = false;
        }
    }

    private void reset() {
        int answer = JOptionPane.showConfirmDialog(this, "Put " + title + "'s settings back to their defaults?",
                "Reset settings", JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE);
        if (answer != JOptionPane.OK_OPTION) {
            return;
        }
        applying = true;
        try {
            for (ConfigItemDescriptor item : descriptor.getItems()) {
                if (!item.getItem().hidden()) {
                    configManager.unsetConfiguration(group, item.getKey());
                }
            }
        } finally {
            applying = false;
        }
        load();
    }

    /**
     * Hears of setting changes; reloads the control of one in this group that this page didn't make. Only that control,
     * so typing in progress elsewhere on the page isn't overwritten.
     *
     * @param event the change, on whichever thread made it
     */
    private void changed(ConfigChanged event) {
        if (group.equals(event.getGroup()) && !(applying && SwingUtilities.isEventDispatchThread())) {
            Runnable load = loaders.get(event.getKey());
            if (load != null) {
                SwingUtilities.invokeLater(() -> loading(load));
            }
        }
    }

    /**
     * Shows the stored values in every control.
     */
    private void load() {
        loading(() -> loaders.values().forEach(Runnable::run));
    }

    private void loading(Runnable task) {
        boolean was = loading;
        loading = true;
        try {
            task.run();
        } finally {
            loading = was;
        }
    }

    /**
     * An item's current value, through the config's own getter: the stored value, else the default.
     *
     * @param item the item
     * @return the value as its getter returns it, or null if the getter throws
     */
    private Object value(ConfigItemDescriptor item) {
        Method getter = getters.get(item.getKey());
        if (getter == null) {
            return null;
        }
        try {
            return getter.invoke(config);
        } catch (IllegalAccessException | InvocationTargetException e) {
            return null;
        }
    }

    private static Number convert(Number value, Class<?> type) {
        if (type == int.class || type == Integer.class) {
            return value.intValue();
        }
        if (type == long.class || type == Long.class) {
            return value.longValue();
        }
        if (type == float.class || type == Float.class) {
            return value.floatValue();
        }
        return value.doubleValue();
    }

    private static JPanel column() {
        JPanel column = new JPanel();
        column.setLayout(new BoxLayout(column, BoxLayout.Y_AXIS));
        column.setAlignmentX(LEFT_ALIGNMENT);
        return column;
    }

    private static String tooltip(String description) {
        return description == null || description.isBlank() ? null
                : "<html><div style='width:220px'>" + Theme.escape(description) + "</div></html>";
    }

    /**
     * An item's control.
     *
     * @param component the control
     * @param below     whether it goes under the item's name rather than beside it
     * @param load      shows the stored value in it
     */
    private record Control(JComponent component, boolean below, Runnable load) {
    }

    /**
     * A spinner's text: the number followed by its unit; typing the unit is optional.
     */
    private static final class UnitFormatter extends JFormattedTextField.AbstractFormatter {
        private final String units;
        private final Class<?> type;
        private final DecimalFormat decimals = new DecimalFormat("0.######",
                DecimalFormatSymbols.getInstance(Locale.ROOT));

        UnitFormatter(String units, Class<?> type) {
            this.units = units;
            this.type = type;
        }

        @Override
        public Object stringToValue(String text) throws ParseException {
            String number = text == null ? "" : text.trim();
            String suffix = units.trim();
            if (!suffix.isEmpty() && number.endsWith(suffix)) {
                number = number.substring(0, number.length() - suffix.length()).trim();
            }
            try {
                if (type == int.class || type == Integer.class) {
                    return Integer.valueOf(number);
                }
                if (type == long.class || type == Long.class) {
                    return Long.valueOf(number);
                }
                return Double.valueOf(number);
            } catch (NumberFormatException e) {
                throw new ParseException(text, 0);
            }
        }

        @Override
        public String valueToString(Object value) {
            if (value == null) {
                return "";
            }
            String number = value instanceof Double || value instanceof Float
                    ? decimals.format(((Number) value).doubleValue()) : value.toString();
            return number + units;
        }
    }

    /**
     * A colour swatch, over a checkerboard so transparency shows.
     */
    private static final class Swatch implements Icon {
        private static final int WIDTH = 36;
        private static final int HEIGHT = 16;
        private final Supplier<Color> colour;

        Swatch(Supplier<Color> colour) {
            this.colour = colour;
        }

        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                for (int i = 0; i < WIDTH; i += 4) {
                    for (int j = 0; j < HEIGHT; j += 4) {
                        g2.setColor(((i + j) / 4 % 2 == 0) ? Color.LIGHT_GRAY : Color.WHITE);
                        g2.fillRect(x + i, y + j, 4, 4);
                    }
                }
                g2.setColor(colour.get());
                g2.fillRect(x, y, WIDTH, HEIGHT);
                g2.setColor(Theme.MEDIUM);
                g2.drawRect(x, y, WIDTH - 1, HEIGHT - 1);
            } finally {
                g2.dispose();
            }
        }

        @Override
        public int getIconWidth() {
            return WIDTH;
        }

        @Override
        public int getIconHeight() {
            return HEIGHT;
        }
    }
}
