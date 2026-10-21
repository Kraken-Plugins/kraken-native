package com.kraken.client.ui.devtools.view;

import com.kraken.client.ui.Theme;
import org.kordamp.ikonli.Ikon;
import org.kordamp.ikonli.swing.FontIcon;

import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.MatteBorder;

import static com.formdev.flatlaf.FlatClientProperties.BUTTON_TYPE;
import static com.formdev.flatlaf.FlatClientProperties.BUTTON_TYPE_TAB;

/**
 * Several views of the same snapshot behind a row of tabs, e.g. a table and a picture of the same data. Every view
 * receives every snapshot, so switching tabs shows current data at once.
 *
 * <pre>{@code
 * TabbedView.<Snapshot>builder()
 *         .tab("Table", MaterialDesignT.TABLE, tableView)
 *         .tab("Slots", MaterialDesignV.VIEW_GRID_OUTLINE, slotsView)
 *         .build();
 * }</pre>
 *
 * @param <S> the tool's snapshot type
 */
public final class TabbedView<S> implements ToolView<S> {
    private final List<ToolView<S>> views;
    private final JPanel root = new JPanel(new BorderLayout());

    private record Tab<S>(String name, Ikon icon, ToolView<S> view) {
    }

    private TabbedView(Builder<S> builder) {
        this.views = builder.tabs.stream().map(Tab::view).toList();
        CardLayout cards = new CardLayout();
        JPanel content = new JPanel(cards);
        JPanel strip = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        strip.setBorder(new MatteBorder(0, 0, 1, 0, Theme.DARKER));
        ButtonGroup group = new ButtonGroup();
        int selected = Math.max(0, Math.min(builder.selected, builder.tabs.size() - 1));
        for (int i = 0; i < builder.tabs.size(); i++) {
            Tab<S> tab = builder.tabs.get(i);
            String card = Integer.toString(i);
            content.add(tab.view().component(), card);

            JToggleButton button = new JToggleButton(tab.name(), FontIcon.of(tab.icon(), 16, Theme.LIGHT));
            button.setSelectedIcon(FontIcon.of(tab.icon(), 16, Theme.TEXT));
            button.putClientProperty(BUTTON_TYPE, BUTTON_TYPE_TAB);
            button.setBorder(new EmptyBorder(7, 12, 7, 14));
            button.setIconTextGap(6);
            button.setFocusable(false);
            int index = i;
            button.addActionListener(e -> {
                cards.show(content, card);
                if (builder.onSelect != null) {
                    builder.onSelect.accept(index);
                }
            });
            group.add(button);
            strip.add(button);
            if (i == selected) {
                button.setSelected(true);
            }
        }
        cards.show(content, Integer.toString(selected));
        root.add(strip, BorderLayout.NORTH);
        root.add(content, BorderLayout.CENTER);
    }

    /**
     * Starts a tabbed view.
     *
     * @param <S> the tool's snapshot type
     * @return a builder with no tabs
     */
    public static <S> Builder<S> builder() {
        return new Builder<>();
    }

    @Override
    public JComponent component() {
        return root;
    }

    @Override
    public void update(S snapshot) {
        for (ToolView<S> view : views) {
            view.update(snapshot);
        }
    }

    public static final class Builder<S> {
        private final List<Tab<S>> tabs = new ArrayList<>();
        private int selected;
        private IntConsumer onSelect;

        private Builder() {
        }

        /**
         * Adds a tab.
         *
         * @param name the tab's label
         * @param icon the tab's icon
         * @param view what the tab shows
         * @return this builder
         */
        public Builder<S> tab(String name, Ikon icon, ToolView<S> view) {
            tabs.add(new Tab<>(name, icon, view));
            return this;
        }

        /**
         * Picks the tab shown first.
         *
         * @param index the tab's index, in the order added
         * @return this builder
         */
        public Builder<S> selected(int index) {
            this.selected = index;
            return this;
        }

        /**
         * Called on the EDT when the user picks a tab, e.g. to open the same tab next time.
         *
         * @param listener gets the picked tab's index
         * @return this builder
         */
        public Builder<S> onSelect(IntConsumer listener) {
            this.onSelect = listener;
            return this;
        }

        /**
         * Builds the view.
         *
         * @return the view
         */
        public TabbedView<S> build() {
            if (tabs.isEmpty()) {
                throw new IllegalStateException("A tabbed view needs at least one tab");
            }
            return new TabbedView<>(this);
        }
    }
}
