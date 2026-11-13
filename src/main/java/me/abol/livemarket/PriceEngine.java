package me.abol.livemarket;

import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;

/**
 * The dynamic pricing engine.
 *
 * <p>Formula: a trade of {@code n} units moves the price by a saturating
 * fraction of its current value —
 * {@code move = maxMove * n / (n + pressureK)} — up for buys, down for sells.
 * Because the fraction saturates, bulk trades hit diminishing returns and a
 * single huge trade can never move the price by more than {@code maxMove}.
 * The result is always clamped to the item's [floor, ceiling]. When nobody
 * trades, prices decay back toward their base price. Once an item's daily
 * traded volume passes the guard, further impact is dampened until the next
 * day, which keeps a farmed item from being crashed or pumped on repeat.
 */
public final class PriceEngine {

    /** Static definition of a tradable item, from config.yml. */
    public static final class ItemDef {
        public final Material material;
        public final String category;
        public final double base;
        public final double floor;
        public final double ceiling;

        ItemDef(Material material, String category, double base, double floor, double ceiling) {
            this.material = material;
            this.category = category;
            this.base = base;
            this.floor = floor;
            this.ceiling = ceiling;
        }
    }

    /** Live state of one item. */
    public static final class ItemState {
        public double price;
        public final Deque<Double> history = new ArrayDeque<>();
        public double dayStartPrice;
        public long dailyVolume;

        ItemState(double base) {
            this.price = base;
            this.dayStartPrice = base;
        }
    }

    private final Map<Material, ItemDef> defs = new LinkedHashMap<>();
    private final Map<Material, ItemState> states = new LinkedHashMap<>();

    private double pressureK = 48;
    private double maxMove = 0.30;
    private double decayPerMinute = 0.01;
    private double taxRate = 0.05;
    private long dailyVolumeGuard = 10000;
    private double impactBeyondGuard = 0.25;
    private int historySize = 50;
    private String currentDay = LocalDate.now().toString();
    private Debug debug;

    public void setDebug(Debug debug) {
        this.debug = debug;
    }

    /** (Re)read item definitions and formula constants from the config. */
    public void loadConfig(LiveMarket plugin) {
        var config = plugin.getConfig();
        pressureK = Math.max(1, config.getDouble("settings.pressure-k", 48));
        maxMove = Math.min(0.95, Math.max(0.01, config.getDouble("settings.max-move", 0.30)));
        decayPerMinute = Math.min(1, Math.max(0, config.getDouble("settings.decay-per-minute", 0.01)));
        taxRate = Math.min(0.9, Math.max(0, config.getDouble("settings.tax-rate", 0.05)));
        dailyVolumeGuard = Math.max(0, config.getLong("settings.daily-volume-guard", 10000));
        impactBeyondGuard = Math.min(1, Math.max(0, config.getDouble("settings.impact-beyond-guard", 0.25)));
        historySize = Math.max(5, config.getInt("settings.history-size", 50));

        Map<Material, ItemDef> fresh = new LinkedHashMap<>();
        ConfigurationSection items = config.getConfigurationSection("items");
        if (items != null) {
            for (String key : items.getKeys(false)) {
                Material material = Material.matchMaterial(key);
                if (material == null || !material.isItem()) {
                    plugin.getLogger().warning("Skipping unknown material in config: " + key);
                    continue;
                }
                ConfigurationSection s = items.getConfigurationSection(key);
                double base = s.getDouble("base", 1.0);
                double floor = Math.max(0.01, s.getDouble("floor", base / 4));
                double ceiling = Math.max(floor, s.getDouble("ceiling", base * 4));
                base = Math.min(ceiling, Math.max(floor, base));
                fresh.put(material, new ItemDef(material, s.getString("category", "Misc"), base, floor, ceiling));
            }
        }
        defs.clear();
        defs.putAll(fresh);
        // Keep live state for items that still exist; new items start at base.
        states.keySet().retainAll(defs.keySet());
        for (ItemDef def : defs.values()) {
            states.computeIfAbsent(def.material, m -> new ItemState(def.base));
        }
    }

    public Map<Material, ItemDef> defs() {
        return defs;
    }

    public ItemDef def(Material material) {
        return defs.get(material);
    }

    public ItemState state(Material material) {
        return states.get(material);
    }

    public double price(Material material) {
        ItemState state = states.get(material);
        return state != null ? state.price : defs.get(material).base;
    }

    public double taxRate() {
        return taxRate;
    }

    /**
     * Apply a completed trade's price impact. {@code amount} is the number of
     * units traded; buys push the price up, sells push it down.
     */
    public void applyTrade(Material material, long amount, boolean buy) {
        rollDayIfNeeded();
        ItemState state = states.get(material);
        ItemDef def = defs.get(material);
        if (state == null || def == null || amount <= 0) {
            return;
        }
        double fraction = (double) amount / ((double) amount + pressureK);
        double move = maxMove * fraction;
        boolean guard = state.dailyVolume > dailyVolumeGuard;
        if (guard) {
            move *= impactBeyondGuard;
        }
        double before = state.price;
        double raw = buy ? before * (1 + move) : before * (1 - move);
        double next = clamp(raw, def);
        boolean clamped = next != raw;
        setPrice(state, def, next);
        state.dailyVolume += amount;
        if (debug != null) {
            debug.log("PRICE", "item=" + material.name() + " side=" + (buy ? "BUY" : "SELL")
                    + " amount=" + amount + " frac=" + Debug.f(fraction)
                    + " movePct=" + Debug.pct(buy ? move : -move)
                    + " before=" + Debug.f(before) + " raw=" + Debug.f(raw)
                    + " after=" + Debug.f(state.price) + " clamped=" + clamped
                    + " floor=" + def.floor + " ceiling=" + def.ceiling
                    + " guard=" + guard + " dailyVolume=" + state.dailyVolume);
        }
    }

    /** One minute of quiet-market decay toward base prices. */
    public void decayTick() {
        rollDayIfNeeded();
        if (decayPerMinute <= 0) {
            return;
        }
        int adjusted = 0;
        Material biggest = null;
        double biggestMove = 0;
        for (Map.Entry<Material, ItemState> entry : states.entrySet()) {
            ItemDef def = defs.get(entry.getKey());
            ItemState state = entry.getValue();
            double gap = def.base - state.price;
            if (Math.abs(gap) > 0.001) {
                double next = state.price + gap * decayPerMinute;
                double applied = clamp(next, def) - state.price;
                state.price = clamp(next, def);
                adjusted++;
                if (Math.abs(applied) > Math.abs(biggestMove)) {
                    biggestMove = applied;
                    biggest = entry.getKey();
                }
            }
        }
        if (debug != null && adjusted > 0) {
            debug.log("DECAY", "adjusted=" + adjusted + " rate=" + decayPerMinute
                    + (biggest != null ? " biggest=" + biggest.name() + " move=" + Debug.f(biggestMove)
                    + " price=" + Debug.f(states.get(biggest).price) : ""));
        }
    }

    /** Admin action: set an exact price (clamped), recorded in history. */
    public void setPrice(Material material, double newPrice) {
        ItemState state = states.get(material);
        ItemDef def = defs.get(material);
        if (state != null && def != null) {
            setPrice(state, def, newPrice);
        }
    }

    /** Admin action: return an item to its base price. */
    public void resetPrice(Material material) {
        ItemDef def = defs.get(material);
        if (def != null) {
            setPrice(material, def.base);
        }
    }

    private void setPrice(ItemState state, ItemDef def, double newPrice) {
        state.price = clamp(newPrice, def);
        state.history.addLast(state.price);
        while (state.history.size() > historySize) {
            state.history.removeFirst();
        }
    }

    private double clamp(double value, ItemDef def) {
        return Math.min(def.ceiling, Math.max(def.floor, value));
    }

    /** Trend of the current price versus the oldest kept history point (MiniMessage). */
    public String trend(Material material) {
        ItemState state = states.get(material);
        if (state == null || state.history.size() < 2) {
            return "<gray>—</gray>";
        }
        double first = state.history.peekFirst();
        double change = (state.price - first) / first;
        if (change > 0.02) {
            return "<green>▲</green>";
        }
        if (change < -0.02) {
            return "<red>▼</red>";
        }
        return "<gray>—</gray>";
    }

    /** Signed percent change since the start of today. */
    public double dayChange(Material material) {
        ItemState state = states.get(material);
        if (state == null || state.dayStartPrice <= 0) {
            return 0;
        }
        return (state.price - state.dayStartPrice) / state.dayStartPrice * 100.0;
    }

    /** Items with trades today, biggest absolute movers first. */
    public List<Material> topMovers(int limit) {
        rollDayIfNeeded();
        List<Material> traded = new ArrayList<>();
        for (Map.Entry<Material, ItemState> entry : states.entrySet()) {
            if (entry.getValue().dailyVolume > 0) {
                traded.add(entry.getKey());
            }
        }
        traded.sort(Comparator.comparingDouble((Material m) -> Math.abs(dayChange(m))).reversed());
        return traded.subList(0, Math.min(limit, traded.size()));
    }

    private void rollDayIfNeeded() {
        String today = LocalDate.now().toString();
        if (!today.equals(currentDay)) {
            currentDay = today;
            for (ItemState state : states.values()) {
                state.dailyVolume = 0;
                state.dayStartPrice = state.price;
            }
        }
    }

    // ---- persistence hooks (used by DataStore) ----

    public String currentDay() {
        return currentDay;
    }

    public void restoreDay(String day) {
        this.currentDay = day != null ? day : LocalDate.now().toString();
    }

    public Map<Material, ItemState> allStates() {
        return states;
    }
}
