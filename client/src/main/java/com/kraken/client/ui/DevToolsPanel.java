package com.kraken.client.ui;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.google.inject.name.Named;
import com.kraken.api.KrakenApi;
import com.kraken.api.UnsupportedBuildException;
import com.kraken.api.events.Events;
import com.kraken.api.game.Client;
import com.kraken.api.game.MapCoord;
import com.kraken.api.game.Npc;
import com.kraken.api.game.Player;
import lombok.extern.java.Log;
import org.kordamp.ikonli.materialdesign2.MaterialDesignC;
import org.kordamp.ikonli.swing.FontIcon;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableRowSorter;
import java.awt.*;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import static com.formdev.flatlaf.FlatClientProperties.STYLE;
import static com.formdev.flatlaf.FlatClientProperties.STYLE_CLASS;

/**
 * A RuneLite-style dev tools panel that proves the native API is reading the live client. It attaches the
 * {@link KrakenApi} to the embedded {@code osclient.exe}, shows a live table of the NPCs loaded around the
 * local player, and tails an event log fed by {@link Events} subscriptions (NPC load/unload, game ticks).
 *
 * <p>All API reads happen on the API poll thread; UI updates are marshalled to the EDT.
 */
@Log(topic = "kraken")
@Singleton
public final class DevToolsPanel extends JPanel {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final int MAX_LOG_LINES = 500;

    private final File exe;

    private final JLabel status = new JLabel("Waiting for the client to start…");
    private final JLabel stateValue = fact();
    private final JLabel playerValue = fact();
    private final JLabel tickValue = fact();
    private final JLabel countValue = fact();

    private final NpcTableModel npcModel = new NpcTableModel();
    private final JTextArea eventLog = new JTextArea();

    private volatile KrakenApi api;

    @Inject
    DevToolsPanel(@Named("exe") File exe) {
        super(new BorderLayout());
        this.exe = exe;
        setBorder(new EmptyBorder(12, 12, 12, 12));

        add(header(), BorderLayout.NORTH);
        add(body(), BorderLayout.CENTER);
    }

    /** Called by the launcher once {@code osclient.exe} is running. Attaches the API off the EDT. */
    public void start(long pid) {
        Thread attach = new Thread(() -> attachWithRetry(pid), "kraken-api-attach");
        attach.setDaemon(true);
        attach.start();
    }

    private void attachWithRetry(long pid) {
        Path mappings = mappingsDir();
        setStatus("Attaching to osclient.exe (pid " + pid + ")…");
        UnsupportedBuildException unsupported = null;
        for (int attempt = 0; attempt < 20; attempt++) {
            try {
                KrakenApi attached = KrakenApi.attach(pid, exe.toPath(), mappings);
                this.api = attached;
                wire(attached);
                attached.start();
                setStatus("Attached · build " + attached.mapping().build()
                        + " · mappings " + mappings);
                log.info("Dev tools attached to the native client");
                return;
            } catch (UnsupportedBuildException e) {
                unsupported = e;
                break; // retrying will not help; the build simply has no mapping
            } catch (Exception e) {
                try {
                    Thread.sleep(500);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
        if (unsupported != null) {
            setStatus("No mapping for this client build (" + unsupported.sha256().substring(0, 16) + "…). "
                    + "Run the updater for this build.");
            log.warning("Dev tools: unsupported build " + unsupported.sha256());
        } else {
            setStatus("Could not attach to osclient.exe. See the console.");
            log.warning("Dev tools: attach gave up after retries");
        }
    }

    private void wire(KrakenApi api) {
        Events events = api.events();
        events.subscribe(Events.ON_NPC_LOAD, (name, id) -> logEvent("NPC loaded", id, name));
        events.subscribe(Events.ON_NPC_UNLOAD, (name, id) -> logEvent("NPC unloaded", id, name));
        api.setPollListener(() -> refresh(api.client()));
    }

    /** Runs on the API poll thread: reads a snapshot, then pushes it to the EDT. */
    private void refresh(Client client) {
        boolean loggedIn = client.isLoggedIn();
        int tick = client.getTickCount();
        Player local = client.getLocalPlayer();
        MapCoord localAt = local == null ? null : local.location();

        List<NpcRow> rows = new ArrayList<>();
        for (Npc npc : client.getNpcs()) {
            MapCoord at = npc.location();
            int dist = (at != null && localAt != null && at.level() == localAt.level())
                    ? at.distanceTo(localAt) : -1;
            rows.add(new NpcRow(npc.index(), npc.typeId(), npc.name(),
                    at == null ? 0 : at.x(), at == null ? 0 : at.z(), dist));
        }

        String state = loggedIn ? "Logged in" : "Not logged in (state " + client.gameState() + ")";
        String player = local == null ? "–"
                : local.name() + (localAt == null ? "" : "  " + localAt);
        int npcCount = rows.size();

        SwingUtilities.invokeLater(() -> {
            stateValue.setText(state);
            playerValue.setText(player);
            tickValue.setText(String.valueOf(tick));
            countValue.setText(String.valueOf(npcCount));
            npcModel.setRows(rows);
        });
    }

    // ---- UI ------------------------------------------------------------------------------------

    private JComponent header() {
        JLabel title = new JLabel("Dev Tools", FontIcon.of(MaterialDesignC.CODE_TAGS, 18, Theme.ACCENT), SwingConstants.LEFT);
        title.putClientProperty(STYLE_CLASS, "h3");
        title.setBorder(new EmptyBorder(0, 0, 10, 0));

        JPanel facts = new JPanel(new GridLayout(0, 1, 0, 2));
        facts.putClientProperty(STYLE, "arc: 12; background: " + Theme.hex(Theme.DARKER));
        facts.setBorder(new EmptyBorder(10, 12, 10, 12));
        facts.add(factRow("Game", stateValue));
        facts.add(factRow("Local player", playerValue));
        facts.add(factRow("Tick", tickValue));
        facts.add(factRow("NPCs loaded", countValue));

        status.putClientProperty(STYLE_CLASS, "small");
        status.setForeground(Theme.LIGHT);
        status.setBorder(new EmptyBorder(10, 2, 4, 2));

        JPanel north = new JPanel(new BorderLayout());
        north.add(title, BorderLayout.NORTH);
        north.add(facts, BorderLayout.CENTER);
        north.add(status, BorderLayout.SOUTH);
        return north;
    }

    private JComponent body() {
        JTable table = new JTable(npcModel);
        table.setRowHeight(22);
        table.setFillsViewportHeight(true);
        table.setShowVerticalLines(false);
        table.getTableHeader().setReorderingAllowed(false);
        TableRowSorter<NpcTableModel> sorter = new TableRowSorter<>(npcModel);
        table.setRowSorter(sorter);

        DefaultTableCellRenderer right = new DefaultTableCellRenderer();
        right.setHorizontalAlignment(SwingConstants.RIGHT);
        int[] widths = {52, 64, 170, 56, 56, 52};
        for (int col = 0; col < widths.length; col++) {
            table.getColumnModel().getColumn(col).setPreferredWidth(widths[col]);
            if (col != 2) {
                table.getColumnModel().getColumn(col).setCellRenderer(right);
            }
        }

        JScrollPane tableScroll = new JScrollPane(table);
        tableScroll.setBorder(BorderFactory.createEmptyBorder());

        eventLog.setEditable(false);
        eventLog.putClientProperty(STYLE_CLASS, "monospaced small");
        eventLog.setBackground(Theme.DARKEST);
        eventLog.setForeground(Theme.LIGHT);
        eventLog.setMargin(new Insets(6, 8, 6, 8));
        JScrollPane logScroll = new JScrollPane(eventLog);
        logScroll.setBorder(BorderFactory.createEmptyBorder());
        logScroll.setPreferredSize(new Dimension(0, 130));

        JLabel npcsHeading = heading("NPCs around you");
        JLabel eventsHeading = heading("Events");

        JPanel tablePane = new JPanel(new BorderLayout());
        tablePane.add(npcsHeading, BorderLayout.NORTH);
        tablePane.add(tableScroll, BorderLayout.CENTER);

        JPanel logPane = new JPanel(new BorderLayout());
        logPane.add(eventsHeading, BorderLayout.NORTH);
        logPane.add(logScroll, BorderLayout.CENTER);

        JPanel center = new JPanel(new BorderLayout(0, 10));
        center.setBorder(new EmptyBorder(12, 0, 0, 0));
        center.add(tablePane, BorderLayout.CENTER);
        center.add(logPane, BorderLayout.SOUTH);
        return center;
    }

    private void logEvent(String what, int id, String name) {
        String line = TIME.format(LocalTime.now()) + "  " + what + "  #" + id
                + (name == null || name.isEmpty() ? "" : "  " + name);
        SwingUtilities.invokeLater(() -> {
            eventLog.append(line + "\n");
            int lines = eventLog.getLineCount();
            if (lines > MAX_LOG_LINES) {
                try {
                    int end = eventLog.getLineEndOffset(lines - MAX_LOG_LINES);
                    eventLog.replaceRange("", 0, end);
                } catch (javax.swing.text.BadLocationException ignored) {
                    // line bookkeeping raced a clear; harmless
                }
            }
            eventLog.setCaretPosition(eventLog.getDocument().getLength());
        });
    }

    private void setStatus(String text) {
        SwingUtilities.invokeLater(() -> status.setText(text));
    }

    private static JLabel heading(String text) {
        JLabel label = new JLabel(text);
        label.putClientProperty(STYLE_CLASS, "semibold");
        label.setBorder(new EmptyBorder(0, 0, 6, 0));
        return label;
    }

    private static JLabel fact() {
        JLabel label = new JLabel("–");
        label.setForeground(Theme.TEXT);
        return label;
    }

    private static JComponent factRow(String key, JLabel value) {
        JLabel label = new JLabel(key);
        label.setForeground(Theme.LIGHT);
        JPanel row = new JPanel(new BorderLayout(12, 0));
        row.setOpaque(false);
        row.add(label, BorderLayout.WEST);
        row.add(value, BorderLayout.EAST);
        return row;
    }

    /** Finds the repo's {@code mappings/} directory: an override property, else the nearest one above the CWD. */
    private static Path mappingsDir() {
        String override = System.getProperty("kraken.mappings");
        if (override != null) {
            return Path.of(override);
        }
        Path start = Path.of("").toAbsolutePath();
        for (Path dir = start; dir != null; dir = dir.getParent()) {
            Path candidate = dir.resolve("mappings");
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
        }
        return start.resolve("mappings");
    }

    // ---- table model ---------------------------------------------------------------------------

    private record NpcRow(int index, int id, String name, int x, int z, int dist) {
    }

    private static final class NpcTableModel extends AbstractTableModel {
        private final String[] columns = {"Index", "Id", "Name", "X", "Z", "Dist"};
        private List<NpcRow> rows = new ArrayList<>();

        void setRows(List<NpcRow> rows) {
            this.rows = rows;
            fireTableDataChanged();
        }

        @Override
        public int getRowCount() {
            return rows.size();
        }

        @Override
        public int getColumnCount() {
            return columns.length;
        }

        @Override
        public String getColumnName(int column) {
            return columns[column];
        }

        @Override
        public Class<?> getColumnClass(int column) {
            return column == 2 ? String.class : Integer.class;
        }

        @Override
        public Object getValueAt(int rowIndex, int column) {
            NpcRow row = rows.get(rowIndex);
            return switch (column) {
                case 0 -> row.index();
                case 1 -> row.id();
                case 2 -> row.name();
                case 3 -> row.x();
                case 4 -> row.z();
                default -> row.dist() < 0 ? null : row.dist();
            };
        }
    }
}
