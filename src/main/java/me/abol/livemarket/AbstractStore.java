package me.abol.livemarket;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Material;

/**
 * Shared in-memory alert handling for every {@link Storage} backend.
 *
 * <p>Alerts live in a map while the server runs and are only written out
 * by a backend's save; the backends differ in where they write, not in
 * how alerts behave. The dirty flag is shared too: any alert change
 * marks the state dirty so the next scheduled save picks it up.
 */
public abstract class AbstractStore implements Storage {

    protected final LiveMarket plugin;
    protected final PriceEngine engine;
    protected final Debug debug;
    protected final Map<UUID, List<Alert>> alerts = new HashMap<>();
    private volatile boolean dirty = false;

    protected AbstractStore(LiveMarket plugin, PriceEngine engine, Debug debug) {
        this.plugin = plugin;
        this.engine = engine;
        this.debug = debug;
    }

    @Override
    public void markDirty() {
        dirty = true;
    }

    @Override
    public boolean isDirty() {
        return dirty;
    }

    /** Clear the dirty flag; backends call this when a save starts. */
    protected void clearDirty() {
        dirty = false;
    }

    @Override
    public List<Alert> alertsOf(UUID player) {
        return alerts.getOrDefault(player, List.of());
    }

    @Override
    public void addAlert(UUID player, Alert alert) {
        alerts.computeIfAbsent(player, k -> new ArrayList<>())
                .removeIf(a -> a.material() == alert.material());
        alerts.get(player).add(alert);
        debug.log("ALERT", "SET player=" + player + " item=" + alert.material().name()
                + " condition=" + (alert.above() ? "above" : "below") + " target=" + Debug.f(alert.target()));
        markDirty();
    }

    @Override
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

    @Override
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

    @Override
    public Map<UUID, List<Alert>> allAlerts() {
        return alerts;
    }

    /** Restore one persisted alert row into the in-memory map. */
    protected void restoreAlert(UUID player, Material material, boolean above, double target) {
        alerts.computeIfAbsent(player, k -> new ArrayList<>())
                .add(new Alert(material, above, target));
    }
}
