package com.kraken.client.ui;

import com.kraken.api.eventbus.EventBus;
import com.kraken.api.eventbus.Subscriber;
import com.kraken.client.events.ConfigButtonClicked;
import com.kraken.client.events.ConfigChanged;
import com.kraken.client.events.ConfigSuggestionsRequested;
import com.kraken.client.plugins.config.Button;
import com.kraken.client.plugins.config.Config;
import com.kraken.client.plugins.config.ConfigDescriptor;
import com.kraken.client.plugins.config.ConfigGroup;
import com.kraken.client.plugins.config.ConfigItem;
import com.kraken.client.plugins.config.ConfigItemDescriptor;
import com.kraken.client.plugins.config.ConfigManager;
import com.kraken.client.plugins.config.ConfigObject;
import com.kraken.client.plugins.config.ConfigSectionDescriptor;
import com.kraken.client.plugins.config.Keybind;
import com.kraken.client.plugins.config.Range;
import com.kraken.client.plugins.config.ShowIf;
import com.kraken.client.plugins.config.Suggestions;
import com.kraken.client.plugins.config.Unsupported;
import org.kordamp.ikonli.materialdesign2.MaterialDesignA;
import org.kordamp.ikonli.materialdesign2.MaterialDesignC;
import org.kordamp.ikonli.materialdesign2.MaterialDesignR;
import org.kordamp.ikonli.materialdesign2.MaterialDesignT;
import org.kordamp.ikonli.swing.FontIcon;

import java.awt.*;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import javax.swing.*;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.border.MatteBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.event.PopupMenuEvent;
import javax.swing.event.PopupMenuListener;
import javax.swing.text.DefaultFormatterFactory;
import javax.swing.text.JTextComponent;

import static com.formdev.flatlaf.FlatClientProperties.*;

/**
 * A settings page built from a {@link Config}'s {@link ConfigDescriptor}: items and collapsible sections in position
 * order, one control per item by its type, and a Reset button. Items and sections with {@link ShowIf} conditions
 * appear and disappear as the values they depend on change ({@link ConfigManager#isShown} decides).
 *
 * <table>
 *   <caption>Controls</caption>
 *   <tr><td>{@code boolean}</td><td>switch</td></tr>
 *   <tr><td>{@code int}, {@code long}, {@code float}, {@code double}</td><td>spinner, bounded by {@code @Range},
 *       suffixed by {@code @Units}</td></tr>
 *   <tr><td>{@code String}</td><td>text area (a password field if {@code secret}), saved after a pause in typing or
 *       when it loses focus; with {@code @Suggestions}, a text field with a drop-down whose entries the plugin
 *       supplies when it opens ({@link ConfigSuggestionsRequested}), saved also when an entry is picked</td></tr>
 *   <tr><td>enum</td><td>drop-down</td></tr>
 *   <tr><td>{@code Set} of an enum</td><td>a checkbox per constant</td></tr>
 *   <tr><td>{@code Color}</td><td>swatch opening a colour picker, with transparency if {@code @Alpha}</td></tr>
 *   <tr><td>{@code Dimension}, {@code Point}</td><td>two spinners, each bounded by {@code @Range}</td></tr>
 *   <tr><td>{@link Button}</td><td>a button with the item's name, which posts {@link ConfigButtonClicked}</td></tr>
 *   <tr><td>{@link Keybind}</td><td>a button that records the keys pressed after clicking it</td></tr>
 * </table>
 *
 * <p>An item with a {@code warning} asks before its value changes. An {@link Unsupported} item is greyed out, its
 * tooltip saying why. Hidden items aren't shown, and Reset leaves them alone (plugins keep their own state in them). Changes made elsewhere (a plugin's setter) show up while the page is
 * open. EDT only.
 */
public final class ConfigPanel extends JPanel {

    private static final Icon EXPANDED = FontIcon.of(MaterialDesignC.CHEVRON_DOWN, 14, Theme.LIGHT);
    private static final Icon COLLAPSED = FontIcon.of(MaterialDesignC.CHEVRON_RIGHT, 14, Theme.LIGHT);
    private static final int TEXT_SAVE_DELAY_MS = 600;

    /**
     * The widest control that goes beside its name; a wider one goes under it, so the name keeps its room in the
     * narrowest sidebar.
     */
    private static final int BESIDE_MAX_WIDTH = 140;

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
    private final Map<ConfigItemDescriptor, JComponent> itemRows = new LinkedHashMap<>();
    private final Map<ConfigSectionDescriptor, JComponent> sectionPanels = new LinkedHashMap<>();
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
            JComponent panel = section(section, contents);
            sectionPanels.put(section, panel);
            topLevel.put(section, panel);
        }
        for (ConfigItemDescriptor item : descriptor.getItems()) {
            if (item.getItem().hidden()) {
                continue;
            }
            JComponent row = item(item);
            itemRows.put(item, row);
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
     * One item: its name, and its control beside it, or below it when the control is a text field, a list or too wide
     * to leave the name room; a button alone, across the row.
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
        if (item.getType() == Button.class) {
            row.add(control.component(), BorderLayout.CENTER);
        } else if (control.below() || control.component().getPreferredSize().width > BESIDE_MAX_WIDTH) {
            row.add(name, BorderLayout.NORTH);
            row.add(control.component(), BorderLayout.CENTER);
        } else {
            row.add(name, BorderLayout.CENTER);
            JPanel east = new JPanel(new GridBagLayout());
            east.add(control.component());
            row.add(east, BorderLayout.EAST);
        }
        if (item.getUnsupported() != null) {
            name.setForeground(Theme.MEDIUM);
            disable(row, unsupportedTooltip(item));
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
            return item.getSuggestions() != null ? suggestions(item) : text(item);
        }
        if (type == Color.class) {
            return colour(item);
        }
        if (type == Dimension.class) {
            return pair(item, "×", Dimension::new,
                    value -> value instanceof Dimension size ? new int[]{size.width, size.height} : null);
        }
        if (type == Point.class) {
            return pair(item, ",", Point::new, value -> value instanceof Point at ? new int[]{at.x, at.y} : null);
        }
        if (type == Button.class) {
            return button(item);
        }
        if (type == Keybind.class) {
            return keybind(item);
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

    /**
     * A text field with a drop-down of the plugin's suggestions, asked for each time the drop-down opens.
     *
     * @param item a {@code String} item with {@link Suggestions}
     * @return the control
     */
    private Control suggestions(ConfigItemDescriptor item) {
        DefaultComboBoxModel<String> model = new DefaultComboBoxModel<>();
        JComboBox<String> box = new JComboBox<>(model);
        box.setEditable(true);
        box.setMaximumRowCount(16);
        box.setPrototypeDisplayValue("Suggestions");
        JTextComponent field = (JTextComponent) box.getEditor().getEditorComponent();
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
        // Picking an entry, Enter and leaving the field all end in an action event
        box.addActionListener(e -> {
            if (loading) {
                return;
            }
            Object picked = box.getSelectedItem();
            if (picked != null && !picked.toString().equals(field.getText())) {
                loading(() -> field.setText(picked.toString()));
            }
            pause.stop();
            save.run();
        });
        boolean[] reopening = {false};
        Consumer<List<String>> show = entries -> {
            loading(() -> {
                String text = field.getText();
                model.removeAllElements();
                model.addAll(entries);
                box.setSelectedItem(text);
                field.setText(text);
            });
            if (box.isPopupVisible()) {
                reopening[0] = true;
                try {
                    // The open list keeps its old size unless it is shown again
                    box.hidePopup();
                    if (!entries.isEmpty()) {
                        box.showPopup();
                    }
                } finally {
                    reopening[0] = false;
                }
            }
        };
        box.addPopupMenuListener(new PopupMenuListener() {
            @Override
            public void popupMenuWillBecomeVisible(PopupMenuEvent e) {
                if (!reopening[0]) {
                    eventBus.post(new ConfigSuggestionsRequested(group, item.getKey(),
                            entries -> SwingUtilities.invokeLater(() -> show.accept(entries))));
                }
            }

            @Override
            public void popupMenuWillBecomeInvisible(PopupMenuEvent e) {
            }

            @Override
            public void popupMenuCanceled(PopupMenuEvent e) {
            }
        });
        return new Control(box, true, load);
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

    /**
     * Two spinners for a value made of two whole numbers, such as a size or a point, each bounded by {@code @Range}.
     *
     * @param item      the item
     * @param separator what goes between the spinners
     * @param make      makes the value from the two numbers
     * @param split     the two numbers of a value, or null for none
     * @return the control
     */
    private Control pair(ConfigItemDescriptor item, String separator, BiFunction<Integer, Integer, Object> make,
                         Function<Object, int[]> split) {
        Range range = item.getRange();
        int min = range != null ? range.min() : 0;
        int max = range != null ? range.max() : Integer.MAX_VALUE;
        JSpinner first = new JSpinner(new SpinnerNumberModel(min, min, max, 1));
        JSpinner second = new JSpinner(new SpinnerNumberModel(min, min, max, 1));
        for (JSpinner spinner : new JSpinner[]{first, second}) {
            // Plain digits: a coordinate reads as 3222, not 3,222
            spinner.setEditor(new JSpinner.NumberEditor(spinner, "0"));
            ((JSpinner.DefaultEditor) spinner.getEditor()).getTextField().setColumns(4);
        }
        Runnable load = () -> {
            int[] value = split.apply(value(item));
            first.setValue(value != null ? Math.max(min, Math.min(max, value[0])) : min);
            second.setValue(value != null ? Math.max(min, Math.min(max, value[1])) : min);
        };
        Runnable changed = () -> apply(item, make.apply((Integer) first.getValue(), (Integer) second.getValue()),
                load);
        first.addChangeListener(e -> changed.run());
        second.addChangeListener(e -> changed.run());
        JPanel both = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        both.add(first);
        both.add(new JLabel(separator));
        both.add(second);
        return new Control(both, false, load);
    }

    /**
     * A button that posts {@link ConfigButtonClicked}, after asking if the item has a warning.
     *
     * @param item a {@link Button} item
     * @return the control
     */
    private Control button(ConfigItemDescriptor item) {
        JButton button = new JButton(item.getName());
        button.setToolTipText(tooltip(item.getItem().description()));
        button.setFocusable(false);
        button.addActionListener(e -> {
            String warning = item.getItem().warning();
            if (!warning.isEmpty() && JOptionPane.showConfirmDialog(this, warning, "Are you sure?",
                    JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.YES_OPTION) {
                return;
            }
            eventBus.post(new ConfigButtonClicked(group, item.getKey()));
        });
        return new Control(button, false, () -> {
        });
    }

    /**
     * A button showing a keybind. Clicked, it waits for keys: the keys pressed together are stored once they are all
     * let go, Backspace or Delete stores {@link Keybind#NOT_SET}, and Escape or clicking elsewhere keeps the old one.
     *
     * @param item a {@link Keybind} item
     * @return the control
     */
    private Control keybind(ConfigItemDescriptor item) {
        JButton button = new JButton();
        button.setFocusable(true);
        Keybind[] pending = {null};
        boolean[] capturing = {false};
        Runnable load = () -> {
            capturing[0] = false;
            pending[0] = null;
            button.setText(value(item) instanceof Keybind keybind ? keybind.toString() : Keybind.NOT_SET.toString());
        };
        button.addActionListener(e -> {
            capturing[0] = true;
            pending[0] = null;
            button.setText("Press a key...");
            button.requestFocusInWindow();
        });
        button.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (!capturing[0]) {
                    return;
                }
                e.consume();
                int code = e.getKeyCode();
                if (code == KeyEvent.VK_ESCAPE) {
                    load.run();
                } else if (code == KeyEvent.VK_BACK_SPACE || code == KeyEvent.VK_DELETE) {
                    capturing[0] = false;
                    apply(item, Keybind.NOT_SET, load);
                    load.run();
                } else {
                    pending[0] = Keybind.of(e);
                    button.setText(pending[0].toString());
                }
            }

            @Override
            public void keyReleased(KeyEvent e) {
                if (capturing[0] && pending[0] != null && (e.getModifiersEx() & Keybind.MODIFIER_MASK) == 0) {
                    e.consume();
                    Keybind chosen = pending[0];
                    capturing[0] = false;
                    apply(item, chosen, load);
                    load.run();
                }
            }
        });
        button.addFocusListener(new FocusAdapter() {
            @Override
            public void focusLost(FocusEvent e) {
                if (capturing[0]) {
                    load.run();
                }
            }
        });
        return new Control(button, false, load);
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
        updateShown();
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
     * Hears of setting changes in this group that this page didn't make: reloads the changed setting's control (only
     * that control, so typing in progress elsewhere on the page isn't overwritten), then shows and hides the items and
     * sections whose conditions changed. The page's own changes update it as they are made.
     *
     * @param event the change, on whichever thread made it
     */
    private void changed(ConfigChanged event) {
        if (!group.equals(event.getGroup()) || applying && SwingUtilities.isEventDispatchThread()) {
            return;
        }
        Runnable load = loaders.get(event.getKey());
        SwingUtilities.invokeLater(() -> {
            if (load != null) {
                loading(load);
            }
            updateShown();
        });
    }

    /**
     * Shows the stored values in every control, and the items and sections their values call for.
     */
    private void load() {
        loading(() -> loaders.values().forEach(Runnable::run));
        updateShown();
    }

    /**
     * Shows the items and sections whose conditions hold, and hides the rest.
     */
    private void updateShown() {
        boolean changed = false;
        for (Map.Entry<ConfigItemDescriptor, JComponent> row : itemRows.entrySet()) {
            changed |= show(row.getValue(), configManager.isShown(config, descriptor, row.getKey()));
        }
        for (Map.Entry<ConfigSectionDescriptor, JComponent> section : sectionPanels.entrySet()) {
            changed |= show(section.getValue(), configManager.isShown(config, descriptor, section.getKey()));
        }
        if (changed) {
            revalidate();
            repaint();
        }
    }

    /**
     * Shows or hides a row or section.
     *
     * @param component the row or section
     * @param shown     whether to show it
     * @return true if that changed it
     */
    private static boolean show(JComponent component, boolean shown) {
        if (component.isVisible() == shown) {
            return false;
        }
        component.setVisible(shown);
        return true;
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
     * The tooltip of an {@link Unsupported} item: that it is currently unsupported, why, then its description.
     *
     * @param item an item with {@link Unsupported}
     * @return the tooltip's HTML
     */
    static String unsupportedTooltip(ConfigItemDescriptor item) {
        String reason = item.getUnsupported().value();
        String description = item.getItem().description();
        StringBuilder html = new StringBuilder("<html><div style='width:220px'><b>Currently unsupported</b>");
        if (!reason.isBlank()) {
            html.append(": ").append(Theme.escape(reason));
        }
        if (description != null && !description.isBlank()) {
            html.append("<br><br>").append(Theme.escape(description));
        }
        return html.append("</div></html>").toString();
    }

    /**
     * Greys out a row and everything in it, each part showing the tooltip, so the user can't change the setting but
     * can still see its value and read why.
     *
     * @param component the row or one of its parts
     * @param tooltip   the tooltip
     */
    private static void disable(Component component, String tooltip) {
        component.setEnabled(false);
        if (component instanceof JComponent part) {
            part.setToolTipText(tooltip);
        }
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                disable(child, tooltip);
            }
        }
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
