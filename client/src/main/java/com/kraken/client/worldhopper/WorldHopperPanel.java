package com.kraken.client.worldhopper;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.kraken.api.Client;
import com.kraken.api.GameSession;
import com.kraken.api.World;
import com.kraken.api.WorldType;
import com.kraken.api.eventbus.EventBus;
import com.kraken.client.ProcessAttacher;
import com.kraken.client.plugins.config.ConfigManager;
import com.kraken.client.plugins.config.Keybind;
import com.kraken.client.ui.ConfigPanel;
import com.kraken.client.ui.Theme;
import org.kordamp.ikonli.Ikon;
import org.kordamp.ikonli.materialdesign2.MaterialDesignC;
import org.kordamp.ikonli.materialdesign2.MaterialDesignE;
import org.kordamp.ikonli.materialdesign2.MaterialDesignM;
import org.kordamp.ikonli.materialdesign2.MaterialDesignR;
import org.kordamp.ikonli.swing.FontIcon;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.event.RowSorterEvent;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableRowSorter;
import java.awt.*;
import java.awt.event.HierarchyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static com.formdev.flatlaf.FlatClientProperties.*;

/**
 * The "World Hopper" sidebar page: every world in the world list, with the player's current world, a search over
 * world numbers, activities and regions, region and type filters, and sorting by any column. Clicking a world hops to
 * it, and the arrows by the current world quick-hop like the keys do ({@link WorldHopper}). The cog swaps the page for
 * the {@link WorldHopperConfig} settings until the back arrow is clicked. The filters and the sort are kept in the
 * settings' hidden items.
 *
 * <p>The page exists before the game does: it follows the attached game through {@link ProcessAttacher}'s listeners,
 * reading the current world each poll, and hops through the session's {@link WorldHopper} once it is attached.
 */
@Singleton
public final class WorldHopperPanel extends JPanel {

    private static final String LIST = "list";
    private static final String SETTINGS = "settings";
    private static final String ALL_REGIONS = "All regions";

    /**
     * A list older than this is fetched again when the page is shown.
     */
    private static final Duration STALE = Duration.ofMinutes(1);

    /**
     * How long the hopper's last word stays on the status line once no hop is under way.
     */
    private static final int STATUS_MILLIS = 8000;

    private static final Color CURRENT_WORLD = new Color(66, 227, 17);
    private static final Color MEMBERS_WORLD = new Color(210, 193, 53);
    private static final Color FREE_WORLD = new Color(200, 200, 200);
    private static final Color DANGEROUS_WORLD = new Color(251, 62, 62);
    private static final Color SEASONAL_WORLD = new Color(133, 177, 178);
    private static final Color BETA_WORLD = new Color(79, 145, 255);
    private static final Color PVP_ARENA_WORLD = new Color(144, 179, 255);
    private static final Color QUEST_SPEEDRUNNING_WORLD = new Color(94, 213, 201);
    private static final Color FRESH_START_WORLD = new Color(255, 211, 83);

    private static final int WORLD = 0;
    private static final int PLAYERS = 1;
    private static final int REGION = 2;
    private static final int ACTIVITY = 3;
    private static final String[] COLUMNS = {"World", "Players", "Loc", "Activity"};
    private static final int[] WIDTHS = {60, 58, 40, 120};

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault());

    private final WorldService worldService;
    private final WorldHopperConfig config;
    private final ConfigManager configManager;
    private final EventBus eventBus;
    private final CardLayout cards = new CardLayout();
    private final JPanel listPage = new JPanel(new BorderLayout());
    private ConfigPanel settingsPage;

    private final JLabel current = new JLabel();
    private final JButton previous = toolbarButton(MaterialDesignC.CHEVRON_LEFT, "");
    private final JButton next = toolbarButton(MaterialDesignC.CHEVRON_RIGHT, "");
    private final JTextField search = new JTextField();
    private final JComboBox<String> region = new JComboBox<>();
    private final JComboBox<WorldTypeFilter> type = new JComboBox<>(WorldTypeFilter.values());
    private final WorldTableModel model = new WorldTableModel();
    private final TableRowSorter<WorldTableModel> sorter = new TableRowSorter<>(model);
    private final JTable table = new JTable(model);
    private final JLabel status = new JLabel();
    private final Timer statusTimer = new Timer(STATUS_MILLIS, e -> updateStatus());

    /**
     * Whether the saved filters are being put back, which shouldn't save them again. EDT.
     */
    private boolean restoring;

    /**
     * The session's hopper, once the game is attached.
     */
    private volatile WorldHopper hopper;

    /**
     * The current world and whether a player is logged in, as of the last poll; read on the API poll thread.
     */
    private volatile int currentWorld = -1;
    private volatile boolean loggedIn;

    @Inject
    WorldHopperPanel(WorldService worldService, WorldHopperConfig config, ConfigManager configManager,
                     EventBus eventBus, ProcessAttacher attacher) {
        this.worldService = worldService;
        this.config = config;
        this.configManager = configManager;
        this.eventBus = eventBus;
        setLayout(cards);
        listPage.setBorder(new EmptyBorder(12, 12, 8, 12));
        listPage.add(header(), BorderLayout.NORTH);
        listPage.add(worldTable(), BorderLayout.CENTER);
        status.putClientProperty(STYLE_CLASS, "small");
        status.setForeground(Theme.LIGHT);
        status.setBorder(new EmptyBorder(6, 2, 0, 0));
        listPage.add(status, BorderLayout.SOUTH);
        add(listPage, LIST);

        worldService.addListener(this::worldsChanged);
        attacher.addAttachListener(session -> {
            WorldHopper attached = session.getInstance(WorldHopper.class);
            attached.addStatusListener(this::updateStatus);
            hopper = attached;
            SwingUtilities.invokeLater(this::updateCurrent);
        });
        attacher.addPollListener(this::readCurrentWorld);
        addHierarchyListener(e -> {
            if ((e.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) != 0 && isShowing()) {
                shown();
            }
        });
        restoreFilters();
        updateCurrent();
        updateStatus();
    }

    private JComponent header() {
        JLabel title = new JLabel("World Hopper", FontIcon.of(MaterialDesignE.EARTH, 18, Theme.ACCENT),
                SwingConstants.LEFT);
        title.putClientProperty(STYLE_CLASS, "h3");
        JButton refresh = toolbarButton(MaterialDesignR.REFRESH, "Fetch the world list again");
        refresh.addActionListener(e -> worldService.refresh());
        JButton settings = toolbarButton(MaterialDesignC.COG_OUTLINE, "Settings");
        settings.addActionListener(e -> showSettings());
        Box titleButtons = Box.createHorizontalBox();
        titleButtons.add(refresh);
        titleButtons.add(settings);
        JPanel titleRow = new JPanel(new BorderLayout());
        titleRow.add(title, BorderLayout.CENTER);
        titleRow.add(titleButtons, BorderLayout.EAST);

        previous.addActionListener(e -> quickHop(true));
        next.addActionListener(e -> quickHop(false));
        Box hopButtons = Box.createHorizontalBox();
        hopButtons.add(previous);
        hopButtons.add(next);
        JPanel currentRow = new JPanel(new BorderLayout());
        currentRow.setBorder(new EmptyBorder(8, 0, 0, 0));
        currentRow.add(current, BorderLayout.CENTER);
        currentRow.add(hopButtons, BorderLayout.EAST);

        search.putClientProperty(PLACEHOLDER_TEXT, "Search worlds and activities");
        search.putClientProperty(TEXT_FIELD_LEADING_ICON, FontIcon.of(MaterialDesignM.MAGNIFY, 16, Theme.LIGHT));
        search.putClientProperty(TEXT_FIELD_SHOW_CLEAR_BUTTON, true);
        search.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                applyFilter();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                applyFilter();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                applyFilter();
            }
        });
        JPanel searchRow = new JPanel(new BorderLayout());
        searchRow.setBorder(new EmptyBorder(8, 0, 6, 0));
        searchRow.add(search);

        region.addItem(ALL_REGIONS);
        for (WorldRegion each : WorldRegion.values()) {
            region.addItem(each.getDisplayName());
        }
        region.setToolTipText("Show the worlds of one region");
        region.addActionListener(e -> {
            if (!restoring) {
                config.regionFilter(selectedRegion());
            }
            applyFilter();
        });
        type.setToolTipText("Show one kind of world");
        type.addActionListener(e -> {
            if (!restoring) {
                config.typeFilter((WorldTypeFilter) type.getSelectedItem());
            }
            applyFilter();
        });
        JPanel filterRow = new JPanel(new GridLayout(1, 2, 6, 0));
        filterRow.setBorder(new EmptyBorder(0, 0, 8, 0));
        filterRow.add(region);
        filterRow.add(type);

        JPanel north = new JPanel();
        north.setLayout(new BoxLayout(north, BoxLayout.Y_AXIS));
        for (JComponent c : new JComponent[]{titleRow, currentRow, searchRow, filterRow}) {
            c.setAlignmentX(LEFT_ALIGNMENT);
            north.add(c);
        }
        return north;
    }

    private JComponent worldTable() {
        table.setRowSorter(sorter);
        table.setRowHeight(22);
        table.setFillsViewportHeight(true);
        table.setShowVerticalLines(false);
        table.setShowHorizontalLines(false);
        table.setFocusable(false);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.getTableHeader().setReorderingAllowed(false);
        table.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        table.setDefaultRenderer(Object.class, new WorldCellRenderer());
        table.setDefaultRenderer(Integer.class, new WorldCellRenderer());
        for (int i = 0; i < COLUMNS.length; i++) {
            table.getColumnModel().getColumn(i).setPreferredWidth(WIDTHS[i]);
            if (i != ACTIVITY) {
                table.getColumnModel().getColumn(i).setMaxWidth(WIDTHS[i] + 20);
            }
        }
        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (!SwingUtilities.isLeftMouseButton(e)) {
                    return;
                }
                int row = table.rowAtPoint(e.getPoint());
                if (row >= 0) {
                    hop(model.world(table.convertRowIndexToModel(row)));
                }
            }
        });
        sorter.addRowSorterListener(e -> {
            if (e.getType() == RowSorterEvent.Type.SORT_ORDER_CHANGED && !restoring) {
                saveSort();
            }
        });
        JScrollPane scroll = new JScrollPane(table);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        return scroll;
    }

    /**
     * Fetches the list if it's missing or stale, as the page is shown. EDT.
     */
    private void shown() {
        worldService.start();
        Instant updated = worldService.getUpdatedAt();
        if (updated == null || Duration.between(updated, Instant.now()).compareTo(STALE) > 0) {
            worldService.refresh();
        }
        updateStatus();
    }

    /**
     * Swaps the list for the World Hopper's settings page.
     */
    private void showSettings() {
        closeSettings();
        settingsPage = new ConfigPanel(configManager, eventBus, config, "World Hopper", this::showList);
        add(settingsPage, SETTINGS);
        cards.show(this, SETTINGS);
    }

    private void showList() {
        cards.show(this, LIST);
        closeSettings();
        updateCurrent();
    }

    private void closeSettings() {
        if (settingsPage != null) {
            remove(settingsPage);
            settingsPage = null;
        }
    }

    private void hop(World world) {
        WorldHopper attached = hopper;
        if (attached == null) {
            status.setText("Waiting for the game to start");
            return;
        }
        attached.hopTo(world);
    }

    private void quickHop(boolean back) {
        WorldHopper attached = hopper;
        if (attached == null) {
            status.setText("Waiting for the game to start");
            return;
        }
        attached.quickHop(back);
    }

    /**
     * Notes the current world and whether a player is logged in. API poll thread.
     *
     * @param session the attached API
     */
    private void readCurrentWorld(GameSession session) {
        int world = -1;
        boolean in = false;
        try {
            Client client = session.client();
            in = client.isLoggedIn();
            world = client.getWorld();
        } catch (RuntimeException e) {
            // Unreadable mid-login or as the game exits: the next poll reads it again
        }
        if (world != currentWorld || in != loggedIn) {
            currentWorld = world;
            loggedIn = in;
            SwingUtilities.invokeLater(() -> {
                updateCurrent();
                table.repaint();
            });
        }
    }

    /**
     * Shows the current world and the quick-hop keys. EDT.
     */
    private void updateCurrent() {
        int world = currentWorld;
        if (hopper == null) {
            current.setText("Waiting for the game");
        } else if (!loggedIn || world <= 0) {
            current.setText(world > 0 ? "World " + world + " · logged out" : "Not logged in");
        } else {
            World listed = worldService.findWorld(world);
            String activity = listed == null || listed.getActivity().isBlank() || "-".equals(listed.getActivity())
                    ? "" : " · " + listed.getActivity();
            current.setText("<html>World <b>" + world + "</b>" + Theme.escape(activity) + "</html>");
        }
        current.setForeground(loggedIn && world > 0 ? CURRENT_WORLD : Theme.LIGHT);
        previous.setToolTipText("Quick-hop to the previous world" + keyHint(config.previousKey()));
        next.setToolTipText("Quick-hop to the next world" + keyHint(config.nextKey()));
    }

    private void worldsChanged() {
        model.setWorlds(worldService.getWorlds());
        updateCurrent();
        updateStatus();
    }

    /**
     * Shows what the hopper is doing, or what it last said for a few seconds after, or else the list's state. EDT.
     */
    private void updateStatus() {
        WorldHopper attached = hopper;
        String said = attached != null ? attached.getStatus() : null;
        if (said != null && (attached.isHopping() || attached.getStatusAgeMillis() < STATUS_MILLIS)) {
            status.setText(said);
            status.setToolTipText(said);
            status.setForeground(Theme.TEXT);
            if (!attached.isHopping()) {
                statusTimer.setRepeats(false);
                statusTimer.restart();
            }
            return;
        }
        status.setForeground(Theme.LIGHT);
        int total = model.getRowCount();
        String error = worldService.getError();
        Instant updated = worldService.getUpdatedAt();
        if (total == 0) {
            status.setText(error != null ? "Couldn't fetch the world list: " + error
                    : worldService.isFetching() || updated == null ? "Fetching the world list…" : "No worlds");
            status.setToolTipText(error);
            return;
        }
        int shown = table.getRowCount();
        String count = shown == total ? total + " worlds" : shown + " of " + total + " worlds";
        status.setText(count + (updated != null ? " · updated " + TIME.format(updated) : "")
                + (error != null ? " · refresh failed" : ""));
        status.setToolTipText(error);
    }

    private void applyFilter() {
        String query = search.getText().trim().toLowerCase(Locale.ROOT);
        WorldRegion onlyRegion = selectedRegion();
        WorldTypeFilter onlyType = (WorldTypeFilter) type.getSelectedItem();
        sorter.setRowFilter(new RowFilter<>() {
            @Override
            public boolean include(Entry<? extends WorldTableModel, ? extends Integer> entry) {
                World world = model.world(entry.getIdentifier());
                WorldRegion worldRegion = WorldRegion.of(world.getLocation());
                if (onlyRegion != null && worldRegion != onlyRegion) {
                    return false;
                }
                if (onlyType != null && !onlyType.matches(world)) {
                    return false;
                }
                return query.isEmpty() || matches(world, worldRegion, query);
            }
        });
        updateStatus();
    }

    /**
     * Whether a world matches the search: its number starts with it, or its activity or region holds it.
     *
     * @param world       the world
     * @param worldRegion its region, or null if unknown
     * @param query       the search, lower-cased
     * @return true if it matches
     */
    static boolean matches(World world, WorldRegion worldRegion, String query) {
        if (String.valueOf(world.getId()).startsWith(query)) {
            return true;
        }
        if (world.getActivity().toLowerCase(Locale.ROOT).contains(query)) {
            return true;
        }
        return worldRegion != null && (worldRegion.getDisplayName().toLowerCase(Locale.ROOT).contains(query)
                || worldRegion.getCode().toLowerCase(Locale.ROOT).equals(query));
    }

    private WorldRegion selectedRegion() {
        int index = region.getSelectedIndex();
        return index <= 0 ? null : WorldRegion.values()[index - 1];
    }

    private void restoreFilters() {
        restoring = true;
        try {
            WorldRegion savedRegion = config.regionFilter();
            region.setSelectedIndex(savedRegion == null ? 0 : savedRegion.ordinal() + 1);
            type.setSelectedItem(config.typeFilter());
            int column = config.sortColumn();
            if (column < 0 || column >= COLUMNS.length) {
                column = WORLD;
            }
            sorter.setSortKeys(List.of(new RowSorter.SortKey(column,
                    config.sortDescending() ? SortOrder.DESCENDING : SortOrder.ASCENDING)));
        } finally {
            restoring = false;
        }
        applyFilter();
    }

    private void saveSort() {
        List<? extends RowSorter.SortKey> keys = sorter.getSortKeys();
        if (keys.isEmpty()) {
            return;
        }
        RowSorter.SortKey key = keys.get(0);
        config.sortColumn(key.getColumn());
        config.sortDescending(key.getSortOrder() == SortOrder.DESCENDING);
    }

    private static String keyHint(Keybind keybind) {
        return keybind == null || Keybind.NOT_SET.equals(keybind) ? "" : " (" + keybind + ")";
    }

    private static JButton toolbarButton(Ikon ikon, String tooltip) {
        JButton button = new JButton(FontIcon.of(ikon, 18, Theme.LIGHT));
        button.setRolloverIcon(FontIcon.of(ikon, 18, Theme.TEXT));
        button.putClientProperty(BUTTON_TYPE, BUTTON_TYPE_TOOLBAR_BUTTON);
        button.setToolTipText(tooltip);
        button.setFocusable(false);
        return button;
    }

    /**
     * The colour a world's activity is shown in: red for dangerous worlds, then the colours of seasonal, beta, PvP
     * Arena, quest speedrunning and Fresh Start worlds.
     *
     * @param world the world
     * @return the colour
     */
    static Color activityColor(World world) {
        if (world.hasType(WorldType.PVP) || world.hasType(WorldType.HIGH_RISK) || world.hasType(WorldType.DEADMAN)) {
            return DANGEROUS_WORLD;
        }
        if (world.hasType(WorldType.SEASONAL) || world.hasType(WorldType.TOURNAMENT_WORLD)) {
            return SEASONAL_WORLD;
        }
        if (world.hasType(WorldType.NOSAVE_MODE) || world.hasType(WorldType.BETA_WORLD)) {
            return BETA_WORLD;
        }
        if (world.hasType(WorldType.PVP_ARENA)) {
            return PVP_ARENA_WORLD;
        }
        if (world.hasType(WorldType.QUEST_SPEEDRUNNING)) {
            return QUEST_SPEEDRUNNING_WORLD;
        }
        if (world.hasType(WorldType.FRESH_START_WORLD)) {
            return FRESH_START_WORLD;
        }
        return Theme.TEXT;
    }

    /**
     * The world list as table rows: number, players, region code and activity.
     */
    private static final class WorldTableModel extends AbstractTableModel {
        private List<World> worlds = new ArrayList<>();

        void setWorlds(List<World> worlds) {
            this.worlds = new ArrayList<>(worlds);
            fireTableDataChanged();
        }

        World world(int row) {
            return worlds.get(row);
        }

        @Override
        public int getRowCount() {
            return worlds.size();
        }

        @Override
        public int getColumnCount() {
            return COLUMNS.length;
        }

        @Override
        public String getColumnName(int column) {
            return COLUMNS[column];
        }

        @Override
        public Class<?> getColumnClass(int column) {
            return column == WORLD || column == PLAYERS ? Integer.class : String.class;
        }

        @Override
        public Object getValueAt(int row, int column) {
            World world = worlds.get(row);
            return switch (column) {
                case WORLD -> world.getId();
                case PLAYERS -> world.getPlayerCount();
                case REGION -> {
                    WorldRegion worldRegion = WorldRegion.of(world.getLocation());
                    yield worldRegion != null ? worldRegion.getCode() : String.valueOf(world.getLocation());
                }
                default -> world.getActivity();
            };
        }
    }

    /**
     * Draws a cell in its world's colours: the current world green, numbers gold for members and grey for free
     * worlds, activities by kind of world.
     */
    private final class WorldCellRenderer extends DefaultTableCellRenderer {
        private final EmptyBorder padding = new EmptyBorder(0, 4, 0, 4);
        private final EmptyBorder numberPadding = new EmptyBorder(0, 4, 0, 8);

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean selected, boolean focused,
                                                       int row, int column) {
            super.getTableCellRendererComponent(table, value, selected, false, row, column);
            World world = model.world(table.convertRowIndexToModel(row));
            boolean isCurrent = loggedIn && world.getId() == currentWorld;
            setHorizontalAlignment(column == PLAYERS ? SwingConstants.RIGHT : SwingConstants.LEFT);
            setBorder(column == PLAYERS ? numberPadding : padding);
            Color colour;
            if (isCurrent) {
                colour = CURRENT_WORLD;
            } else if (column == WORLD) {
                colour = world.hasType(WorldType.MEMBERS) ? MEMBERS_WORLD : FREE_WORLD;
            } else if (column == ACTIVITY) {
                colour = activityColor(world);
            } else {
                colour = Theme.TEXT;
            }
            setForeground(colour);
            if (column == PLAYERS && world.getPlayerCount() < 0) {
                setText("OFF");
            }
            WorldRegion worldRegion = WorldRegion.of(world.getLocation());
            String where = worldRegion != null ? worldRegion.getDisplayName() : "location " + world.getLocation();
            String activity = world.getActivity().isBlank() || "-".equals(world.getActivity()) ? ""
                    : "<br>" + Theme.escape(world.getActivity());
            setToolTipText(isCurrent ? "<html>World " + world.getId() + ", the world you're on<br>" + where
                    + activity + "</html>"
                    : "<html>Click to hop to world " + world.getId() + "<br>" + where + activity + "</html>");
            return this;
        }
    }
}
