package me.abol.livemarket;

import java.util.List;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;

/** All player-facing strings live in messages.yml — nothing is hardcoded. */
public final class Msg {

    private final LiveMarket plugin;
    private FileConfiguration messages;

    public Msg(LiveMarket plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        this.messages = plugin.loadMessagesFile();
    }

    public String raw(String key) {
        String value = messages.getString(key);
        return value != null ? value : key;
    }

    public List<String> list(String key) {
        return messages.getStringList(key);
    }

    public String format(String key, String... placeholders) {
        String text = raw(key);
        for (int i = 0; i + 1 < placeholders.length; i += 2) {
            text = text.replace("{" + placeholders[i] + "}", placeholders[i + 1]);
        }
        return color(text);
    }

    public void send(CommandSender to, String key, String... placeholders) {
        to.sendMessage(format("prefix") + format(key, placeholders));
    }

    public static String color(String text) {
        return ChatColor.translateAlternateColorCodes('&', text);
    }

    /** Prices are shown with up to 2 decimals, trailing zeros trimmed. */
    public static String money(double value) {
        if (value == Math.rint(value)) {
            return String.format("%,.0f", value);
        }
        return String.format("%,.2f", value);
    }
}
