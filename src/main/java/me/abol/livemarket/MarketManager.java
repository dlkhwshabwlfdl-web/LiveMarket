package me.abol.livemarket;

import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

/**
 * Executes trades through Vault. Rules that keep the market safe:
 * buying checks space and balance before charging; selling removes the
 * items from the inventory first and only then pays out. The price impact
 * of a trade is applied after the trade completes, at the price the trade
 * actually executed at.
 */
public final class MarketManager {

    public static final int MAX_TRADE = 2304; // one full inventory of stacks

    private final LiveMarket plugin;
    private final PriceEngine engine;
    private final DataStore store;
    private final Economy economy;
    private final Debug debug;

    public MarketManager(LiveMarket plugin, PriceEngine engine, DataStore store, Economy economy, Debug debug) {
        this.plugin = plugin;
        this.engine = engine;
        this.store = store;
        this.economy = economy;
        this.debug = debug;
    }

    public Economy economy() {
        return economy;
    }

    public enum Result {
        OK, NO_PERMISSION, NOT_ENOUGH_MONEY, NOT_ENOUGH_ITEMS, INVENTORY_FULL, ECONOMY_ERROR
    }

    public record TradeOutcome(Result result, double total, double newPrice) {}

    public TradeOutcome buy(Player player, Material material, int amount) {
        double unit = engine.price(material);
        double cost = unit * amount;
        String who = "player=" + player.getName() + " item=" + material.name() + " side=BUY amount=" + amount;
        if (!player.hasPermission("livemarket.sell")) {
            debug.log("TRADE", who + " result=NO_PERMISSION");
            return new TradeOutcome(Result.NO_PERMISSION, 0, unit);
        }
        double balance = economy.getBalance(player);
        if (balance < cost) {
            debug.log("TRADE", who + " result=NOT_ENOUGH_MONEY cost=" + Debug.f(cost) + " balance=" + Debug.f(balance));
            return new TradeOutcome(Result.NOT_ENOUGH_MONEY, cost, unit);
        }
        int capacity = freeCapacity(player.getInventory(), material);
        if (capacity < amount) {
            debug.log("TRADE", who + " result=INVENTORY_FULL capacity=" + capacity);
            return new TradeOutcome(Result.INVENTORY_FULL, cost, unit);
        }
        EconomyResponse response = economy.withdrawPlayer(player, cost);
        if (!response.transactionSuccess()) {
            debug.log("TRADE", who + " result=ECONOMY_ERROR vault=" + response.errorMessage);
            return new TradeOutcome(Result.ECONOMY_ERROR, cost, unit);
        }
        player.getInventory().addItem(new ItemStack(material, amount));
        engine.applyTrade(material, amount, true);
        store.markDirty();
        plugin.checkAlerts(material);
        debug.log("TRADE", who + " result=OK unit=" + Debug.f(unit) + " total=" + Debug.f(cost)
                + " balanceAfter=" + Debug.f(economy.getBalance(player)));
        return new TradeOutcome(Result.OK, cost, engine.price(material));
    }

    public TradeOutcome sell(Player player, Material material, int amount) {
        String who = "player=" + player.getName() + " item=" + material.name() + " side=SELL amount=" + amount;
        if (!player.hasPermission("livemarket.sell")) {
            debug.log("TRADE", who + " result=NO_PERMISSION");
            return new TradeOutcome(Result.NO_PERMISSION, 0, engine.price(material));
        }
        PlayerInventory inventory = player.getInventory();
        int have = countItems(inventory, material);
        if (have < amount) {
            debug.log("TRADE", who + " result=NOT_ENOUGH_ITEMS have=" + have);
            return new TradeOutcome(Result.NOT_ENOUGH_ITEMS, 0, engine.price(material));
        }
        double unit = engine.price(material);
        double gross = unit * amount;
        double gain = gross * (1 - engine.taxRate());

        // Remove first, pay second — never the other way round.
        inventory.removeItem(new ItemStack(material, amount));
        EconomyResponse response = economy.depositPlayer(player, gain);
        if (!response.transactionSuccess()) {
            inventory.addItem(new ItemStack(material, amount)); // give the items back
            debug.log("TRADE", who + " result=ECONOMY_ERROR vault=" + response.errorMessage + " itemsRestored=true");
            return new TradeOutcome(Result.ECONOMY_ERROR, 0, unit);
        }
        engine.applyTrade(material, amount, false);
        store.markDirty();
        plugin.checkAlerts(material);
        debug.log("TRADE", who + " result=OK unit=" + Debug.f(unit) + " gross=" + Debug.f(gross)
                + " gain=" + Debug.f(gain) + " taxRate=" + engine.taxRate()
                + " balanceAfter=" + Debug.f(economy.getBalance(player)));
        return new TradeOutcome(Result.OK, gain, engine.price(material));
    }

    /** Sell everything tradable in the player's inventory. Returns total gained. */
    public double sellAll(Player player) {
        double total = 0;
        for (Material material : engine.defs().keySet()) {
            int count = countItems(player.getInventory(), material);
            if (count > 0) {
                TradeOutcome outcome = sell(player, material, count);
                if (outcome.result() == Result.OK) {
                    total += outcome.total();
                }
            }
        }
        return total;
    }

    public static int countItems(PlayerInventory inventory, Material material) {
        int count = 0;
        for (ItemStack stack : inventory.getStorageContents()) {
            if (stack != null && stack.getType() == material) {
                count += stack.getAmount();
            }
        }
        return count;
    }

    private static int freeCapacity(PlayerInventory inventory, Material material) {
        int capacity = 0;
        int max = material.getMaxStackSize();
        for (ItemStack stack : inventory.getStorageContents()) {
            if (stack == null || stack.getType().isAir()) {
                capacity += max;
            } else if (stack.getType() == material) {
                capacity += max - stack.getAmount();
            }
        }
        return capacity;
    }
}
