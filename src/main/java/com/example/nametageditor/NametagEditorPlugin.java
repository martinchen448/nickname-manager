package com.example.nametageditor;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextReplacementConfig;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.server.TabCompleteEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public final class NametagEditorPlugin extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {

    private static final Pattern VALID_MC_NAME = Pattern.compile("^[A-Za-z0-9_]{1,16}$");

    private NickManager nickManager;
    private NametagEditorGUI gui;
    private LanguageManager languageManager;
    private NameColor nameColor;
    private NametagRenderer nametags;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        ensureDefaults();
        languageManager = new LanguageManager(this);
        languageManager.reload();
        nameColor = new NameColor(this);
        nameColor.loadPresets();
        nickManager = new NickManager(this);
        nickManager.load();
        nametags = new NametagRenderer(this);
        gui = new NametagEditorGUI(this, nickManager);

        var pm = getServer().getPluginManager();
        pm.registerEvents(this, this);
        pm.registerEvents(new CommandRewriteListener(this, nickManager), this);
        pm.registerEvents(gui, this);
        pm.registerEvents(nametags, this);
        pm.registerEvents(new ChatListener(this), this);

        for (String name : List.of("nametag", "nick", "namecolor", "gradient")) {
            PluginCommand cmd = getCommand(name);
            if (cmd != null) {
                cmd.setExecutor(this);
                cmd.setTabCompleter(this);
            }
        }

        nametags.start();
        // Supports /reload and plugin managers: re-apply to anyone already online.
        for (Player p : Bukkit.getOnlinePlayers()) nickManager.handleJoin(p);
    }

    @Override
    public void onDisable() {
        if (nametags != null) nametags.stop();
        if (nickManager != null) nickManager.save();
    }

    /** Adds keys that are new in this version to an existing config.yml without touching user values. */
    private void ensureDefaults() {
        try (InputStream in = getResource("config.yml")) {
            if (in == null) return;
            YamlConfiguration defaults = YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
            boolean changed = false;
            for (String key : defaults.getKeys(true)) {
                if (defaults.isConfigurationSection(key)) continue;
                if (!getConfig().contains(key, true)) {
                    getConfig().set(key, defaults.get(key));
                    changed = true;
                }
            }
            if (changed) saveConfig();
        } catch (Exception e) {
            getLogger().warning("Could not merge default config: " + e.getMessage());
        }
    }

    public NickManager nickManager() { return nickManager; }
    public NameColor nameColor() { return nameColor; }
    public NametagRenderer nametags() { return nametags; }

    public String tr(String key) {
        return languageManager == null ? key : languageManager.tr(key);
    }

    public String tr(String key, Map<String, String> placeholders) {
        return languageManager == null ? key : languageManager.tr(key, placeholders);
    }

    // ---- events -----------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        nickManager.handleJoin(player);
        if (getConfig().getBoolean("settings.join-quit-message-enabled", true)) {
            String format = getConfig().getString("settings.join-format", tr("messages.join-format"));
            event.joinMessage(formatWithName(format, nickManager.styledName(player)));
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        if (getConfig().getBoolean("settings.join-quit-message-enabled", true)) {
            String format = getConfig().getString("settings.quit-format", tr("messages.quit-format"));
            event.quitMessage(formatWithName(format, nickManager.styledName(player)));
        }
        nametags.handleQuit(player);
        nickManager.handleQuit(player);
    }

    private Component formatWithName(String format, Component name) {
        return Component.text(format, NamedTextColor.YELLOW)
                .replaceText(TextReplacementConfig.builder().matchLiteral("%name%").replacement(name).build());
    }

    /** Swaps real names for styled nicknames inside the (translatable) vanilla death message. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onDeath(PlayerDeathEvent event) {
        if (!getConfig().getBoolean("settings.use-nickname-in-death-message", true)) return;
        Component message = event.deathMessage();
        if (message == null) return;
        Map<String, Component> replacements = new HashMap<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (nickManager.hasCustomName(p)) replacements.put(p.getName(), nickManager.styledName(p));
        }
        if (replacements.isEmpty()) return;
        String alternation = replacements.keySet().stream()
                .sorted(Comparator.comparingInt(String::length).reversed())
                .map(Pattern::quote)
                .collect(Collectors.joining("|"));
        Pattern pattern = Pattern.compile("(?<![A-Za-z0-9_])(?:" + alternation + ")(?![A-Za-z0-9_])");
        event.deathMessage(message.replaceText(TextReplacementConfig.builder()
                .match(pattern)
                .replacement((result, builder) -> replacements.get(result.group()))
                .build()));
    }

    /** Shows nicknames instead of real names in command tab completion (commands accept nicknames via rewrite). */
    @EventHandler
    public void onTabCompleteEvent(TabCompleteEvent event) {
        if (!getConfig().getBoolean("settings.nicknames-in-tab-completion", true)) return;
        if (!getConfig().getBoolean("settings.command-rewrite-enabled", true)) return;
        if (!(event.getSender() instanceof Player) || !event.isCommand()) return;
        List<String> completions = event.getCompletions();
        if (completions.isEmpty()) return;
        Set<String> out = new LinkedHashSet<>();
        for (String c : completions) {
            String nick = findNickForRealName(c);
            out.add(nick.isBlank() ? c : nick);
        }
        event.setCompletions(new ArrayList<>(out));
    }

    // ---- commands ---------------------------------------------------------------------------

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        switch (command.getName().toLowerCase(Locale.ROOT)) {
            case "nick" -> handleNick(sender, args);
            case "nametag" -> handleNametag(sender, args);
            case "namecolor" -> handleNameColor(sender, args, false);
            case "gradient" -> handleNameColor(sender, args, true);
            default -> { return false; }
        }
        return true;
    }

    private void handleNick(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            msg(sender, tr("messages.error.players-only-nick"));
            return;
        }
        if (!canUseSelf(player)) {
            msg(sender, tr("messages.error.no-permission"));
            return;
        }
        if (args.length != 1) {
            msg(sender, tr("messages.usage.nick"));
            return;
        }
        try {
            if (args[0].equalsIgnoreCase("clear")) {
                nickManager.clearNickname(player);
                msg(sender, tr("messages.nick-cleared"));
            } else {
                nickManager.setNickname(player, args[0]);
                msg(sender, tr("messages.nick-set-self", Map.of("nickname", args[0])));
            }
        } catch (IllegalArgumentException e) {
            error(sender, e.getMessage());
        }
    }

    private void handleNametag(CommandSender sender, String[] args) {
        if (args.length == 0) {
            msg(sender, tr("messages.usage.nametag"));
            return;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        try {
            switch (sub) {
                case "reload" -> {
                    if (!canEditOthers(sender)) { msg(sender, tr("messages.error.no-permission")); return; }
                    reloadConfig();
                    ensureDefaults();
                    languageManager.reload();
                    nameColor.loadPresets();
                    nickManager.load();
                    nickManager.refreshAll();
                    msg(sender, tr("messages.reloaded"));
                }
                case "gui" -> {
                    if (!(sender instanceof Player player)) { msg(sender, tr("messages.error.players-only-gui")); return; }
                    if (!canUseSelf(player)) { msg(sender, tr("messages.error.no-permission")); return; }
                    gui.openMain(player);
                }
                case "set" -> {
                    if (args.length < 3) { msg(sender, tr("messages.usage.set")); return; }
                    OfflinePlayer target = requireTarget(sender, args[1]);
                    if (target == null) return;
                    if (!canEditTarget(sender, target)) { msg(sender, tr("messages.error.self-only-edit")); return; }
                    nickManager.setNickname(target, args[2]);
                    msg(sender, tr("messages.nick-set-other", Map.of("player", nameOf(target), "nickname", args[2])));
                }
                case "clear" -> {
                    if (args.length < 2) { msg(sender, tr("messages.usage.clear")); return; }
                    OfflinePlayer target = requireTarget(sender, args[1]);
                    if (target == null) return;
                    if (!canEditTarget(sender, target)) { msg(sender, tr("messages.error.self-only-edit")); return; }
                    nickManager.clearNickname(target);
                    msg(sender, tr("messages.nick-cleared-other", Map.of("player", nameOf(target))));
                }
                case "skin" -> {
                    if (args.length < 3) { msg(sender, tr("messages.usage.skin")); return; }
                    OfflinePlayer target = requireTarget(sender, args[1]);
                    if (target == null) return;
                    if (!canEditTarget(sender, target)) { msg(sender, tr("messages.error.self-only-edit")); return; }
                    if (args[2].equalsIgnoreCase("clear")) {
                        nickManager.clearSkin(target);
                        msg(sender, tr("messages.skin-cleared", Map.of("player", nameOf(target))));
                    } else {
                        nickManager.setSkinSource(target, args[2]);
                        msg(sender, tr("messages.skin-set", Map.of("player", nameOf(target), "skin", args[2])));
                    }
                }
                case "color" -> {
                    if (args.length < 3) { msg(sender, tr("messages.usage.color")); return; }
                    OfflinePlayer target = requireTarget(sender, args[1]);
                    if (target == null) return;
                    if (!canEditTarget(sender, target)) { msg(sender, tr("messages.error.self-only-edit")); return; }
                    if (args[2].equalsIgnoreCase("clear")) {
                        nickManager.clearColor(target);
                        msg(sender, tr("messages.color-cleared-other", Map.of("player", nameOf(target))));
                    } else {
                        String spec = parseColorArgs(Arrays.asList(args).subList(2, args.length), List.of());
                        nickManager.setColor(target, spec);
                        sendColorSet(sender, target, spec);
                    }
                }
                case "info" -> {
                    if (args.length < 2) { msg(sender, tr("messages.usage.info")); return; }
                    OfflinePlayer target = requireTarget(sender, args[1]);
                    if (target == null) return;
                    if (!canEditTarget(sender, target)) { msg(sender, tr("messages.error.self-only-view")); return; }
                    NickManager.Identity id = nickManager.getIdentity(target.getUniqueId());
                    String nick = id == null || id.nickname().isBlank() ? tr("messages.none.nickname") : id.nickname();
                    String skin = id == null || id.skinSource().isBlank() ? tr("messages.none.skin") : id.skinSource();
                    String color = id == null || id.color().isBlank() ? tr("messages.none.color") : NameColor.describe(id.color());
                    msg(sender, tr("messages.info.player", Map.of("player", nameOf(target))));
                    msg(sender, tr("messages.info.nickname", Map.of("value", nick)));
                    msg(sender, tr("messages.info.skin", Map.of("value", skin)));
                    msg(sender, tr("messages.info.color", Map.of("value", color)));
                    sender.sendMessage(Component.text(tr("messages.preview"))
                            .append(nickManager.styledName(target.getUniqueId(), target.getName())));
                }
                default -> msg(sender, tr("messages.error.unknown-subcommand"));
            }
        } catch (IllegalArgumentException e) {
            error(sender, e.getMessage());
        }
    }

    /**
     * /namecolor <color|#hex|preset|rainbow|clear> [styles...] [player]
     * /gradient <#hex1> <#hex2> [#hex...] [styles...] [player] | <preset> [player] | list
     */
    private void handleNameColor(CommandSender sender, String[] args, boolean gradientOnly) {
        String perm = gradientOnly ? "nametageditor.gradient" : "nametageditor.color";
        String usage = gradientOnly ? "messages.usage.gradient" : "messages.usage.namecolor";
        if (args.length == 0) {
            msg(sender, tr(usage));
            return;
        }
        if (gradientOnly && args[0].equalsIgnoreCase("list")) {
            listPresets(sender);
            return;
        }

        List<String> tokens = new ArrayList<>(Arrays.asList(args));
        OfflinePlayer target = null;
        // A trailing token that is neither a color nor a style is the target player.
        if (tokens.size() >= 2) {
            String last = tokens.getLast();
            if (!isColorOrStyleToken(last)) {
                target = requireTarget(sender, last);
                if (target == null) return;
                tokens.removeLast();
            }
        }
        if (target == null) {
            if (!(sender instanceof Player p)) {
                msg(sender, tr(usage));
                return;
            }
            target = p;
        }

        boolean self = sender instanceof Player p && p.getUniqueId().equals(target.getUniqueId());
        if (self ? !sender.hasPermission(perm) : !(canEditOthers(sender) || sender.hasPermission(perm + ".others"))) {
            msg(sender, tr("messages.error.no-permission"));
            return;
        }

        try {
            if (tokens.getFirst().equalsIgnoreCase("clear") || tokens.getFirst().equalsIgnoreCase("reset")) {
                nickManager.clearColor(target);
                msg(sender, self ? tr("messages.color-cleared-self")
                        : tr("messages.color-cleared-other", Map.of("player", nameOf(target))));
                return;
            }
            String spec = parseColorArgs(tokens, List.of());
            if (gradientOnly) {
                String color = spec.split("\\|", 2)[0];
                if (!(color.startsWith("gradient:") || color.startsWith("preset:") || color.equals("rainbow"))) {
                    throw new IllegalArgumentException(tr("messages.error.gradient-needs-two"));
                }
            }
            nickManager.setColor(target, spec);
            sendColorSet(sender, target, spec);
        } catch (IllegalArgumentException e) {
            error(sender, e.getMessage());
        }
    }

    private void sendColorSet(CommandSender sender, OfflinePlayer target, String spec) {
        boolean self = sender instanceof Player p && p.getUniqueId().equals(target.getUniqueId());
        String text = self
                ? tr("messages.color-set-self", Map.of("color", NameColor.describe(spec)))
                : tr("messages.color-set-other", Map.of("player", nameOf(target), "color", NameColor.describe(spec)));
        sender.sendMessage(Component.text(text + " ").append(nickManager.styledName(target.getUniqueId(), target.getName())));
    }

    private void listPresets(CommandSender sender) {
        String sample = sender instanceof Player p ? nickManager.getVisibleName(p) : "Sample";
        Component line = Component.text(tr("messages.gradient-list", Map.of("presets", "")));
        boolean first = true;
        for (String preset : nameColor.presetNames()) {
            if (!first) line = line.append(Component.text(", ", NamedTextColor.GRAY));
            line = line.append(nameColor.render(preset, "preset:" + preset));
            first = false;
        }
        line = line.append(Component.text(", ", NamedTextColor.GRAY)).append(nameColor.render("rainbow", "rainbow"));
        sender.sendMessage(line);
        sender.sendMessage(Component.text(tr("messages.preview"))
                .append(nameColor.render(sample, "preset:" + nameColor.presetNames().stream().findFirst().orElse(""))));
    }

    private boolean isColorOrStyleToken(String token) {
        return token.equalsIgnoreCase("clear") || token.equalsIgnoreCase("reset")
                || NameColor.normalizeStyle(token) != null
                || nameColor.parseColorToken(token) != null
                || token.contains("|");
    }

    /**
     * Turns tokens like ["#ff0000", "#0000ff", "bold"], ["sunset"], ["red", "italic"] or a canonical
     * spec into a canonical color spec. Throws IllegalArgumentException with a translated message.
     */
    public String parseColorArgs(List<String> tokens, List<String> defaultStyles) {
        List<String> hexes = new ArrayList<>();
        List<String> styles = new ArrayList<>();
        String single = null;
        for (String raw : tokens) {
            if (raw == null || raw.isBlank()) continue;
            String token = raw.trim();
            if (token.contains("|")) {
                String normalized = nameColor.normalizeSpec(token);
                if (normalized == null) throw new IllegalArgumentException(tr("messages.error.invalid-color", Map.of("value", token)));
                return normalized;
            }
            String style = NameColor.normalizeStyle(token);
            if (style != null) {
                if (!styles.contains(style)) styles.add(style);
                continue;
            }
            String hex = NameColor.parseHex(token);
            if (hex != null) {
                hexes.add(hex);
                continue;
            }
            if (token.startsWith("#") || token.startsWith("&#")) {
                throw new IllegalArgumentException(tr("messages.error.invalid-hex", Map.of("value", token)));
            }
            String color = nameColor.parseColorToken(token);
            if (color == null) {
                String key = token.toLowerCase(Locale.ROOT).startsWith("preset:") ? "messages.error.unknown-preset" : "messages.error.invalid-color";
                throw new IllegalArgumentException(tr(key, Map.of("value", token)));
            }
            if (color.startsWith("gradient:")) {
                hexes.addAll(Arrays.asList(color.substring(9).split(":")));
            } else {
                single = color;
            }
        }
        if (styles.isEmpty() && defaultStyles != null) styles.addAll(defaultStyles);
        String colorPart;
        if (hexes.size() >= 2) colorPart = NameColor.gradientSpec(hexes);
        else if (hexes.size() == 1 && single == null) colorPart = "hex:" + hexes.getFirst();
        else if (single != null) colorPart = single;
        else if (!styles.isEmpty()) colorPart = "named:white";
        else throw new IllegalArgumentException(tr("messages.error.invalid-color", Map.of("value", String.join(" ", tokens))));
        return NameColor.withStyles(colorPart, styles);
    }

    // ---- tab completion ---------------------------------------------------------------------

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        String cmd = command.getName().toLowerCase(Locale.ROOT);
        String current = args.length == 0 ? "" : args[args.length - 1];
        List<String> options = new ArrayList<>();
        switch (cmd) {
            case "nick" -> {
                if (args.length == 1) options.add("clear");
            }
            case "nametag" -> {
                if (args.length == 1) {
                    options.addAll(List.of("gui", "info"));
                    if (canEditOthers(sender)) options.addAll(List.of("set", "clear", "skin", "color", "reload"));
                } else if (args.length == 2 && List.of("set", "clear", "skin", "color", "info").contains(args[0].toLowerCase(Locale.ROOT))) {
                    options.addAll(onlineTargetSuggestions());
                } else if (args.length == 3 && args[0].equalsIgnoreCase("skin")) {
                    options.add("clear");
                    Bukkit.getOnlinePlayers().forEach(p -> options.add(p.getName()));
                } else if (args.length >= 3 && args[0].equalsIgnoreCase("color")) {
                    if (args.length == 3) options.add("clear");
                    options.addAll(colorSuggestions(true));
                    options.addAll(NameColor.STYLES);
                }
            }
            case "namecolor" -> {
                if (args.length == 1) {
                    options.add("clear");
                    options.addAll(colorSuggestions(true));
                } else {
                    options.addAll(NameColor.STYLES);
                    if (canEditOthers(sender) || sender.hasPermission("nametageditor.color.others")) options.addAll(onlineTargetSuggestions());
                }
            }
            case "gradient" -> {
                if (args.length == 1) {
                    options.add("list");
                    options.add("clear");
                    options.addAll(nameColor.presetNames());
                    options.add("rainbow");
                    options.add("#");
                } else {
                    options.add("#");
                    options.addAll(NameColor.STYLES);
                    if (canEditOthers(sender) || sender.hasPermission("nametageditor.gradient.others")) options.addAll(onlineTargetSuggestions());
                }
            }
            default -> { }
        }
        return filter(current, options);
    }

    private List<String> colorSuggestions(boolean includePresets) {
        List<String> out = new ArrayList<>(NameColor.NAMED.keySet());
        out.add("rainbow");
        if (includePresets) out.addAll(nameColor.presetNames());
        out.add("#");
        return out;
    }

    private List<String> filter(String prefix, List<String> options) {
        String p = prefix.toLowerCase(Locale.ROOT);
        return options.stream().distinct().filter(o -> o.toLowerCase(Locale.ROOT).startsWith(p)).collect(Collectors.toList());
    }

    private List<String> onlineTargetSuggestions() {
        List<String> out = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            out.add(p.getName());
            String nick = nickManager.getNickname(p.getUniqueId());
            if (!nick.isBlank()) out.add(nick);
        }
        return out;
    }

    // ---- targets & permissions --------------------------------------------------------------

    private OfflinePlayer requireTarget(CommandSender sender, String name) {
        OfflinePlayer target = resolveTargetByNameOrNick(name);
        if (target == null) msg(sender, tr("messages.error.player-not-found"));
        return target;
    }

    public OfflinePlayer resolveTargetByNameOrNick(String input) {
        if (input == null || input.isBlank()) return null;
        Player exact = Bukkit.getPlayerExact(input);
        if (exact != null) return exact;
        Player byNick = nickManager.findOnlineByNickname(input);
        if (byNick != null) return byNick;
        UUID known = nickManager.findKnownByNickname(input);
        if (known != null) return Bukkit.getOfflinePlayer(known);
        OfflinePlayer cached = Bukkit.getOfflinePlayerIfCached(input);
        if (cached != null) return cached;
        if (!VALID_MC_NAME.matcher(input).matches()) return null;
        // Allows editing players who have never joined (may look the name up).
        return Bukkit.getOfflinePlayer(input);
    }

    private String findNickForRealName(String realName) {
        Player p = Bukkit.getPlayerExact(realName);
        return p == null ? "" : nickManager.getNickname(p.getUniqueId());
    }

    public boolean canEditOthers(CommandSender sender) {
        return !(sender instanceof Player) || sender.hasPermission("nametageditor.admin");
    }

    public boolean canUseSelf(Player player) {
        return player.hasPermission("nametageditor.self") || player.hasPermission("nametageditor.admin");
    }

    public boolean canEditTarget(CommandSender sender, OfflinePlayer target) {
        if (canEditOthers(sender)) return true;
        return sender instanceof Player p && p.getUniqueId().equals(target.getUniqueId()) && canUseSelf(p);
    }

    private static String nameOf(OfflinePlayer p) {
        return p.getName() == null ? p.getUniqueId().toString() : p.getName();
    }

    private static void msg(CommandSender sender, String text) {
        sender.sendMessage(Component.text(text));
    }

    private static void error(CommandSender sender, String text) {
        sender.sendMessage(Component.text(text == null ? "Error" : text, NamedTextColor.RED));
    }
}
