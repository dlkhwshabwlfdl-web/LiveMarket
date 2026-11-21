package me.abol.livemarket;

import java.io.File;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * LiveMarket — a player market whose prices move with supply and demand.
 *
 * <p>The main class only wires things together: the {@link PriceEngine}
 * holds the pricing math, {@link MarketManager} executes trades through
 * Vault, {@link Storage} persists state, and the GUI/command classes are
 * thin front-ends over those three.
 */
public final class LiveMarket extends JavaPlugin {

    private Msg msg;
    private Debug debug;
    private PriceEngine engine;
    private Storage store;
    private MarketManager manager;
    private MarketGui gui;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        if (!new File(getDataFolder(), "messages.yml").exists()) {
            saveResource("messages.yml", false);
        }
        msg = new Msg(this);
        debug = new Debug(this, getConfig().getBoolean("settings.debug", false));

        engine = new PriceEngine();
        engine.setDebug(debug);
        engine.loadConfig(this);
        store = createStore();
        store.load();

        Economy economy = setupEconomy();
        if (economy == null) {
            getLogger().severe("LiveMarket needs Vault with an economy provider "
                    + "(e.g. EssentialsX) — disabling.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        manager = new MarketManager(this, engine, store, economy, debug);
        manager.setPaused(!getConfig().getBoolean("settings.trading-enabled", true));
        if (manager.isPaused()) {
            getLogger().info("Trading starts PAUSED (settings.trading-enabled: false) — "
                    + "an admin can resume it with /market resume.");
        }
        gui = new MarketGui(this, engine, manager, debug);

        MarketCommand command = new MarketCommand(this, engine, manager, store, gui);
        var market = getCommand("market");
        if (market != null) {
            market.setExecutor(command);
            market.setTabCompleter(command);
        }
        getServer().getPluginManager().registerEvents(gui, this);

        // PlaceholderAPI is optional: when it is present, expose the live
        // market as %livemarket_...% placeholders (scoreboards, chat, signs).
        if (getServer().getPluginManager().getPlugin("PlaceholderAPI") != null) {
            boolean registered = new Placeholders(this, engine).register();
            getLogger().info(registered
                    ? "PlaceholderAPI expansion registered (%livemarket_...%)."
                    : "PlaceholderAPI expansion failed to register.");
        }

        // Quiet markets drift back to base prices, one step per minute.
        getServer().getScheduler().runTaskTimer(this, () -> {
            engine.decayTick();
            store.markDirty();
        }, 1200L, 1200L);
        // Persist state every five minutes when anything changed.
        getServer().getScheduler().runTaskTimer(this, () -> {
            if (store.isDirty()) {
                store.saveAsync();
            }
        }, 6000L, 6000L);
        // Alert sweep: catch crossings caused by decay as well as trades.
        getServer().getScheduler().runTaskTimer(this, () -> {
            for (Material material : engine.defs().keySet()) {
                checkAlerts(material);
            }
        }, 600L, 600L);

        getLogger().info("LiveMarket enabled — " + engine.defs().size()
                + " items trading, economy: " + economy.getName() + ".");
    }

    @Override
    public void onDisable() {
        if (store != null) {
            store.saveNow();
        }
    }

    /**
     * Pick the storage backend from {@code storage.type} in config.yml.
     * MySQL is tried first when selected; when the database cannot be
     * reached the plugin logs an error and keeps running on data.yml
     * instead of refusing to start — prices still save, just locally.
     */
    private Storage createStore() {
        String type = getConfig().getString("storage.type", "yaml");
        if ("mysql".equalsIgnoreCase(type)) {
            try {
                MySqlStore mysql = new MySqlStore(this, engine, debug);
                mysql.connect();
                getLogger().info("Storage: MySQL " + mysql.describe());
                return mysql;
            } catch (Exception e) {
                getLogger().severe("MySQL storage unavailable (" + e.getMessage()
                        + ") — falling back to data.yml. Check storage.mysql in config.yml.");
            }
        } else if (!"yaml".equalsIgnoreCase(type)) {
            getLogger().warning("Unknown storage.type '" + type + "' — using yaml (data.yml).");
        }
        return new YamlStore(this, engine, debug);
    }

    private Economy setupEconomy() {
        if (getServer().getPluginManager().getPlugin("Vault") == null) {
            return null;
        }
        RegisteredServiceProvider<Economy> provider =
                getServer().getServicesManager().getRegistration(Economy.class);
        return provider != null ? provider.getProvider() : null;
    }

    /** Fire (once) every alert the item's current price triggers. */
    public void checkAlerts(Material material) {
        double price = engine.price(material);
        for (Player player : getServer().getOnlinePlayers()) {
            for (Storage.Alert fired : store.collectTriggered(player.getUniqueId(), material, price)) {
                debug.log("ALERT", "FIRED player=" + player.getName() + " item=" + material.name()
                        + " condition=" + (fired.above() ? "above" : "below")
                        + " target=" + Debug.f(fired.target()) + " price=" + Debug.f(price));
                msg.send(player, "alert-fired",
                        "item", MarketGui.pretty(material),
                        "price", Msg.money(price),
                        "condition", fired.above() ? "above" : "below",
                        "target", Msg.money(fired.target()));
            }
        }
    }

    public void reloadAll() {
        reloadConfig();
        msg.reload();
        engine.loadConfig(this);
        // The config is the source of truth on reload: a live /market pause
        // ends here if the config enables trading (and vice versa).
        if (manager != null) {
            manager.setPaused(!getConfig().getBoolean("settings.trading-enabled", true));
        }
        store.markDirty();
    }

    FileConfiguration loadMessagesFile() {
        return YamlConfiguration.loadConfiguration(new File(getDataFolder(), "messages.yml"));
    }

    public Msg msg() {
        return msg;
    }

    public Debug debug() {
        return debug;
    }

    public PriceEngine engine() {
        return engine;
    }

    public MarketManager manager() {
        return manager;
    }
}
