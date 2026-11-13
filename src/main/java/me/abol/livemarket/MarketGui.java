package me.abol.livemarket;

import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

/**
 * Chest GUIs for the market: a category-grouped shop front and a per-item
 * detail view with a glass-pane price chart, live day stats, estimated
 * totals on the trade buttons, and click/trade sounds. All text is
 * Adventure components built from messages.yml (MiniMessage).
 */
public final class MarketGui implements Listener {

    private final LiveMarket plugin;
    private final PriceEngine engine;
    private final MarketManager manager;
    private final Debug debug;

    public MarketGui(LiveMarket plugin, PriceEngine engine, MarketManager manager, Debug debug) {
        this.plugin = plugin;
        this.engine = engine;
        this.manager = manager;
        this.debug = debug;
    }

    public static final class ShopHolder implements InventoryHolder {
        private Inventory inventory;
        @Override public Inventory getInventory() { return inventory; }
    }

    public static final class DetailHolder implements InventoryHolder {
        public final Material material;
        private Inventory inventory;
        DetailHolder(Material material) { this.material = material; }
        @Override public Inventory getInventory() { return inventory; }
    }

    private static Component noItalic(Component component) {
        return component.decoration(TextDecoration.ITALIC, false);
    }

    private Component text(String minimessage) {
        return noItalic(plugin.msg().parse(minimessage));
    }

    private Component msgComp(String key, String... placeholders) {
        return noItalic(plugin.msg().comp(key, placeholders));
    }

    // ---------------- shop ----------------

    public void openShop(Player player) {
        ShopHolder holder = new ShopHolder();
        Inventory inv = Bukkit.createInventory(holder, 54, msgComp("gui.shop-title"));
        holder.inventory = inv;
        int slot = 0;
        String lastCategory = null;
        for (PriceEngine.ItemDef def : engine.defs().values()) {
            if (slot >= 45) break;
            if (!def.category.equals(lastCategory)) {
                lastCategory = def.category;
                inv.setItem(slot++, pane(Material.LIGHT_GRAY_STAINED_GLASS_PANE,
                        "<gradient:#ffd97a:#e8a33d><bold>" + def.category + "</bold></gradient>", List.of()));
                if (slot >= 45) break;
            }
            inv.setItem(slot++, shopItem(def));
        }
        for (int i = 45; i < 54; i++) {
            inv.setItem(i, pane(Material.GRAY_STAINED_GLASS_PANE, " ", List.of()));
        }
        ItemStack balance = new ItemStack(Material.GOLD_INGOT);
        ItemMeta meta = balance.getItemMeta();
        meta.displayName(msgComp("gui.your-balance",
                "balance", Msg.money(manager.economy().getBalance(player))));
        balance.setItemMeta(meta);
        inv.setItem(49, balance);
        debug.log("GUI", "OPEN shop player=" + player.getName() + " items=" + engine.defs().size());
        player.openInventory(inv);
    }

    private ItemStack shopItem(PriceEngine.ItemDef def) {
        ItemStack stack = new ItemStack(def.material);
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(text("<white>" + pretty(def.material) + "</white>"));
        List<Component> lore = new ArrayList<>();
        lore.add(msgComp("gui.price-line",
                "price", Msg.money(engine.price(def.material)),
                "base", Msg.money(def.base),
                "trend", engine.trend(def.material)));
        lore.add(msgComp("gui.day-line",
                "change", dayChangeText(def.material),
                "volume", String.valueOf(engine.state(def.material).dailyVolume)));
        lore.add(msgComp("gui.click-hint"));
        meta.lore(lore);
        stack.setItemMeta(meta);
        return stack;
    }

    // ---------------- detail ----------------

    public void openDetail(Player player, Material material) {
        PriceEngine.ItemDef def = engine.def(material);
        if (def == null) {
            return;
        }
        DetailHolder holder = new DetailHolder(material);
        Inventory inv = Bukkit.createInventory(holder, 54,
                msgComp("gui.detail-title", "item", pretty(material)));
        holder.inventory = inv;

        ItemStack info = new ItemStack(material);
        ItemMeta meta = info.getItemMeta();
        meta.displayName(text("<white>" + pretty(material) + "</white>"));
        List<Component> lore = new ArrayList<>();
        lore.add(msgComp("gui.price-line",
                "price", Msg.money(engine.price(material)),
                "base", Msg.money(def.base),
                "trend", engine.trend(material)));
        lore.add(msgComp("gui.day-line",
                "change", dayChangeText(material),
                "volume", String.valueOf(engine.state(material).dailyVolume)));
        lore.add(msgComp("gui.range-line",
                "floor", Msg.money(def.floor), "ceiling", Msg.money(def.ceiling)));
        lore.add(msgComp("gui.history-line", "chart", sparkline(material)));
        lore.add(msgComp("gui.your-balance",
                "balance", Msg.money(manager.economy().getBalance(player))));
        meta.lore(lore);
        info.setItemMeta(meta);
        inv.setItem(4, info);

        drawChart(inv, material);

        int[] amounts = {1, 8, 32, 64};
        double unit = engine.price(material);
        for (int i = 0; i < amounts.length; i++) {
            inv.setItem(45 + i, tradeButton(Material.LIME_CONCRETE, amounts[i],
                    plugin.msg().raw("gui.buy") + " " + amounts[i],
                    msgComp("gui.total-buy",
                            "total", Msg.money(unit * amounts[i]),
                            "amount", String.valueOf(amounts[i]))));
            inv.setItem(50 + i, tradeButton(Material.RED_CONCRETE, amounts[i],
                    plugin.msg().raw("gui.sell") + " " + amounts[i],
                    msgComp("gui.total-sell",
                            "total", Msg.money(unit * amounts[i] * (1 - engine.taxRate())))));
        }
        ItemStack back = new ItemStack(Material.ARROW);
        ItemMeta backMeta = back.getItemMeta();
        backMeta.displayName(msgComp("gui.back"));
        back.setItemMeta(backMeta);
        inv.setItem(49, back);

        debug.log("GUI", "OPEN detail player=" + player.getName() + " item=" + material.name()
                + " price=" + Debug.f(unit));
        player.openInventory(inv);
    }

    /** Last 9 price points as columns of panes, newest on the right. */
    private void drawChart(Inventory inv, Material material) {
        List<Double> points = new ArrayList<>(engine.state(material).history);
        if (points.size() > 9) {
            points = points.subList(points.size() - 9, points.size());
        }
        if (points.isEmpty()) {
            return;
        }
        double min = points.stream().mapToDouble(Double::doubleValue).min().orElse(0);
        double max = points.stream().mapToDouble(Double::doubleValue).max().orElse(1);
        int startCol = 9 - points.size();
        for (int i = 0; i < points.size(); i++) {
            double value = points.get(i);
            int level = max - min < 0.0001 ? 1 : (int) Math.round((value - min) / (max - min) * 2);
            Material paneType = Material.GRAY_STAINED_GLASS_PANE;
            if (i > 0) {
                if (value > points.get(i - 1)) paneType = Material.LIME_STAINED_GLASS_PANE;
                else if (value < points.get(i - 1)) paneType = Material.RED_STAINED_GLASS_PANE;
            }
            int row = 2 - level; // chart rows are inventory rows 1..3 (slots 9..35)
            int slot = 9 + row * 9 + startCol + i;
            inv.setItem(slot, pane(paneType, "<white>" + Msg.money(value) + "</white>", List.of()));
        }
    }

    private String sparkline(Material material) {
        List<Double> points = new ArrayList<>(engine.state(material).history);
        if (points.size() > 16) {
            points = points.subList(points.size() - 16, points.size());
        }
        if (points.isEmpty()) {
            return "—";
        }
        String blocks = "▁▂▃▄▅▆▇█";
        double min = points.stream().mapToDouble(Double::doubleValue).min().orElse(0);
        double max = points.stream().mapToDouble(Double::doubleValue).max().orElse(1);
        StringBuilder out = new StringBuilder();
        for (double value : points) {
            int idx = max - min < 0.0001 ? 3 : (int) Math.round((value - min) / (max - min) * 7);
            out.append(blocks.charAt(idx));
        }
        return out.toString();
    }

    private String dayChangeText(Material material) {
        double change = engine.dayChange(material);
        String formatted = String.format("%+.1f%%", change);
        if (change > 0.05) {
            return "<green>" + formatted + "</green>";
        }
        if (change < -0.05) {
            return "<red>" + formatted + "</red>";
        }
        return "<gray>" + formatted + "</gray>";
    }

    // ---------------- clicks ----------------

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        InventoryHolder holder = event.getView().getTopInventory().getHolder();
        if (!(holder instanceof ShopHolder) && !(holder instanceof DetailHolder)) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        if (event.getClickedInventory() == null
                || !event.getClickedInventory().equals(event.getView().getTopInventory())) {
            return;
        }
        int slot = event.getSlot();
        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || clicked.getType().isAir()) {
            return;
        }
        debug.log("GUI", "CLICK player=" + player.getName() + " slot=" + slot
                + " clicked=" + clicked.getType().name()
                + " view=" + (holder instanceof ShopHolder ? "shop" : "detail"));
        if (holder instanceof ShopHolder) {
            Material material = clicked.getType();
            if (engine.def(material) != null) {
                player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.6f, 1.1f);
                openDetail(player, material);
            }
            return;
        }
        DetailHolder detail = (DetailHolder) holder;
        if (slot == 49) {
            player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.6f, 0.9f);
            openShop(player);
            return;
        }
        int[] amounts = {1, 8, 32, 64};
        if (slot >= 45 && slot <= 48) {
            int amount = amounts[slot - 45];
            MarketManager.TradeOutcome outcome = manager.buy(player, detail.material, amount);
            MarketCommand.sendTradeMessage(plugin, player, true, detail.material, amount, outcome);
            playTradeSound(player, outcome.result() == MarketManager.Result.OK);
            openDetail(player, detail.material);
        } else if (slot >= 50 && slot <= 53) {
            int amount = amounts[slot - 50];
            int have = MarketManager.countItems(player.getInventory(), detail.material);
            int toSell = Math.min(amount, have);
            if (toSell <= 0) {
                plugin.msg().send(player, "not-enough-items",
                        "have", "0", "item", pretty(detail.material));
                playTradeSound(player, false);
                return;
            }
            MarketManager.TradeOutcome outcome = manager.sell(player, detail.material, toSell);
            MarketCommand.sendTradeMessage(plugin, player, false, detail.material, toSell, outcome);
            playTradeSound(player, outcome.result() == MarketManager.Result.OK);
            openDetail(player, detail.material);
        }
    }

    private void playTradeSound(Player player, boolean success) {
        if (success) {
            player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.7f, 1.2f);
        } else {
            player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.7f, 1.0f);
        }
    }

    // ---------------- helpers ----------------

    private ItemStack pane(Material type, String name, List<Component> lore) {
        ItemStack stack = new ItemStack(type);
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(text(name));
        if (!lore.isEmpty()) {
            meta.lore(lore);
        }
        stack.setItemMeta(meta);
        return stack;
    }

    private ItemStack tradeButton(Material type, int amount, String label, Component estimate) {
        ItemStack stack = new ItemStack(type, Math.min(64, amount));
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(text(label));
        meta.lore(List.of(noItalic(estimate)));
        stack.setItemMeta(meta);
        return stack;
    }

    public static String pretty(Material material) {
        String[] parts = material.name().toLowerCase().split("_");
        StringBuilder out = new StringBuilder();
        for (String part : parts) {
            if (!part.isEmpty()) {
                out.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1)).append(' ');
            }
        }
        return out.toString().trim();
    }
}
