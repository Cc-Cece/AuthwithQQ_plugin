package com.cccece.authwithqq.util;

import com.cccece.authwithqq.AuthWithQqPlugin;
import java.util.Map;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.configuration.file.FileConfiguration;

public class MessageManager {
    private final AuthWithQqPlugin plugin;
    private final LegacyComponentSerializer serializer = LegacyComponentSerializer.legacyAmpersand();

    public MessageManager(AuthWithQqPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Retrieves a message from the config.yml by path.
     *
     * @param path The path to the message in config.yml (e.g., "messages.auth.no-permission").
     * @return The formatted message component.
     */
    public Component getMessage(String path) {
        String message = plugin.getConfig().getString(path, "&cMessage not found: " + path);
        return serializer.deserialize(message);
    }

    /**
     * Retrieves a message from the config.yml by path and replaces placeholders.
     *
     * @param path The path to the message in config.yml.
     * @param placeholders A map of placeholders (e.g., "%player%") to their replacement values.
     * @return The formatted message component.
     */
    public Component getMessage(String path, Map<String, String> placeholders) {
        String message = plugin.getConfig().getString(path, "&cMessage not found: " + path);
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            message = message.replace(entry.getKey(), entry.getValue());
        }
        return serializer.deserialize(message);
    }

    /**
     * Retrieves a message from the config.yml by path, falls back to a default value, and replaces placeholders.
     *
     * @param path The path to the message in config.yml.
     * @param defaultValue The default value if the path is not found in config.
     * @param placeholders A map of placeholders (e.g., "%player%") to their replacement values.
     * @return The formatted message component.
     */
    public Component getMessage(String path, String defaultValue, Map<String, String> placeholders) {
        String message = plugin.getConfig().getString(path, defaultValue);
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            message = message.replace(entry.getKey(), entry.getValue());
        }
        return serializer.deserialize(message);
    }
}
