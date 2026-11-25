package com.example.nametageditor;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Lets players type nicknames in other plugins' commands: "/tpa Nick" becomes "/tpa RealName". */
public final class CommandRewriteListener implements Listener {

    private static final Set<String> OWN_COMMANDS = Set.of(
            "nick", "nametag", "nametageditor", "namecolor", "nickcolor", "ncolor", "gradient");

    private final NametagEditorPlugin plugin;
    private final NickManager nickManager;

    public CommandRewriteListener(NametagEditorPlugin plugin, NickManager nickManager) {
        this.plugin = plugin;
        this.nickManager = nickManager;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        if (!plugin.getConfig().getBoolean("settings.command-rewrite-enabled", true)) return;
        String message = event.getMessage();
        if (message == null || message.isBlank() || !message.startsWith("/")) return;
        String[] parts = message.substring(1).split("\\s+");
        if (parts.length < 2) return;
        String label = parts[0].toLowerCase(Locale.ROOT);
        int colon = label.indexOf(':');
        if (colon >= 0) label = label.substring(colon + 1);
        if (OWN_COMMANDS.contains(label)) return;

        List<String> out = new ArrayList<>(parts.length);
        out.add(parts[0]);
        boolean changed = false;
        for (int i = 1; i < parts.length; i++) {
            String arg = parts[i];
            if (arg.startsWith("@") || arg.startsWith("-")) {
                out.add(arg);
                continue;
            }
            Player byNick = nickManager.findOnlineByNickname(arg);
            if (byNick != null && !byNick.getName().equals(arg)) {
                out.add(byNick.getName());
                changed = true;
            } else {
                out.add(arg);
            }
        }
        if (changed) event.setMessage("/" + String.join(" ", out));
    }
}
