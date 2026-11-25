package com.example.nametageditor;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

/** Renders chat with the styled nickname. GUI chat-input capture runs earlier and cancels the event. */
public final class ChatListener implements Listener {

    private final NametagEditorPlugin plugin;

    public ChatListener(NametagEditorPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        if (!plugin.getConfig().getBoolean("settings.chat-format-enabled", true)) return;
        String format = plugin.getConfig().getString("settings.chat-format", "\\<<name>> <message>");
        if (format == null || format.isBlank()) return;
        Component name = plugin.nickManager().styledName(event.getPlayer());
        event.renderer((source, sourceDisplayName, message, viewer) ->
                MiniMessage.miniMessage().deserialize(format,
                        Placeholder.component("name", name),
                        Placeholder.component("message", message)));
    }
}
