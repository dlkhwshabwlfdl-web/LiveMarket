package me.abol.livemarket;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Persists live prices, price history, daily volumes and player alerts to
 * {@code data.yml}. Saves are serialized on the calling thread and written
 * from an async task, so the main thread never does file I/O.
 */
public final class DataStore {

    /** One price alert: tell the player when an item goes above/below a price. */
    public record Alert(Material material, boolean above, double target) {}

    private final LiveMarket plugin;
    private final PriceEngine engine;
    private final Debug debug;
    private final File file;
    private final Map<UUID, List<Alert>> alerts = new HashMap<>();
    private volatile boolean dirty = false;

    public DataStore(LiveMarket plugin, PriceEngine engine, Debug debug) {
        this.plugin = plugin;
        this.engine = engine;
        this.debug = debug;
        this.file = new File(plugin.getDataFolder(), "data.yml");
    }

    public void load() {
        if (!file.exists()) {
            debug.log("STORE", "load skipped — no data.yml yet (fresh start)");
            return;
        }
        YamlConfiguration data = YamlConfiguration.loadConfiguration(file);
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
        String text = data.saveToString();
        debug.log("STORE", "save scheduled bytes=" + text.length() + " items=" + engine.allStates().size());
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                Files.writeString(file.toPath(), text, StandardCharsets.UTF_8);
            } catch (IOException e) {
                plugin.getLogger().warning("Could not save data.yml: " + e.getMessage());
            }
        });
    }

    /** Final save on shutdown — synchronous, complete (prices + alerts). */
    public void saveNow() {
        dirty = false;
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
        try {
            data.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("Could not save data.yml on shutdown: " + e.getMessage());
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
