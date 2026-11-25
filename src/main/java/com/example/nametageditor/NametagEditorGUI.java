package com.example.nametageditor;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class NametagEditorGUI implements Listener {

    enum InputType { OPEN_BY_NAME, SET_NICK, SET_SKIN, SET_COLOR }

    record InputSession(InputType type, UUID target, int page) {}

    record TargetEntry(UUID uuid, String name, boolean online) {}

    abstract static class BaseHolder implements InventoryHolder {
        private Inventory inventory;

        void setInventory(Inventory inventory) { this.inventory = inventory; }

        @Override
        public Inventory getInventory() { return inventory; }
    }

    static final class MainHolder extends BaseHolder {}

    static final class PlayerListHolder extends BaseHolder {
        final int page;
        final List<TargetEntry> entries;

        PlayerListHolder(int page, List<TargetEntry> entries) {
            this.page = page;
            this.entries = entries;
        }
    }

    static final class EditorHolder extends BaseHolder {
        final UUID target;
        final int page; // player-list page we came from, or -1 for main menu

        EditorHolder(UUID target, int page) {
            this.target = target;
            this.page = page;
        }
    }

    static final class ColorHolder extends BaseHolder {
        final UUID target;
        final int page;
        final List<String> namedOrder = new ArrayList<>();
        final List<String> presetOrder = new ArrayList<>();

        ColorHolder(UUID target, int page) {
            this.target = target;
            this.page = page;
        }
    }

    private static final int PAGE_SIZE = 45;
    private static final Map<String, Material> WOOL = Map.ofEntries(
            Map.entry("black", Material.BLACK_WOOL), Map.entry("darkblue", Material.BLUE_WOOL),
            Map.entry("darkgreen", Material.GREEN_WOOL), Map.entry("darkaqua", Material.CYAN_WOOL),
            Map.entry("darkred", Material.RED_TERRACOTTA), Map.entry("darkpurple", Material.PURPLE_WOOL),
            Map.entry("gold", Material.ORANGE_WOOL), Map.entry("gray", Material.LIGHT_GRAY_WOOL),
            Map.entry("darkgray", Material.GRAY_WOOL), Map.entry("blue", Material.BLUE_CONCRETE),
            Map.entry("green", Material.LIME_WOOL), Map.entry("aqua", Material.LIGHT_BLUE_WOOL),
            Map.entry("red", Material.RED_WOOL), Map.entry("pink", Material.MAGENTA_WOOL),
            Map.entry("yellow", Material.YELLOW_WOOL), Map.entry("white", Material.WHITE_WOOL));
    private static final int STYLE_SLOT_START = 38; // 38..42 for the five styles

    private final NametagEditorPlugin plugin;
    private final NickManager nickManager;
    private final Map<UUID, InputSession> inputSessions = new ConcurrentHashMap<>();

    public NametagEditorGUI(NametagEditorPlugin plugin, NickManager nickManager) {
        this.plugin = plugin;
        this.nickManager = nickManager;
    }

    // ---- screens ----------------------------------------------------------------------------

    public void openMain(Player player) {
        MainHolder holder = new MainHolder();
        Inventory inv = Bukkit.createInventory(holder, 27, t("gui.main-title"));
        holder.setInventory(inv);
        inv.setItem(11, item(Material.NAME_TAG, "gui.main.self-title", "gui.main.self-lore"));
        if (plugin.canEditOthers(player)) {
            inv.setItem(15, item(Material.PLAYER_HEAD, "gui.main.manage-title", "gui.main.manage-lore"));
            inv.setItem(22, item(Material.WRITABLE_BOOK, "gui.main.by-name-title", "gui.main.by-name-lore"));
        } else {
            inv.setItem(15, item(Material.BARRIER, "gui.main.admin-only-title", "gui.main.admin-only-lore"));
        }
        player.openInventory(inv);
    }

    public void openPlayerList(Player player, int page) {
        List<TargetEntry> entries = buildTargetEntries();
        int maxPage = Math.max(0, (entries.size() - 1) / PAGE_SIZE);
        page = Math.max(0, Math.min(page, maxPage));
        PlayerListHolder holder = new PlayerListHolder(page, entries);
        Inventory inv = Bukkit.createInventory(holder, 54,
                t("gui.player-list-title", Map.of("page", String.valueOf(page + 1), "max", String.valueOf(maxPage + 1))));
        holder.setInventory(inv);
        int start = page * PAGE_SIZE;
        for (int i = 0; i < PAGE_SIZE && start + i < entries.size(); i++) {
            TargetEntry e = entries.get(start + i);
            inv.setItem(i, playerHead(e.uuid(), e.online(),
                    plugin.tr("gui.player-edit-name", Map.of("name", e.name())),
                    plugin.tr("gui.player-nick-line", Map.of("nick", showNick(e.uuid())))));
        }
        inv.setItem(45, item(Material.ARROW, "gui.button.back-title", "gui.button.back-main-lore"));
        if (page > 0) inv.setItem(48, item(Material.ARROW, "gui.button.prev-title", "gui.button.empty"));
        if (page < maxPage) inv.setItem(50, item(Material.ARROW, "gui.button.next-title", "gui.button.empty"));
        inv.setItem(53, item(Material.WRITABLE_BOOK, "gui.main.by-name-title", "gui.player-list.by-name-lore"));
        player.openInventory(inv);
    }

    public void openEditor(Player player, UUID target, int page) {
        OfflinePlayer op = Bukkit.getOfflinePlayer(target);
        String realName = op.getName() == null ? target.toString() : op.getName();
        NickManager.Identity id = nickManager.getIdentity(target);
        String nick = id == null || id.nickname().isBlank() ? plugin.tr("messages.none.nickname") : id.nickname();
        String skin = id == null || id.skinSource().isBlank() ? plugin.tr("messages.none.skin") : id.skinSource();
        String color = id == null || id.color().isBlank() ? plugin.tr("messages.none.color") : NameColor.describe(id.color());

        EditorHolder holder = new EditorHolder(target, page);
        Inventory inv = Bukkit.createInventory(holder, 45, t("gui.editor-title", Map.of("target", realName)));
        holder.setInventory(inv);
        inv.setItem(10, item(Material.NAME_TAG, "gui.editor.set-nick-title", "gui.editor.set-nick-lore"));
        inv.setItem(11, item(Material.BARRIER, "gui.editor.clear-nick-title", "gui.editor.clear-nick-lore"));
        inv.setItem(12, item(Material.MAGENTA_DYE, "gui.editor.color-title", "gui.editor.color-lore"));
        inv.setItem(13, item(Material.PLAYER_HEAD, "gui.editor.set-skin-title", "gui.editor.set-skin-lore"));
        inv.setItem(14, item(Material.FEATHER, "gui.editor.clear-skin-title", "gui.editor.clear-skin-lore"));

        ItemStack info = new ItemStack(Material.BOOK);
        ItemMeta meta = info.getItemMeta();
        meta.displayName(t("gui.editor.info-title"));
        meta.lore(List.of(
                t("gui.editor.info-name", Map.of("value", realName)),
                t("gui.editor.info-nick", Map.of("value", nick)),
                t("gui.editor.info-skin", Map.of("value", skin)),
                t("gui.editor.info-color", Map.of("value", color)),
                plain(plugin.tr("messages.preview")).append(nickManager.styledName(target, realName))));
        info.setItemMeta(meta);
        inv.setItem(16, info);

        inv.setItem(31, page >= 0
                ? item(Material.ARROW, "gui.editor.back-list-title", "gui.button.empty")
                : item(Material.ARROW, "gui.editor.back-main-title", "gui.button.empty"));
        player.openInventory(inv);
    }

    public void openColorPage(Player player, UUID target, int page) {
        OfflinePlayer op = Bukkit.getOfflinePlayer(target);
        String realName = op.getName() == null ? target.toString() : op.getName();
        String sample = nickManager.getVisibleName(target, realName);
        String current = nickManager.getColor(target);
        List<String> activeStyles = stylesOf(current);

        ColorHolder holder = new ColorHolder(target, page);
        Inventory inv = Bukkit.createInventory(holder, 54, t("gui.color-title", Map.of("target", realName)));
        holder.setInventory(inv);

        // Row 0-1: the 16 named colors
        int slot = 0;
        for (String name : NameColor.NAMED.keySet()) {
            ItemStack it = new ItemStack(WOOL.getOrDefault(name, Material.WHITE_WOOL));
            ItemMeta meta = it.getItemMeta();
            meta.displayName(plugin.nameColor().render(sample, NameColor.withStyles("named:" + name, activeStyles))
                    .decoration(TextDecoration.ITALIC, false));
            meta.lore(List.of(plain(name), t("gui.color.named-lore")));
            it.setItemMeta(meta);
            inv.setItem(slot++, it);
            holder.namedOrder.add(name);
        }

        // Row 2-3: gradient presets (rainbow first)
        slot = 18;
        List<String> presets = new ArrayList<>();
        presets.add("rainbow");
        presets.addAll(plugin.nameColor().presetNames());
        for (String preset : presets) {
            if (slot > 35) break;
            String spec = preset.equals("rainbow") ? "rainbow" : "preset:" + preset;
            ItemStack it = new ItemStack(Material.PAINTING);
            ItemMeta meta = it.getItemMeta();
            meta.displayName(plugin.nameColor().render(sample, NameColor.withStyles(spec, activeStyles))
                    .decoration(TextDecoration.ITALIC, false));
            meta.lore(List.of(plain(preset), t("gui.color.preset-lore")));
            if (current.split("\\|", 2)[0].equals(spec)) glow(meta);
            it.setItemMeta(meta);
            inv.setItem(slot++, it);
            holder.presetOrder.add(spec);
        }

        // Row 4: style toggles
        for (int i = 0; i < NameColor.STYLES.size(); i++) {
            String style = NameColor.STYLES.get(i);
            boolean on = activeStyles.contains(style);
            ItemStack it = new ItemStack(on ? Material.LIME_DYE : Material.GRAY_DYE);
            ItemMeta meta = it.getItemMeta();
            meta.displayName(plain(style + (on ? " ✔" : "")).color(on ? NamedTextColor.GREEN : NamedTextColor.GRAY));
            if (on) glow(meta);
            it.setItemMeta(meta);
            inv.setItem(STYLE_SLOT_START + i, it);
        }

        inv.setItem(45, item(Material.ARROW, "gui.color.back-title", "gui.button.empty"));
        inv.setItem(49, item(Material.WRITABLE_BOOK, "gui.color.custom-title", "gui.color.custom-lore"));
        inv.setItem(53, item(Material.BARRIER, "gui.color.clear-title", "gui.color.clear-lore"));
        player.openInventory(inv);
    }

    // ---- events -----------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        InputSession session = inputSessions.remove(player.getUniqueId());
        if (session == null) return;
        event.setCancelled(true);
        String text = PlainTextComponentSerializer.plainText().serialize(event.message()).trim();
        Bukkit.getScheduler().runTask(plugin, () -> handleInput(player, session, text));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        inputSessions.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof BaseHolder) event.setCancelled(true);
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        Inventory top = event.getView().getTopInventory();
        if (!(top.getHolder() instanceof BaseHolder holder)) return;
        event.setCancelled(true);
        if (event.getClickedInventory() == null || !event.getClickedInventory().equals(top)) return;
        int slot = event.getRawSlot();

        switch (holder) {
            case MainHolder ignored -> {
                if (slot == 11) {
                    if (!plugin.canUseSelf(player)) {
                        player.sendMessage(plain(plugin.tr("messages.error.no-permission")));
                        return;
                    }
                    openEditor(player, player.getUniqueId(), -1);
                } else if (slot == 15 && plugin.canEditOthers(player)) {
                    openPlayerList(player, 0);
                } else if (slot == 22 && plugin.canEditOthers(player)) {
                    beginInput(player, InputType.OPEN_BY_NAME, null, -1, "messages.prompt.open-by-name");
                }
            }
            case PlayerListHolder list -> {
                if (slot >= 0 && slot < PAGE_SIZE) {
                    int index = list.page * PAGE_SIZE + slot;
                    if (index < list.entries.size()) openEditor(player, list.entries.get(index).uuid(), list.page);
                } else if (slot == 45) {
                    openMain(player);
                } else if (slot == 48 && list.page > 0) {
                    openPlayerList(player, list.page - 1);
                } else if (slot == 50) {
                    openPlayerList(player, list.page + 1);
                } else if (slot == 53) {
                    beginInput(player, InputType.OPEN_BY_NAME, null, list.page, "messages.prompt.open-by-name");
                }
            }
            case EditorHolder editor -> handleEditorClick(player, editor, slot);
            case ColorHolder color -> handleColorClick(player, color, slot);
            default -> { }
        }
    }

    private void handleEditorClick(Player player, EditorHolder editor, int slot) {
        OfflinePlayer target = Bukkit.getOfflinePlayer(editor.target);
        if (!plugin.canEditTarget(player, target)) {
            player.sendMessage(plain(plugin.tr("messages.error.self-only-edit")));
            return;
        }
        switch (slot) {
            case 10 -> beginInput(player, InputType.SET_NICK, editor.target, editor.page, "messages.prompt.new-nick");
            case 11 -> {
                nickManager.clearNickname(target);
                player.sendMessage(plain(isSelf(player, target)
                        ? plugin.tr("messages.nick-cleared")
                        : plugin.tr("messages.nick-cleared-other", Map.of("player", nameOf(target)))));
                openEditor(player, editor.target, editor.page);
            }
            case 12 -> {
                if (!canColor(player, target)) {
                    player.sendMessage(plain(plugin.tr("messages.error.no-permission")));
                    return;
                }
                openColorPage(player, editor.target, editor.page);
            }
            case 13 -> beginInput(player, InputType.SET_SKIN, editor.target, editor.page, "messages.prompt.new-skin");
            case 14 -> {
                nickManager.clearSkin(target);
                player.sendMessage(plain(isSelf(player, target)
                        ? plugin.tr("messages.skin-cleared-self")
                        : plugin.tr("messages.skin-cleared", Map.of("player", nameOf(target)))));
                openEditor(player, editor.target, editor.page);
            }
            case 31 -> {
                if (editor.page >= 0) openPlayerList(player, editor.page);
                else openMain(player);
            }
            default -> { }
        }
    }

    private void handleColorClick(Player player, ColorHolder holder, int slot) {
        OfflinePlayer target = Bukkit.getOfflinePlayer(holder.target);
        if (!plugin.canEditTarget(player, target) || !canColor(player, target)) {
            player.sendMessage(plain(plugin.tr("messages.error.no-permission")));
            return;
        }
        String current = nickManager.getColor(holder.target);
        List<String> styles = stylesOf(current);
        String colorPart = current.isBlank() ? "" : current.split("\\|", 2)[0];

        if (slot >= 0 && slot < holder.namedOrder.size()) {
            applyColor(player, target, NameColor.withStyles("named:" + holder.namedOrder.get(slot), styles));
        } else if (slot >= 18 && slot < 18 + holder.presetOrder.size()) {
            applyColor(player, target, NameColor.withStyles(holder.presetOrder.get(slot - 18), styles));
        } else if (slot >= STYLE_SLOT_START && slot < STYLE_SLOT_START + NameColor.STYLES.size()) {
            String style = NameColor.STYLES.get(slot - STYLE_SLOT_START);
            List<String> next = new ArrayList<>(styles);
            if (!next.remove(style)) next.add(style);
            // Styles need a color to hang on; default to white.
            nickManager.setColor(target, NameColor.withStyles(colorPart.isBlank() ? "named:white" : colorPart, next));
        } else if (slot == 45) {
            openEditor(player, holder.target, holder.page);
            return;
        } else if (slot == 49) {
            beginInput(player, InputType.SET_COLOR, holder.target, holder.page, "messages.prompt.new-color");
            return;
        } else if (slot == 53) {
            nickManager.clearColor(target);
            player.sendMessage(plain(isSelf(player, target)
                    ? plugin.tr("messages.color-cleared-self")
                    : plugin.tr("messages.color-cleared-other", Map.of("player", nameOf(target)))));
        } else {
            return;
        }
        openColorPage(player, holder.target, holder.page);
    }

    private void applyColor(Player player, OfflinePlayer target, String spec) {
        nickManager.setColor(target, spec);
        Component msg = plain(isSelf(player, target)
                ? plugin.tr("messages.color-set-self", Map.of("color", NameColor.describe(spec)))
                : plugin.tr("messages.color-set-other", Map.of("player", nameOf(target), "color", NameColor.describe(spec))));
        player.sendMessage(msg.append(Component.text(" ")).append(nickManager.styledName(target.getUniqueId(), target.getName())));
    }

    private void beginInput(Player player, InputType type, UUID target, int page, String promptKey) {
        inputSessions.put(player.getUniqueId(), new InputSession(type, target, page));
        player.closeInventory();
        player.sendMessage(plain(plugin.tr(promptKey)).color(NamedTextColor.YELLOW));
    }

    private void handleInput(Player player, InputSession session, String text) {
        if (!player.isOnline()) return;
        if (text.equalsIgnoreCase("cancel")) {
            player.sendMessage(plain(plugin.tr("messages.input-cancelled")));
            reopenAfterInput(player, session);
            return;
        }
        if (session.type() == InputType.OPEN_BY_NAME) {
            OfflinePlayer target = plugin.resolveTargetByNameOrNick(text);
            if (target == null) {
                player.sendMessage(plain(plugin.tr("messages.error.player-not-found")));
                return;
            }
            if (!plugin.canEditTarget(player, target)) {
                player.sendMessage(plain(plugin.tr("messages.error.no-permission")));
                return;
            }
            openEditor(player, target.getUniqueId(), session.page());
            return;
        }

        OfflinePlayer target = Bukkit.getOfflinePlayer(session.target());
        if (!plugin.canEditTarget(player, target)) {
            player.sendMessage(plain(plugin.tr("messages.error.no-permission")));
            return;
        }
        try {
            switch (session.type()) {
                case SET_NICK -> {
                    if (text.equalsIgnoreCase("clear")) {
                        nickManager.clearNickname(target);
                        player.sendMessage(plain(plugin.tr("messages.nick-cleared")));
                    } else {
                        nickManager.setNickname(target, text);
                        player.sendMessage(plain(isSelf(player, target)
                                ? plugin.tr("messages.nick-set-self", Map.of("nickname", text))
                                : plugin.tr("messages.nick-set-other", Map.of("player", nameOf(target), "nickname", text))));
                    }
                }
                case SET_SKIN -> {
                    if (text.equalsIgnoreCase("clear")) {
                        nickManager.clearSkin(target);
                        player.sendMessage(plain(plugin.tr("messages.skin-cleared-self")));
                    } else {
                        nickManager.setSkinSource(target, text);
                        player.sendMessage(plain(isSelf(player, target)
                                ? plugin.tr("messages.skin-set-self", Map.of("skin", text))
                                : plugin.tr("messages.skin-set", Map.of("player", nameOf(target), "skin", text))));
                    }
                }
                case SET_COLOR -> {
                    if (!canColor(player, target)) {
                        player.sendMessage(plain(plugin.tr("messages.error.no-permission")));
                        return;
                    }
                    if (text.equalsIgnoreCase("clear")) {
                        nickManager.clearColor(target);
                        player.sendMessage(plain(plugin.tr("messages.color-cleared-self")));
                    } else {
                        String spec = plugin.parseColorArgs(Arrays.asList(text.split("[\\s,]+")), stylesOf(nickManager.getColor(target.getUniqueId())));
                        applyColor(player, target, spec);
                    }
                    openColorPage(player, session.target(), session.page());
                    return;
                }
                default -> { }
            }
        } catch (IllegalArgumentException e) {
            player.sendMessage(plain(e.getMessage()).color(NamedTextColor.RED));
            beginInput(player, session.type(), session.target(), session.page(), promptFor(session.type()));
            return;
        }
        openEditor(player, session.target(), session.page());
    }

    private void reopenAfterInput(Player player, InputSession session) {
        if (session.target() == null) return;
        if (session.type() == InputType.SET_COLOR) openColorPage(player, session.target(), session.page());
        else openEditor(player, session.target(), session.page());
    }

    private String promptFor(InputType type) {
        return switch (type) {
            case SET_NICK -> "messages.prompt.new-nick";
            case SET_SKIN -> "messages.prompt.new-skin";
            case SET_COLOR -> "messages.prompt.new-color";
            case OPEN_BY_NAME -> "messages.prompt.open-by-name";
        };
    }

    // ---- helpers ----------------------------------------------------------------------------

    private boolean isSelf(Player player, OfflinePlayer target) {
        return player.getUniqueId().equals(target.getUniqueId());
    }

    private boolean canColor(Player player, OfflinePlayer target) {
        if (isSelf(player, target)) {
            return player.hasPermission("nametageditor.color") || player.hasPermission("nametageditor.gradient");
        }
        return plugin.canEditOthers(player) || player.hasPermission("nametageditor.color.others");
    }

    private static List<String> stylesOf(String spec) {
        List<String> out = new ArrayList<>();
        if (spec == null) return out;
        String[] parts = spec.split("\\|", 2);
        if (parts.length < 2) return out;
        for (String s : parts[1].split(",")) {
            String n = NameColor.normalizeStyle(s.trim());
            if (n != null && !out.contains(n)) out.add(n);
        }
        return out;
    }

    private static String nameOf(OfflinePlayer p) {
        return p.getName() == null ? p.getUniqueId().toString() : p.getName();
    }

    private static void glow(ItemMeta meta) {
        meta.addEnchant(Enchantment.UNBREAKING, 1, true);
        meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
    }

    private ItemStack item(Material material, String titleKey, String... loreKeys) {
        ItemStack it = new ItemStack(material);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(t(titleKey));
        List<Component> lore = new ArrayList<>();
        for (String key : loreKeys) {
            String line = plugin.tr(key);
            if (!line.isEmpty() && !line.equals(key)) lore.add(plain(line));
        }
        meta.lore(lore);
        it.setItemMeta(meta);
        return it;
    }

    private ItemStack playerHead(UUID uuid, boolean online, String title, String nickLine) {
        ItemStack it = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) it.getItemMeta();
        meta.setOwningPlayer(Bukkit.getOfflinePlayer(uuid));
        meta.displayName(plain(title));
        meta.lore(List.of(plain(nickLine),
                plain(plugin.tr(online ? "gui.status.online" : "gui.status.offline"))
                        .color(online ? NamedTextColor.GREEN : NamedTextColor.GRAY)));
        it.setItemMeta(meta);
        return it;
    }

    private String showNick(UUID uuid) {
        String nick = nickManager.getNickname(uuid);
        return nick.isBlank() ? plugin.tr("messages.none.nick-short") : nick;
    }

    private static Component plain(String text) {
        return Component.text(text).decoration(TextDecoration.ITALIC, false);
    }

    private Component t(String key) {
        return plain(plugin.tr(key));
    }

    private Component t(String key, Map<String, String> placeholders) {
        return plain(plugin.tr(key, placeholders));
    }

    private List<TargetEntry> buildTargetEntries() {
        List<TargetEntry> list = new ArrayList<>();
        for (UUID uuid : nickManager.getKnownPlayerUuids()) {
            OfflinePlayer op = Bukkit.getOfflinePlayer(uuid);
            String name = op.getName() == null ? uuid.toString() : op.getName();
            list.add(new TargetEntry(uuid, name, Bukkit.getPlayer(uuid) != null));
        }
        list.sort((a, b) -> {
            if (a.online() != b.online()) return a.online() ? -1 : 1;
            return String.CASE_INSENSITIVE_ORDER.compare(a.name(), b.name());
        });
        return list;
    }
}
