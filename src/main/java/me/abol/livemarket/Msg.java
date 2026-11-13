package me.abol.livemarket;

import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;

/**
 * All player-facing strings live in messages.yml in MiniMessage format —
 * nothing is hardcoded. Placeholders look like {item} and are substituted
 * before the MiniMessage parse, so values can never inject formatting.
 */
public final class Msg {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

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

    /** Substitute {placeholders}, then parse MiniMessage into a Component. */
    public Component comp(String key, String... placeholders) {
        String text = raw(key);
        for (int i = 0; i + 1 < placeholders.length; i += 2) {
            text = text.replace("{" + placeholders[i] + "}", placeholders[i + 1]);
        }
        return MINI.deserialize(text);
    }

    /** Parse an arbitrary MiniMessage string (used for GUI titles/lore). */
    public Component parse(String minimessage) {
        return MINI.deserialize(minimessage);
    }

    public void send(CommandSender to, String key, String... placeholders) {
        to.sendMessage(comp("prefix").append(comp(key, placeholders)));
    }

    public void sendRaw(CommandSender to, String minimessage) {
        to.sendMessage(parse(minimessage));
    }

    /** Prices are shown with up to 2 decimals, trailing zeros trimmed. */
    public static String money(double value) {
        if (value == Math.rint(value)) {
            return String.format("%,.0f", value);
        }
        return String.format("%,.2f", value);
    }
}
