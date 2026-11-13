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
 * Vault, {@link DataStore} persists state, and the GUI/command classes are
 * thin front-ends over those three.
 */
public final class LiveMarket extends JavaPlugin {

    private Msg msg;
    private PriceEngine engine;
    private DataStore store;
    private MarketManager manager;
    private MarketGui gui;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        if (!new File(getDataFolder(), "messages.yml").exists()) {
            saveResource("messages.yml", false);
        }
        msg = new Msg(this);

        engine = new PriceEngine();
        engine.loadConfig(this);
        store = new DataStore(this, engine);
        store.load();

        Economy economy = setupEconomy();
        if (economy == null) {
            getLogger().severe("LiveMarket needs Vault with an economy provider "
                    + "(e.g. EssentialsX) — disabling.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        manager = new MarketManager(this, engine, store, economy);
        gui = new MarketGui(this, engine, manager);

        MarketCommand command = new MarketCommand(this, engine, manager, store, gui);
        var market = getCommand("market");
        if (market != null) {
            market.setExecutor(command);
            market.setTabCompleter(command);
        }
        getServer().getPluginManager().registerEvents(gui, this);

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
            for (DataStore.Alert fired : store.collectTriggered(player.getUniqueId(), material, price)) {
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
        store.markDirty();
    }

    FileConfiguration loadMessagesFile() {
        return YamlConfiguration.loadConfiguration(new File(getDataFolder(), "messages.yml"));
    }

    public Msg msg() {
        return msg;
    }

    public PriceEngine engine() {
        return engine;
    }

    public MarketManager manager() {
        return manager;
    }
}
