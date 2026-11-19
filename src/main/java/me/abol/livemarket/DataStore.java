package me.abol.livemarket;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.bukkit.Material;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Persists live prices, price history, daily volumes and player alerts to
 * {@code data.yml}. Saves are serialized on the calling thread and written
 * from an async task, so the main thread never does file I/O.
 *
 * <p>Writes are crash-safe: the snapshot goes to {@code data.yml.tmp}
 * first, the previous file is kept as {@code data.yml.bak}, and the temp
 * file is then moved over {@code data.yml} atomically — a crash mid-save
 * can never leave a torn file behind. If {@code data.yml} is unreadable
 * at load time, the backup is used instead. Writes carry a sequence
 * number and are performed under a lock, so a stale async snapshot can
 * never overwrite a newer one.
 */
public final class DataStore {

    /** One price alert: tell the player when an item goes above/below a price. */
    public record Alert(Material material, boolean above, double target) {}

    private final LiveMarket plugin;
    private final PriceEngine engine;
    private final Debug debug;
    private final File file;
    private final File backupFile;
    private final File tempFile;
    private final Map<UUID, List<Alert>> alerts = new HashMap<>();
    private volatile boolean dirty = false;
    private final Object writeLock = new Object();
    private final AtomicLong saveSequence = new AtomicLong();
    private long lastWrittenSeq = 0;

    public DataStore(LiveMarket plugin, PriceEngine engine, Debug debug) {
        this.plugin = plugin;
        this.engine = engine;
        this.debug = debug;
        this.file = new File(plugin.getDataFolder(), "data.yml");
        this.backupFile = new File(plugin.getDataFolder(), "data.yml.bak");
        this.tempFile = new File(plugin.getDataFolder(), "data.yml.tmp");
    }

    public void load() {
        if (!file.exists() && !backupFile.exists()) {
            debug.log("STORE", "load skipped — no data.yml yet (fresh start)");
            return;
        }
        YamlConfiguration data = tryLoad(file);
        if (data == null && backupFile.exists()) {
            plugin.getLogger().warning("data.yml is unreadable — restoring from data.yml.bak.");
            data = tryLoad(backupFile);
        }
        if (data == null) {
            plugin.getLogger().severe("Could not read data.yml or its backup — starting fresh. "
                    + "Prices and alerts were reset; the corrupt files were left on disk.");
            return;
        }
        engine.restoreDay(data.getString("day"));
        var prices = data.getConfigurationSection("prices");
        if (prices != null) {
            for (String key : prices.getKeys(false)) {
                Material material = Material.matchMaterial(key);
                PriceEngine.ItemState state = material != null ? engine.state(material) : null;
                if (state != null) {
                    PriceEngine.ItemDef def = engine.def(material);
                    double stored = prices.getDouble(key, def.base);
                    state.price = Math.min(def.ceiling, Math.max(def.floor, stored));
                }
            }
        }
        var history = data.getConfigurationSection("history");
        if (history != null) {
            for (String key : history.getKeys(false)) {
                Material material = Material.matchMaterial(key);
                PriceEngine.ItemState state = material != null ? engine.state(material) : null;
                if (state != null) {
                    state.history.clear();
                    for (double point : history.getDoubleList(key)) {
                        state.history.addLast(point);
                    }
                    while (state.history.size() > engine.historySize()) {
                        state.history.removeFirst();
                    }
                }
            }
        }
        var dayStart = data.getConfigurationSection("day-start");
        if (dayStart != null) {
            for (String key : dayStart.getKeys(false)) {
                Material material = Material.matchMaterial(key);
                PriceEngine.ItemState state = material != null ? engine.state(material) : null;
                if (state != null) {
                    state.dayStartPrice = dayStart.getDouble(key, state.price);
                }
            }
        }
        var volumes = data.getConfigurationSection("daily-volume");
        if (volumes != null) {
            for (String key : volumes.getKeys(false)) {
                Material material = Material.matchMaterial(key);
                PriceEngine.ItemState state = material != null ? engine.state(material) : null;
                if (state != null) {
                    state.dailyVolume = volumes.getLong(key, 0);
                }
            }
        }
        var alertsSection = data.getConfigurationSection("alerts");
        if (alertsSection != null) {
            for (String uuidKey : alertsSection.getKeys(false)) {
                try {
                    UUID uuid = UUID.fromString(uuidKey);
                    List<Alert> list = new ArrayList<>();
                    for (String encoded : alertsSection.getStringList(uuidKey)) {
                        String[] parts = encoded.split("\\|");
                        if (parts.length == 3) {
                            Material material = Material.matchMaterial(parts[0]);
                            if (material != null) {
                                list.add(new Alert(material, Boolean.parseBoolean(parts[1]),
                                        Double.parseDouble(parts[2])));
                            }
                        }
                    }
                    if (!list.isEmpty()) {
                        alerts.put(uuid, list);
                    }
                } catch (IllegalArgumentException ignored) {
                    // Skip malformed entries rather than failing the load.
                }
            }
        }
        int alertCount = alerts.values().stream().mapToInt(List::size).sum();
        debug.log("STORE", "loaded data.yml day=" + engine.currentDay()
                + " items=" + engine.allStates().size() + " alerts=" + alertCount
                + " bytes=" + file.length());
    }

    public void markDirty() {
        dirty = true;
    }

    public boolean isDirty() {
        return dirty;
    }

    /** Serialize now (cheap, in memory); write the file asynchronously. */
    public void saveAsync() {
        dirty = false;
        String text = serialize();
        long seq = saveSequence.incrementAndGet();
        debug.log("STORE", "save scheduled bytes=" + text.length() + " items=" + engine.allStates().size());
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            synchronized (writeLock) {
                if (seq <= lastWrittenSeq) {
                    return; // a newer snapshot already landed — never go backwards
                }
                try {
                    writeAtomically(text);
                    lastWrittenSeq = seq;
                } catch (IOException e) {
                    plugin.getLogger().warning("Could not save data.yml: " + e.getMessage());
                }
            }
        });
    }

    /** Final save on shutdown — synchronous, complete (prices + alerts). */
    public void saveNow() {
        dirty = false;
        String text = serialize();
        synchronized (writeLock) {
            try {
                writeAtomically(text);
                // Any async save still in flight is now stale by definition.
                lastWrittenSeq = saveSequence.incrementAndGet();
            } catch (IOException e) {
                plugin.getLogger().warning("Could not save data.yml on shutdown: " + e.getMessage());
            }
        }
    }

    private String serialize() {
        YamlConfiguration data = new YamlConfiguration();
        data.set("day", engine.currentDay());
        for (Map.Entry<Material, PriceEngine.ItemState> entry : engine.allStates().entrySet()) {
            String name = entry.getKey().name();
            PriceEngine.ItemState state = entry.getValue();
            data.set("prices." + name, state.price);
            data.set("history." + name, new ArrayList<>(state.history));
            data.set("day-start." + name, state.dayStartPrice);
            data.set("daily-volume." + name, state.dailyVolume);
        }
        for (Map.Entry<UUID, List<Alert>> entry : alerts.entrySet()) {
            List<String> encoded = new ArrayList<>();
            for (Alert alert : entry.getValue()) {
                encoded.add(alert.material().name() + "|" + alert.above() + "|" + alert.target());
            }
            data.set("alerts." + entry.getKey(), encoded);
        }
        return data.saveToString();
    }

    /**
     * Write via a temp file, keep the previous file as the backup, then
     * move the temp file over data.yml (atomically where supported), so
     * readers only ever see a complete snapshot.
     */
    private void writeAtomically(String text) throws IOException {
        Files.writeString(tempFile.toPath(), text, StandardCharsets.UTF_8);
        if (file.exists()) {
            Files.copy(file.toPath(), backupFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
        try {
            Files.move(tempFile.toPath(), file.toPath(),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tempFile.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** Load one file, returning null (with a log line) when it cannot be parsed. */
    private YamlConfiguration tryLoad(File source) {
        if (!source.exists()) {
            return null;
        }
        try {
            YamlConfiguration data = new YamlConfiguration();
            data.load(source);
            return data;
        } catch (IOException | InvalidConfigurationException e) {
            plugin.getLogger().warning("Failed to parse " + source.getName() + ": " + e.getMessage());
            return null;
        }
    }

    // ---- alerts ----

    public List<Alert> alertsOf(UUID player) {
        return alerts.getOrDefault(player, List.of());
    }

    public void addAlert(UUID player, Alert alert) {
        alerts.computeIfAbsent(player, k -> new ArrayList<>())
                .removeIf(a -> a.material() == alert.material());
        alerts.get(player).add(alert);
        debug.log("ALERT", "SET player=" + player + " item=" + alert.material().name()
                + " condition=" + (alert.above() ? "above" : "below") + " target=" + Debug.f(alert.target()));
        markDirty();
    }

    public boolean removeAlert(UUID player, Material material) {
        List<Alert> list = alerts.get(player);
        if (list == null) {
            return false;
        }
        boolean removed = list.removeIf(a -> a.material() == material);
        if (removed) {
            markDirty();
        }
        return removed;
    }

    /** Remove and return the alerts that the given price now triggers. */
    public List<Alert> collectTriggered(UUID player, Material material, double price) {
        List<Alert> list = alerts.get(player);
        if (list == null || list.isEmpty()) {
            return List.of();
        }
        List<Alert> fired = new ArrayList<>();
        list.removeIf(a -> {
            if (a.material() != material) {
                return false;
            }
            boolean hit = a.above() ? price >= a.target() : price <= a.target();
            if (hit) {
                fired.add(a);
            }
            return hit;
        });
        if (!fired.isEmpty()) {
            markDirty();
        }
        return fired;
    }

    public Map<UUID, List<Alert>> allAlerts() {
        return alerts;
    }
}
