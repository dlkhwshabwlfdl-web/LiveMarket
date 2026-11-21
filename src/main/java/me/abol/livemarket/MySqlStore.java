package me.abol.livemarket;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.bukkit.Material;

/**
 * MySQL/MariaDB storage for LiveMarket — the same live prices, history,
 * daily volumes and alerts as {@link YamlStore}, in three tables instead
 * of {@code data.yml}. Pick it with {@code storage.type: mysql} in
 * config.yml; it is meant for networks where several servers share one
 * market state (or where an admin prefers a database over a flat file).
 *
 * <p>The MySQL driver (Connector/J) is shaded into the plugin jar, so
 * there is nothing extra to install on the server — only a reachable
 * MySQL/MariaDB database. Tables are created automatically on first
 * enable. If the database is empty but a {@code data.yml} already
 * exists, the file's state is migrated into MySQL once, so switching
 * backends never loses prices or alerts.
 *
 * <p>Saves use the same pattern as the YAML backend: the snapshot is
 * taken on the calling thread, the write runs asynchronously, and a
 * sequence number stops a stale snapshot overwriting a newer one. A
 * failed write logs a warning and marks the state dirty, so the next
 * scheduled save retries instead of silently dropping data.
 */
public final class MySqlStore extends AbstractStore {

    /** One persisted item row: price, history, day-start price, volume. */
    private record PriceRow(String material, double price, String history,
                            double dayStart, long dailyVolume) {}

    /** One persisted alert row. */
    private record AlertRow(String uuid, String material, boolean above, double target) {}

    /** A full snapshot, taken on the calling thread before the async write. */
    private record Snapshot(String day, List<PriceRow> prices, List<AlertRow> alerts) {}

    private final String host;
    private final int port;
    private final String database;
    private final String username;
    private final String password;
    private final String prefix;
    private final String jdbcUrl;

    private Connection connection;
    private final Object writeLock = new Object();
    private final AtomicLong saveSequence = new AtomicLong();
    private long lastWrittenSeq = 0;

    public MySqlStore(LiveMarket plugin, PriceEngine engine, Debug debug) {
        super(plugin, engine, debug);
        var mysql = plugin.getConfig().getConfigurationSection("storage.mysql");
        host = mysql != null ? mysql.getString("host", "localhost") : "localhost";
        port = mysql != null ? mysql.getInt("port", 3306) : 3306;
        database = mysql != null ? mysql.getString("database", "livemarket") : "livemarket";
        username = mysql != null ? mysql.getString("username", "root") : "root";
        password = mysql != null ? mysql.getString("password", "") : "";
        // Table names go into SQL verbatim, so only allow safe characters.
        String rawPrefix = mysql != null ? mysql.getString("table-prefix", "lm_") : "lm_";
        prefix = rawPrefix != null && rawPrefix.matches("[A-Za-z0-9_]+") ? rawPrefix : "lm_";
        jdbcUrl = "jdbc:mysql://" + host + ":" + port + "/" + database
                + "?sslMode=DISABLED&allowPublicKeyRetrieval=true&serverTimezone=UTC";
    }

    /** Human-readable endpoint for the enable log line (never the password). */
    public String describe() {
        return host + ":" + port + "/" + database + " (tables " + prefix + "*)";
    }

    /** Open the connection and create the tables. Throws when unreachable. */
    public void connect() throws SQLException {
        try {
            Class.forName("com.mysql.cj.jdbc.Driver");
        } catch (ClassNotFoundException e) {
            throw new SQLException("MySQL driver not found in the plugin jar", e);
        }
        connection = DriverManager.getConnection(jdbcUrl, username, password);
        try (Statement stmt = connection.createStatement()) {
            stmt.executeUpdate("CREATE TABLE IF NOT EXISTS `" + prefix + "meta` ("
                    + "`k` VARCHAR(64) PRIMARY KEY, `v` VARCHAR(255) NOT NULL)");
            stmt.executeUpdate("CREATE TABLE IF NOT EXISTS `" + prefix + "prices` ("
                    + "`material` VARCHAR(64) PRIMARY KEY, `price` DOUBLE NOT NULL, "
                    + "`day_start` DOUBLE NOT NULL, `daily_volume` BIGINT NOT NULL, "
                    + "`history` TEXT NOT NULL)");
            stmt.executeUpdate("CREATE TABLE IF NOT EXISTS `" + prefix + "alerts` ("
                    + "`player_uuid` CHAR(36) NOT NULL, `material` VARCHAR(64) NOT NULL, "
                    + "`above` TINYINT(1) NOT NULL, `target` DOUBLE NOT NULL, "
                    + "PRIMARY KEY (`player_uuid`, `material`))");
        }
        debug.log("STORE", "MySQL connected " + describe());
    }

    @Override
    public void load() {
        try {
            ensureConnection();
            if (isEmpty() && new YamlStore(plugin, engine, debug).dataFile().exists()) {
                // First run on MySQL with an existing data.yml: adopt its state.
                // Prices/history live in the engine (shared), but alerts live
                // in the store instance — copy them over, or the migration
                // would silently drop every alert.
                YamlStore yaml = new YamlStore(plugin, engine, debug);
                yaml.load();
                alerts.putAll(yaml.allAlerts());
                saveNow();
                plugin.getLogger().info("Migrated market state from data.yml into MySQL.");
                return;
            }
            String day = null;
            try (Statement stmt = connection.createStatement();
                 ResultSet rs = stmt.executeQuery(
                         "SELECT `k`, `v` FROM `" + prefix + "meta`")) {
                while (rs.next()) {
                    if ("day".equals(rs.getString(1))) {
                        day = rs.getString(2);
                    }
                }
            }
            engine.restoreDay(day);
            try (Statement stmt = connection.createStatement();
                 ResultSet rs = stmt.executeQuery(
                         "SELECT `material`, `price`, `day_start`, `daily_volume`, `history`"
                                 + " FROM `" + prefix + "prices`")) {
                while (rs.next()) {
                    Material material = Material.matchMaterial(rs.getString(1));
                    PriceEngine.ItemState state = material != null ? engine.state(material) : null;
                    if (state == null) {
                        continue;
                    }
                    PriceEngine.ItemDef def = engine.def(material);
                    double stored = rs.getDouble(2);
                    state.price = Math.min(def.ceiling, Math.max(def.floor, stored));
                    state.dayStartPrice = rs.getDouble(3);
                    state.dailyVolume = rs.getLong(4);
                    state.history.clear();
                    String raw = rs.getString(5);
                    if (raw != null && !raw.isEmpty()) {
                        for (String part : raw.split(",")) {
                            try {
                                state.history.addLast(Double.parseDouble(part));
                            } catch (NumberFormatException ignored) {
                                // Skip a bad history point, keep the rest.
                            }
                        }
                    }
                    while (state.history.size() > engine.historySize()) {
                        state.history.removeFirst();
                    }
                }
            }
            try (Statement stmt = connection.createStatement();
                 ResultSet rs = stmt.executeQuery(
                         "SELECT `player_uuid`, `material`, `above`, `target`"
                                 + " FROM `" + prefix + "alerts`")) {
                while (rs.next()) {
                    try {
                        UUID uuid = UUID.fromString(rs.getString(1));
                        Material material = Material.matchMaterial(rs.getString(2));
                        if (material != null) {
                            restoreAlert(uuid, material, rs.getBoolean(3), rs.getDouble(4));
                        }
                    } catch (IllegalArgumentException ignored) {
                        // Skip malformed rows rather than failing the load.
                    }
                }
            }
            int alertCount = alerts.values().stream().mapToInt(List::size).sum();
            debug.log("STORE", "loaded MySQL day=" + engine.currentDay()
                    + " items=" + engine.allStates().size() + " alerts=" + alertCount);
        } catch (SQLException e) {
            plugin.getLogger().severe("Could not load market state from MySQL: " + e.getMessage()
                    + " — starting with base prices.");
        }
    }

    /** Serialize now (cheap, in memory); write asynchronously. */
    @Override
    public void saveAsync() {
        clearDirty();
        Snapshot snapshot = snapshot();
        long seq = saveSequence.incrementAndGet();
        debug.log("STORE", "save scheduled (MySQL) items=" + snapshot.prices().size());
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            synchronized (writeLock) {
                if (seq <= lastWrittenSeq) {
                    return; // a newer snapshot already landed — never go backwards
                }
                try {
                    writeSnapshot(snapshot);
                    lastWrittenSeq = seq;
                } catch (SQLException e) {
                    plugin.getLogger().warning("Could not save to MySQL: " + e.getMessage());
                    markDirty(); // retry on the next scheduled save
                }
            }
        });
    }

    /** Final save on shutdown — synchronous, complete (prices + alerts). */
    @Override
    public void saveNow() {
        clearDirty();
        Snapshot snapshot = snapshot();
        synchronized (writeLock) {
            try {
                writeSnapshot(snapshot);
                // Any async save still in flight is now stale by definition.
                lastWrittenSeq = saveSequence.incrementAndGet();
            } catch (SQLException e) {
                plugin.getLogger().warning("Could not save to MySQL on shutdown: " + e.getMessage());
            }
        }
    }

    private Snapshot snapshot() {
        List<PriceRow> prices = new ArrayList<>();
        for (Map.Entry<Material, PriceEngine.ItemState> entry : engine.allStates().entrySet()) {
            PriceEngine.ItemState state = entry.getValue();
            StringBuilder history = new StringBuilder();
            for (double point : state.history) {
                if (history.length() > 0) {
                    history.append(',');
                }
                history.append(point);
            }
            prices.add(new PriceRow(entry.getKey().name(), state.price,
                    history.toString(), state.dayStartPrice, state.dailyVolume));
        }
        List<AlertRow> alertRows = new ArrayList<>();
        for (Map.Entry<UUID, List<Alert>> entry : alerts.entrySet()) {
            for (Alert alert : entry.getValue()) {
                alertRows.add(new AlertRow(entry.getKey().toString(),
                        alert.material().name(), alert.above(), alert.target()));
            }
        }
        return new Snapshot(engine.currentDay(), prices, alertRows);
    }

    /** Replace the whole persisted state in one transaction. */
    private void writeSnapshot(Snapshot snapshot) throws SQLException {
        ensureConnection();
        boolean wasAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            try (PreparedStatement meta = connection.prepareStatement(
                    "REPLACE INTO `" + prefix + "meta` (`k`, `v`) VALUES ('day', ?)")) {
                meta.setString(1, snapshot.day());
                meta.executeUpdate();
            }
            try (Statement stmt = connection.createStatement()) {
                stmt.executeUpdate("DELETE FROM `" + prefix + "prices`");
                stmt.executeUpdate("DELETE FROM `" + prefix + "alerts`");
            }
            try (PreparedStatement prices = connection.prepareStatement(
                    "INSERT INTO `" + prefix + "prices`"
                            + " (`material`, `price`, `day_start`, `daily_volume`, `history`)"
                            + " VALUES (?, ?, ?, ?, ?)")) {
                for (PriceRow row : snapshot.prices()) {
                    prices.setString(1, row.material());
                    prices.setDouble(2, row.price());
                    prices.setDouble(3, row.dayStart());
                    prices.setLong(4, row.dailyVolume());
                    prices.setString(5, row.history());
                    prices.addBatch();
                }
                prices.executeBatch();
            }
            try (PreparedStatement alertStmt = connection.prepareStatement(
                    "INSERT INTO `" + prefix + "alerts`"
                            + " (`player_uuid`, `material`, `above`, `target`)"
                            + " VALUES (?, ?, ?, ?)")) {
                for (AlertRow row : snapshot.alerts()) {
                    alertStmt.setString(1, row.uuid());
                    alertStmt.setString(2, row.material());
                    alertStmt.setBoolean(3, row.above());
                    alertStmt.setDouble(4, row.target());
                    alertStmt.addBatch();
                }
                alertStmt.executeBatch();
            }
            connection.commit();
        } catch (SQLException e) {
            connection.rollback();
            throw e;
        } finally {
            connection.setAutoCommit(wasAutoCommit);
        }
    }

    private boolean isEmpty() throws SQLException {
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM `" + prefix + "prices`")) {
            return rs.next() && rs.getLong(1) == 0;
        }
    }

    /** Reopen the connection when it dropped; throws when unreachable. */
    private void ensureConnection() throws SQLException {
        if (connection != null) {
            try {
                if (!connection.isClosed() && connection.isValid(2)) {
                    return;
                }
            } catch (SQLException ignored) {
                // Fall through and reconnect.
            }
            try {
                connection.close();
            } catch (SQLException ignored) {
                // Already broken — just replace it.
            }
        }
        connection = DriverManager.getConnection(jdbcUrl, username, password);
        debug.log("STORE", "MySQL reconnected " + describe());
    }
}
