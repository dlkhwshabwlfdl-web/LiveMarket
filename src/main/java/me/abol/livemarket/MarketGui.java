package me.abol.livemarket;

import java.util.ArrayList;
import java.util.List;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

/**
 * Chest GUIs for the market: a shop front grouped by category, and a detail
 * view per item with a price-history chart built from glass panes (no custom
 * textures) plus buy/sell buttons for 1 / 8 / 32 / 64.
 */
public final class MarketGui implements Listener {

    private final LiveMarket plugin;
    private final PriceEngine engine;
    private final MarketManager manager;

    public MarketGui(LiveMarket plugin, PriceEngine engine, MarketManager manager) {
        this.plugin = plugin;
        this.engine = engine;
        this.manager = manager;
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

    // ---------------- shop ----------------

    public void openShop(Player player) {
        ShopHolder holder = new ShopHolder();
        Inventory inv = Bukkit.createInventory(holder, 54, Msg.color(plugin.msg().raw("gui.shop-title")));
        holder.inventory = inv;
        int slot = 0;
        String lastCategory = null;
        for (PriceEngine.ItemDef def : engine.defs().values()) {
            if (!def.category.equals(lastCategory)) {
                lastCategory = def.category;
                if (slot >= 45) break;
                inv.setItem(slot++, pane(Material.YELLOW_STAINED_GLASS_PANE, "&6" + def.category, List.of()));
            }
            if (slot >= 45) break;
            inv.setItem(slot++, shopItem(player, def));
        }
        ItemStack balance = new ItemStack(Material.GOLD_INGOT);
        ItemMeta meta = balance.getItemMeta();
        meta.setDisplayName(Msg.color(plugin.msg().format("gui.your-balance",
                "balance", Msg.money(manager.economy().getBalance(player)))));
        balance.setItemMeta(meta);
        inv.setItem(49, balance);
        player.openInventory(inv);
    }

    private ItemStack shopItem(Player player, PriceEngine.ItemDef def) {
        ItemStack stack = new ItemStack(def.material);
        ItemMeta meta = stack.getItemMeta();
        meta.setDisplayName("§f" + pretty(def.material));
        List<String> lore = new ArrayList<>();
        lore.add(Msg.color(plugin.msg().format("gui.price-line",
                "price", Msg.money(engine.price(def.material)),
                "base", Msg.money(def.base),
                "trend", engine.trend(def.material))));
        lore.add(Msg.color(plugin.msg().raw("gui.click-hint")));
        meta.setLore(lore);
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
                Msg.color(plugin.msg().format("gui.detail-title", "item", pretty(material))));
        holder.inventory = inv;

        ItemStack info = new ItemStack(material);
        ItemMeta meta = info.getItemMeta();
        meta.setDisplayName("§f" + pretty(material));
        List<String> lore = new ArrayList<>();
        lore.add(Msg.color(plugin.msg().format("gui.price-line",
                "price", Msg.money(engine.price(material)),
                "base", Msg.money(def.base),
                "trend", engine.trend(material))));
        lore.add(Msg.color(plugin.msg().format("gui.history-line", "chart", sparkline(material))));
        lore.add(Msg.color(plugin.msg().format("gui.your-balance",
                "balance", Msg.money(manager.economy().getBalance(player)))));
        meta.setLore(lore);
        info.setItemMeta(meta);
        inv.setItem(4, info);

        drawChart(inv, material);

        int[] amounts = {1, 8, 32, 64};
        for (int i = 0; i < amounts.length; i++) {
            inv.setItem(45 + i, tradeButton(Material.LIME_CONCRETE, amounts[i],
                    plugin.msg().raw("gui.buy") + " " + amounts[i]));
            inv.setItem(50 + i, tradeButton(Material.RED_CONCRETE, amounts[i],
                    plugin.msg().raw("gui.sell") + " " + amounts[i]));
        }
        ItemStack back = new ItemStack(Material.ARROW);
        ItemMeta backMeta = back.getItemMeta();
        backMeta.setDisplayName(Msg.color(plugin.msg().raw("gui.back")));
        back.setItemMeta(backMeta);
        inv.setItem(49, back);

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
            inv.setItem(slot, pane(paneType, "§f" + Msg.money(value), List.of()));
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
        if (holder instanceof ShopHolder) {
            Material material = clicked.getType();
            if (engine.def(material) != null) {
                openDetail(player, material);
            }
            return;
        }
        DetailHolder detail = (DetailHolder) holder;
        if (slot == 49) {
            openShop(player);
            return;
        }
        int[] amounts = {1, 8, 32, 64};
        if (slot >= 45 && slot <= 48) {
            int amount = amounts[slot - 45];
            MarketManager.TradeOutcome outcome = manager.buy(player, detail.material, amount);
            MarketCommand.sendTradeMessage(plugin, player, true, detail.material, amount, outcome);
            openDetail(player, detail.material);
        } else if (slot >= 50 && slot <= 53) {
            int amount = amounts[slot - 50];
            int have = MarketManager.countItems(player.getInventory(), detail.material);
            int toSell = Math.min(amount, have);
            if (toSell <= 0) {
                plugin.msg().send(player, "not-enough-items",
                        "have", "0", "item", pretty(detail.material));
                return;
            }
            MarketManager.TradeOutcome outcome = manager.sell(player, detail.material, toSell);
            MarketCommand.sendTradeMessage(plugin, player, false, detail.material, toSell, outcome);
            openDetail(player, detail.material);
        }
    }

    // ---------------- helpers ----------------

    private ItemStack pane(Material type, String name, List<String> lore) {
        ItemStack stack = new ItemStack(type);
        ItemMeta meta = stack.getItemMeta();
        meta.setDisplayName(Msg.color(name));
        if (!lore.isEmpty()) {
            meta.setLore(lore.stream().map(Msg::color).toList());
        }
        stack.setItemMeta(meta);
        return stack;
    }

    private ItemStack tradeButton(Material type, int amount, String label) {
        ItemStack stack = new ItemStack(type, Math.min(64, amount));
        ItemMeta meta = stack.getItemMeta();
        meta.setDisplayName(Msg.color(label));
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
