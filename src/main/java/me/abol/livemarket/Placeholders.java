package me.abol.livemarket;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;

/**
 * PlaceholderAPI expansion for LiveMarket (identifier {@code livemarket}).
 *
 * <p>Registered only when PlaceholderAPI is present (see {@link LiveMarket#onEnable}).
 * All placeholders are read-only views of the live market — no trading happens
 * through them. Unknown items or out-of-range ranks return {@code null}, so
 * PlaceholderAPI leaves the placeholder text untouched instead of inventing
 * a value.
 *
 * <p>Supported placeholders ( {@code <item>} is a Bukkit material name,
 * case-insensitive, e.g. {@code wheat} or {@code GOLD_INGOT} ):
 *
 * <pre>
 * %livemarket_price_wheat%    current price (2 decimals)
 * %livemarket_base_wheat%     base price
 * %livemarket_floor_wheat%    price floor
 * %livemarket_ceiling_wheat%  price ceiling
 * %livemarket_change_wheat%   signed day change, e.g. +17.14%
 * %livemarket_trend_wheat%    ▲ rising, ▼ falling, — flat
 * %livemarket_volume_wheat%   units traded today
 * %livemarket_top_1_item%     biggest mover today (pretty name)
 * %livemarket_top_1_price%    its current price
 * %livemarket_top_1_change%   its day change
 * %livemarket_count%          number of tradable items
 * </pre>
 */
public final class Placeholders extends PlaceholderExpansion {

    private final LiveMarket plugin;
    private final PriceEngine engine;

    public Placeholders(LiveMarket plugin, PriceEngine engine) {
        this.plugin = plugin;
        this.engine = engine;
    }

    @Override
    public String getIdentifier() {
        return "livemarket";
    }

    @Override
    public String getAuthor() {
        return "Abol";
    }

    @Override
    public String getVersion() {
        return plugin.getDescription().getVersion();
    }

    @Override
    public boolean persist() {
        // Keep the expansion registered across PlaceholderAPI reloads.
        return true;
    }

    @Override
    public List<String> getPlaceholders() {
        List<String> out = new ArrayList<>();
        out.add("%livemarket_price_<item>%");
        out.add("%livemarket_base_<item>%");
        out.add("%livemarket_floor_<item>%");
        out.add("%livemarket_ceiling_<item>%");
        out.add("%livemarket_change_<item>%");
        out.add("%livemarket_trend_<item>%");
        out.add("%livemarket_volume_<item>%");
        out.add("%livemarket_top_<n>_item%");
        out.add("%livemarket_top_<n>_price%");
        out.add("%livemarket_top_<n>_change%");
        out.add("%livemarket_count%");
        return out;
    }

    @Override
    public String onRequest(OfflinePlayer player, String params) {
        String lower = params.toLowerCase(Locale.ROOT);

        if (lower.equals("count")) {
            return String.valueOf(engine.defs().size());
        }

        if (lower.startsWith("top_")) {
            // top_<n>_item | top_<n>_price | top_<n>_change
            String[] parts = lower.split("_");
            if (parts.length != 3) {
                return null;
            }
            int rank;
            try {
                rank = Integer.parseInt(parts[1]);
            } catch (NumberFormatException e) {
                return null;
            }
            List<Material> movers = engine.topMovers(Math.max(rank, 1));
            if (rank < 1 || rank > movers.size()) {
                return null;
            }
            Material material = movers.get(rank - 1);
            switch (parts[2]) {
                case "item":
                    return MarketGui.pretty(material);
                case "price":
                    return money(engine.price(material));
                case "change":
                    return pct(engine.dayChange(material));
                default:
                    return null;
            }
        }

        // <field>_<item> — split on the FIRST underscore: the field is one word,
        // the item may contain underscores (e.g. gold_ingot).
        int cut = lower.indexOf('_');
        if (cut <= 0) {
            return null;
        }
        String field = lower.substring(0, cut);
        Material material = parseItem(lower.substring(cut + 1));
        if (material == null || engine.def(material) == null) {
            return null;
        }
        PriceEngine.ItemDef def = engine.def(material);
        PriceEngine.ItemState state = engine.state(material);
        switch (field) {
            case "price":
                return money(engine.price(material));
            case "base":
                return money(def.base);
            case "floor":
                return money(def.floor);
            case "ceiling":
                return money(def.ceiling);
            case "change":
                return pct(engine.dayChange(material));
            case "trend":
                return trend(material);
            case "volume":
                return state != null ? String.valueOf(state.dailyVolume) : "0";
            default:
                return null;
        }
    }

    /** Case-insensitive material lookup; null for anything that is not an item. */
    private static Material parseItem(String name) {
        Material material = Material.matchMaterial(name.toUpperCase(Locale.ROOT));
        return material != null && material.isItem() ? material : null;
    }

    /** Plain-text trend arrow (placeholders are plain text, not MiniMessage). */
    private String trend(Material material) {
        PriceEngine.ItemState state = engine.state(material);
        if (state == null || state.history.size() < 2) {
            return "—";
        }
        double first = state.history.peekFirst();
        double change = (state.price - first) / first;
        if (change > 0.02) {
            return "▲";
        }
        if (change < -0.02) {
            return "▼";
        }
        return "—";
    }

    private static String money(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    private static String pct(double percent) {
        return String.format(Locale.ROOT, "%+.2f%%", percent);
    }
}
