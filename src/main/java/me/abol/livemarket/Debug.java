package me.abol.livemarket;

import java.util.Locale;

/**
 * Structured debug logging. When enabled (config {@code settings.debug} or
 * {@code /market debug on}), every mechanism of the plugin — pricing math,
 * trade validation, Vault responses, GUI clicks, alert checks, persistence —
 * writes one greppable line per event:
 *
 * <pre>[LiveMarket-Debug][TRADE] player=Steve item=WHEAT side=BUY amount=64 unit=2.00 total=128.00 frac=0.5714 movePct=+17.14 before=2.00 after=2.34 clamped=false guard=false</pre>
 *
 * The lines are designed to be analysed after a test run: every number the
 * engine used is in the line, so a log reader can re-derive each result.
 */
public final class Debug {

    private final LiveMarket plugin;
    private boolean enabled;

    public Debug(LiveMarket plugin, boolean enabled) {
        this.plugin = plugin;
        this.enabled = enabled;
    }

    public boolean enabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        plugin.getLogger().info("Debug mode " + (enabled ? "ENABLED" : "disabled")
                + " — mechanism logging " + (enabled ? "is now verbose." : "back to normal."));
    }

    public void log(String category, String message) {
        if (enabled) {
            plugin.getLogger().info("[LiveMarket-Debug][" + category + "] " + message);
        }
    }

    public static String f(double value) {
        return String.format(Locale.ROOT, "%.4f", value);
    }

    public static String pct(double fraction) {
        return String.format(Locale.ROOT, "%+.2f%%", fraction * 100);
    }
}
