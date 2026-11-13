package me.abol.livemarket;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

/** /market — all subcommands. Prices and trading live in the engine/manager. */
public final class MarketCommand implements CommandExecutor, TabCompleter {

    private final LiveMarket plugin;
    private final PriceEngine engine;
    private final MarketManager manager;
    private final DataStore store;
    private final MarketGui gui;

    public MarketCommand(LiveMarket plugin, PriceEngine engine, MarketManager manager,
                         DataStore store, MarketGui gui) {
        this.plugin = plugin;
        this.engine = engine;
        this.manager = manager;
        this.store = store;
        this.gui = gui;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        Msg msg = plugin.msg();
        if (args.length == 0) {
            if (sender instanceof Player player) {
                if (!player.hasPermission("livemarket.use")) {
                    msg.send(sender, "no-permission");
                    return true;
                }
                gui.openShop(player);
            } else {
                sendHelp(sender);
            }
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "buy" -> trade(sender, args, true);
            case "sell" -> trade(sender, args, false);
            case "sellall" -> sellAll(sender);
            case "price" -> price(sender, args);
            case "top" -> top(sender);
            case "alert" -> alert(sender, args);
            case "alerts" -> alerts(sender);
            case "reload" -> admin(sender, () -> {
                plugin.reloadAll();
                msg.send(sender, "reloaded");
            });
            case "setprice" -> setPrice(sender, args);
            case "reset" -> reset(sender, args);
            case "help" -> sendHelp(sender);
            default -> sendHelp(sender);
        }
        return true;
    }

    private void trade(CommandSender sender, String[] args, boolean buy) {
        Msg msg = plugin.msg();
        if (!(sender instanceof Player player)) {
            msg.send(sender, "player-only");
            return;
        }
        if (args.length < 2) {
            sendHelp(sender);
            return;
        }
        Material material = resolveItem(sender, args[1]);
        if (material == null) {
            return;
        }
        int amount = 1;
        if (args.length >= 3) {
            amount = parseAmount(sender, args[2]);
            if (amount < 0) {
                return;
            }
        }
        if (!buy) {
            int have = MarketManager.countItems(player.getInventory(), material);
            if (have < amount) {
                msg.send(player, "not-enough-items",
                        "have", String.valueOf(have), "item", MarketGui.pretty(material));
                return;
            }
        }
        MarketManager.TradeOutcome outcome = buy
                ? manager.buy(player, material, amount)
                : manager.sell(player, material, amount);
        sendTradeMessage(plugin, player, buy, material, amount, outcome);
    }

    private void sellAll(CommandSender sender) {
        Msg msg = plugin.msg();
        if (!(sender instanceof Player player)) {
            msg.send(sender, "player-only");
            return;
        }
        if (!player.hasPermission("livemarket.sell")) {
            msg.send(sender, "no-permission");
            return;
        }
        double total = manager.sellAll(player);
        if (total <= 0) {
            msg.send(player, "sold-nothing");
        } else {
            msg.send(player, "sold-all", "gain", Msg.money(total));
        }
    }

    private void price(CommandSender sender, String[] args) {
        Msg msg = plugin.msg();
        if (!sender.hasPermission("livemarket.use")) {
            msg.send(sender, "no-permission");
            return;
        }
        if (args.length < 2) {
            msg.send(sender, "price-header");
            for (PriceEngine.ItemDef def : engine.defs().values()) {
                sendPriceLine(sender, def.material);
            }
            return;
        }
        Material material = resolveItem(sender, args[1]);
        if (material != null) {
            sendPriceLine(sender, material);
        }
    }

    private void sendPriceLine(CommandSender sender, Material material) {
        PriceEngine.ItemDef def = engine.def(material);
        plugin.msg().send(sender, "price-info",
                "item", MarketGui.pretty(material),
                "price", Msg.money(engine.price(material)),
                "base", Msg.money(def.base),
                "trend", engine.trend(material));
    }

    private void top(CommandSender sender) {
        Msg msg = plugin.msg();
        if (!sender.hasPermission("livemarket.use")) {
            msg.send(sender, "no-permission");
            return;
        }
        List<Material> movers = engine.topMovers(5);
        if (movers.isEmpty()) {
            msg.send(sender, "top-empty");
            return;
        }
        msg.send(sender, "top-header");
        for (Material material : movers) {
            msg.send(sender, "top-line",
                    "item", MarketGui.pretty(material),
                    "change", String.format("%+.1f", engine.dayChange(material)),
                    "price", Msg.money(engine.price(material)));
        }
    }

    private void alert(CommandSender sender, String[] args) {
        Msg msg = plugin.msg();
        if (!(sender instanceof Player player)) {
            msg.send(sender, "player-only");
            return;
        }
        if (!player.hasPermission("livemarket.use")) {
            msg.send(sender, "no-permission");
            return;
        }
        if (args.length >= 3 && args[2].equalsIgnoreCase("remove")) {
            Material material = resolveItem(sender, args[1]);
            if (material == null) {
                return;
            }
            if (store.removeAlert(player.getUniqueId(), material)) {
                store.saveAsync();
            }
            msg.send(player, "alert-removed", "item", MarketGui.pretty(material));
            return;
        }
        if (args.length < 4) {
            msg.send(player, "alert-usage");
            return;
        }
        Material material = resolveItem(sender, args[1]);
        if (material == null) {
            return;
        }
        boolean above;
        if (args[2].equalsIgnoreCase("above")) {
            above = true;
        } else if (args[2].equalsIgnoreCase("below")) {
            above = false;
        } else {
            msg.send(player, "alert-usage");
            return;
        }
        double target;
        try {
            target = Double.parseDouble(args[3]);
        } catch (NumberFormatException e) {
            msg.send(player, "alert-usage");
            return;
        }
        if (target <= 0) {
            msg.send(player, "alert-usage");
            return;
        }
        store.addAlert(player.getUniqueId(), new DataStore.Alert(material, above, target));
        store.saveAsync();
        msg.send(player, "alert-set",
                "item", MarketGui.pretty(material),
                "condition", above ? "above" : "below",
                "price", Msg.money(target));
    }

    private void alerts(CommandSender sender) {
        Msg msg = plugin.msg();
        if (!(sender instanceof Player player)) {
            msg.send(sender, "player-only");
            return;
        }
        List<DataStore.Alert> list = store.alertsOf(player.getUniqueId());
        if (list.isEmpty()) {
            msg.send(player, "alerts-empty");
            return;
        }
        msg.send(player, "alerts-header");
        for (DataStore.Alert alert : list) {
            msg.send(player, "alert-set",
                    "item", MarketGui.pretty(alert.material()),
                    "condition", alert.above() ? "above" : "below",
                    "price", Msg.money(alert.target()));
        }
    }

    private void setPrice(CommandSender sender, String[] args) {
        Msg msg = plugin.msg();
        admin(sender, () -> {
            if (args.length < 3) {
                sendHelp(sender);
                return;
            }
            Material material = resolveItem(sender, args[1]);
            if (material == null) {
                return;
            }
            double value;
            try {
                value = Double.parseDouble(args[2]);
            } catch (NumberFormatException e) {
                sendHelp(sender);
                return;
            }
            engine.setPrice(material, value);
            store.markDirty();
            store.saveAsync();
            plugin.getLogger().info("Admin " + sender.getName() + " set " + material.name()
                    + " price to " + engine.price(material));
            plugin.checkAlerts(material);
            msg.send(sender, "setprice-done",
                    "item", MarketGui.pretty(material), "price", Msg.money(engine.price(material)));
        });
    }

    private void reset(CommandSender sender, String[] args) {
        Msg msg = plugin.msg();
        admin(sender, () -> {
            if (args.length < 2) {
                sendHelp(sender);
                return;
            }
            Material material = resolveItem(sender, args[1]);
            if (material == null) {
                return;
            }
            engine.resetPrice(material);
            store.markDirty();
            store.saveAsync();
            plugin.getLogger().info("Admin " + sender.getName() + " reset " + material.name() + " to base price.");
            plugin.checkAlerts(material);
            msg.send(sender, "reset-done",
                    "item", MarketGui.pretty(material),
                    "base", Msg.money(engine.def(material).base));
        });
    }

    private void admin(CommandSender sender, Runnable action) {
        if (!sender.hasPermission("livemarket.admin")) {
            plugin.msg().send(sender, "no-permission");
            return;
        }
        action.run();
    }

    private Material resolveItem(CommandSender sender, String name) {
        Material material = Material.matchMaterial(name.toUpperCase(Locale.ROOT));
        if (material == null || engine.def(material) == null) {
            plugin.msg().send(sender, "unknown-item", "item", name);
            return null;
        }
        return material;
    }

    private int parseAmount(CommandSender sender, String text) {
        try {
            int amount = Integer.parseInt(text);
            if (amount < 1 || amount > MarketManager.MAX_TRADE) {
                throw new NumberFormatException();
            }
            return amount;
        } catch (NumberFormatException e) {
            plugin.msg().send(sender, "invalid-amount", "max", String.valueOf(MarketManager.MAX_TRADE));
            return -1;
        }
    }

    private void sendHelp(CommandSender sender) {
        for (String line : plugin.msg().list("help")) {
            sender.sendMessage(Msg.color(line));
        }
    }

    /** Shared by the command and the GUI so both report trades identically. */
    public static void sendTradeMessage(LiveMarket plugin, Player player, boolean buy,
                                        Material material, int amount,
                                        MarketManager.TradeOutcome outcome) {
        Msg msg = plugin.msg();
        String item = MarketGui.pretty(material);
        switch (outcome.result()) {
            case OK -> {
                if (buy) {
                    msg.send(player, "bought",
                            "amount", String.valueOf(amount), "item", item,
                            "cost", Msg.money(outcome.total()),
                            "price", Msg.money(outcome.newPrice()));
                } else {
                    msg.send(player, "sold",
                            "amount", String.valueOf(amount), "item", item,
                            "gain", Msg.money(outcome.total()),
                            "tax", String.format("%.0f", plugin.engine().taxRate() * 100),
                            "price", Msg.money(outcome.newPrice()));
                }
            }
            case NO_PERMISSION -> msg.send(player, "no-permission");
            case NOT_ENOUGH_MONEY -> msg.send(player, "not-enough-money",
                    "cost", Msg.money(outcome.total()),
                    "balance", Msg.money(plugin.manager().economy().getBalance(player)));
            case NOT_ENOUGH_ITEMS -> msg.send(player, "not-enough-items",
                    "have", String.valueOf(MarketManager.countItems(player.getInventory(), material)),
                    "item", item);
            case INVENTORY_FULL -> msg.send(player, "inventory-full");
            case ECONOMY_ERROR -> msg.send(player, "economy-error");
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            for (String sub : List.of("buy", "sell", "sellall", "price", "top", "alert", "alerts",
                    "reload", "setprice", "reset", "help")) {
                if (sub.startsWith(args[0].toLowerCase(Locale.ROOT))) {
                    out.add(sub);
                }
            }
        } else if (args.length == 2 && List.of("buy", "sell", "price", "alert", "setprice", "reset")
                .contains(args[0].toLowerCase(Locale.ROOT))) {
            for (Material material : engine.defs().keySet()) {
                if (material.name().toLowerCase(Locale.ROOT).startsWith(args[1].toLowerCase(Locale.ROOT))) {
                    out.add(material.name());
                }
            }
        } else if (args.length == 3 && args[0].equalsIgnoreCase("alert")) {
            for (String option : List.of("above", "below", "remove")) {
                if (option.startsWith(args[2].toLowerCase(Locale.ROOT))) {
                    out.add(option);
                }
            }
        }
        return out;
    }
}
