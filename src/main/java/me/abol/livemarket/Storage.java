package me.abol.livemarket;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Material;

/**
 * Where live prices, price history, daily volumes and player alerts live.
 *
 * <p>Two backends implement this: {@link YamlStore} ({@code data.yml},
 * the default — crash-safe, no setup) and {@link MySqlStore} (a
 * MySQL/MariaDB database, for servers that share state across a network).
 * Both keep the same in-memory alert handling; only the load/save
 * mechanics differ. Callers never touch a backend class directly.
 */
public interface Storage {

    /** One price alert: tell the player when an item goes above/below a price. */
    record Alert(Material material, boolean above, double target) {}

    /** Load persisted state into the engine (and the alert map). */
    void load();

    /** Mark the state dirty — something changed since the last save. */
    void markDirty();

    boolean isDirty();

    /** Serialize now (cheap, in memory); write asynchronously. */
    void saveAsync();

    /** Final save on shutdown — synchronous and complete. */
    void saveNow();

    // ---- alerts (in-memory in every backend; persisted by save) ----

    List<Alert> alertsOf(UUID player);

    void addAlert(UUID player, Alert alert);

    /** Remove the player's alert for the item; true when one existed. */
    boolean removeAlert(UUID player, Material material);

    /** Remove and return the alerts that the given price now triggers. */
    List<Alert> collectTriggered(UUID player, Material material, double price);

    Map<UUID, List<Alert>> allAlerts();
}
