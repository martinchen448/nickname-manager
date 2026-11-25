package com.example.nametageditor;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

public final class NickManager {

    public static final String NICK_METADATA_KEY = "nametageditor:nickname";

    /** Stored per player in identities.yml. Blank strings mean "not set". */
    public record Identity(String nickname, String skinSource, String color) {
        public Identity {
            nickname = nickname == null ? "" : nickname;
            skinSource = skinSource == null ? "" : skinSource;
            color = color == null ? "" : color;
        }

        boolean isEmpty() {
            return nickname.isBlank() && skinSource.isBlank() && color.isBlank();
        }

        Identity withNickname(String v) { return new Identity(v, skinSource, color); }
        Identity withSkinSource(String v) { return new Identity(nickname, v, color); }
        Identity withColor(String v) { return new Identity(nickname, skinSource, v); }
    }

    private static final Identity EMPTY = new Identity("", "", "");

    private final NametagEditorPlugin plugin;
    private final File dataFile;
    // Read from the async chat thread, so must be thread-safe.
    private final Map<UUID, Identity> identities = new ConcurrentHashMap<>();
    private final Map<UUID, PlayerProfile> originalProfiles = new ConcurrentHashMap<>();

    public NickManager(NametagEditorPlugin plugin) {
        this.plugin = plugin;
        this.dataFile = new File(plugin.getDataFolder(), "identities.yml");
    }

    public void load() {
        identities.clear();
        if (!plugin.getDataFolder().exists()) plugin.getDataFolder().mkdirs();
        if (!dataFile.exists()) return;
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(dataFile);
        ConfigurationSection players = yml.getConfigurationSection("players");
        if (players == null) return;
        for (String key : players.getKeys(false)) {
            UUID uuid;
            try {
                uuid = UUID.fromString(key);
            } catch (IllegalArgumentException e) {
                continue;
            }
            String base = "players." + key;
            Identity id = new Identity(
                    yml.getString(base + ".nickname", ""),
                    yml.getString(base + ".skin-source", ""),
                    yml.getString(base + ".color", ""));
            if (!id.isEmpty()) identities.put(uuid, id);
        }
    }

    public void save() {
        YamlConfiguration yml = new YamlConfiguration();
        for (Map.Entry<UUID, Identity> e : identities.entrySet()) {
            String base = "players." + e.getKey();
            yml.set(base + ".nickname", e.getValue().nickname());
            yml.set(base + ".skin-source", e.getValue().skinSource());
            if (!e.getValue().color().isBlank()) yml.set(base + ".color", e.getValue().color());
        }
        if (!plugin.getDataFolder().exists()) plugin.getDataFolder().mkdirs();
        try {
            yml.save(dataFile);
        } catch (IOException e) {
            plugin.getLogger().warning("Could not save identities.yml: " + e.getMessage());
        }
    }

    public Identity getIdentity(UUID uuid) {
        return identities.get(uuid);
    }

    public Set<UUID> getKnownPlayerUuids() {
        Set<UUID> set = new HashSet<>(identities.keySet());
        for (Player p : Bukkit.getOnlinePlayers()) set.add(p.getUniqueId());
        return set;
    }

    public void handleJoin(Player player) {
        originalProfiles.putIfAbsent(player.getUniqueId(), player.getPlayerProfile());
        applyIdentityVisuals(player);
        applySkinOverride(player);
    }

    public void handleQuit(Player player) {
        originalProfiles.remove(player.getUniqueId());
    }

    /** Re-applies visuals for everyone online (after reload). */
    public void refreshAll() {
        for (Player p : Bukkit.getOnlinePlayers()) applyIdentityVisuals(p);
    }

    public String getNickname(UUID uuid) {
        Identity id = identities.get(uuid);
        return id == null || id.nickname().isBlank() ? "" : id.nickname();
    }

    public String getColor(UUID uuid) {
        Identity id = identities.get(uuid);
        return id == null ? "" : id.color();
    }

    /** Personal color, or the configured default color. */
    public String getEffectiveColor(UUID uuid) {
        String own = getColor(uuid);
        if (!own.isBlank()) return own;
        String def = plugin.getConfig().getString("settings.default-name-color", "");
        if (def == null || def.isBlank()) return "";
        String normalized = plugin.nameColor().normalizeSpec(def);
        return normalized == null ? "" : normalized;
    }

    public String getVisibleName(Player player) {
        if (player == null) return "";
        String nick = getNickname(player.getUniqueId());
        return nick.isBlank() ? player.getName() : nick;
    }

    public String getVisibleName(UUID uuid, String realName) {
        String nick = getNickname(uuid);
        return nick.isBlank() ? (realName == null ? "" : realName) : nick;
    }

    /** Nickname (or real name) rendered with the player's color/gradient. */
    public Component styledName(Player player) {
        return plugin.nameColor().render(getVisibleName(player), getEffectiveColor(player.getUniqueId()));
    }

    public Component styledName(UUID uuid, String realName) {
        return plugin.nameColor().render(getVisibleName(uuid, realName), getEffectiveColor(uuid));
    }

    /** True if the player's shown name differs from the vanilla one (nick or color). */
    public boolean hasCustomName(Player player) {
        return !getNickname(player.getUniqueId()).isBlank() || !getEffectiveColor(player.getUniqueId()).isBlank();
    }

    public void setNickname(OfflinePlayer target, String nickname) {
        validateNickname(nickname);
        if (!plugin.getConfig().getBoolean("settings.allow-duplicate-nicknames", true)) {
            for (Map.Entry<UUID, Identity> e : identities.entrySet()) {
                if (e.getKey().equals(target.getUniqueId())) continue;
                if (e.getValue().nickname().equalsIgnoreCase(nickname)) {
                    throw new IllegalArgumentException(plugin.tr("messages.error.nickname-taken"));
                }
            }
        }
        update(target, identities.getOrDefault(target.getUniqueId(), EMPTY).withNickname(nickname));
        Player online = target.getPlayer();
        if (online != null) applyIdentityVisuals(online);
    }

    public void clearNickname(OfflinePlayer target) {
        Identity id = identities.get(target.getUniqueId());
        if (id != null) update(target, id.withNickname(""));
        Player online = target.getPlayer();
        if (online != null) applyIdentityVisuals(online);
    }

    /** @param spec canonical spec from {@link NameColor}; blank clears. */
    public void setColor(OfflinePlayer target, String spec) {
        update(target, identities.getOrDefault(target.getUniqueId(), EMPTY).withColor(spec == null ? "" : spec));
        Player online = target.getPlayer();
        if (online != null) applyIdentityVisuals(online);
    }

    public void clearColor(OfflinePlayer target) {
        setColor(target, "");
    }

    public void setSkinSource(OfflinePlayer target, String skinSource) {
        if (skinSource == null || skinSource.isBlank()) {
            throw new IllegalArgumentException(plugin.tr("messages.error.skin-empty"));
        }
        update(target, identities.getOrDefault(target.getUniqueId(), EMPTY).withSkinSource(skinSource.trim()));
        Player online = target.getPlayer();
        if (online != null) {
            applySkinOverride(online);
            applyIdentityVisuals(online);
        }
    }

    public void clearSkin(OfflinePlayer target) {
        Identity id = identities.get(target.getUniqueId());
        if (id != null) update(target, id.withSkinSource(""));
        Player online = target.getPlayer();
        if (online != null) {
            resetSkin(online);
            applyIdentityVisuals(online);
        }
    }

    private void update(OfflinePlayer target, Identity id) {
        if (id.isEmpty()) identities.remove(target.getUniqueId());
        else identities.put(target.getUniqueId(), id);
        save();
    }

    public Player findOnlineByNickname(String nickname) {
        if (nickname == null || nickname.isBlank()) return null;
        for (Player p : Bukkit.getOnlinePlayers()) {
            String nick = getNickname(p.getUniqueId());
            if (!nick.isBlank() && nick.equalsIgnoreCase(nickname)) return p;
        }
        return null;
    }

    /** Any known (online or stored) player whose nickname matches. */
    public UUID findKnownByNickname(String nickname) {
        if (nickname == null || nickname.isBlank()) return null;
        for (Map.Entry<UUID, Identity> e : identities.entrySet()) {
            if (e.getValue().nickname().equalsIgnoreCase(nickname)) return e.getKey();
        }
        return null;
    }

    private void validateNickname(String nickname) {
        if (nickname == null || nickname.isBlank()) {
            throw new IllegalArgumentException(plugin.tr("messages.error.nickname-empty"));
        }
        int max = Math.max(1, plugin.getConfig().getInt("settings.max-nickname-length", 16));
        if (nickname.length() > max) {
            throw new IllegalArgumentException(plugin.tr("messages.error.nickname-too-long", Map.of("max", String.valueOf(max))));
        }
        String regex = plugin.getConfig().getString("settings.nickname-pattern", "^[A-Za-z0-9_]+$");
        Pattern pattern;
        try {
            pattern = Pattern.compile(regex);
        } catch (PatternSyntaxException e) {
            plugin.getLogger().warning("Invalid settings.nickname-pattern, using default: " + e.getMessage());
            pattern = Pattern.compile("^[A-Za-z0-9_]+$");
        }
        if (!pattern.matcher(nickname).matches()) {
            throw new IllegalArgumentException(plugin.tr("messages.error.nickname-invalid-chars"));
        }
    }

    /** Legacy metadata (deprecated API) kept so other plugins reading it keep working. */
    @SuppressWarnings("deprecation")
    private void setNickMetadata(Player player, String nick) {
        try {
            player.removeMetadata(NICK_METADATA_KEY, plugin);
            if (!nick.isBlank()) player.setMetadata(NICK_METADATA_KEY, new FixedMetadataValue(plugin, nick));
        } catch (LinkageError | UnsupportedOperationException ignored) {
            // Metadata API removed in a future version; nothing else depends on it.
        }
    }

    public void applyIdentityVisuals(Player player) {
        setNickMetadata(player, getNickname(player.getUniqueId()));

        Component styled = styledName(player);
        player.displayName(styled);
        player.playerListName(hasCustomName(player) ? styled : null);
        plugin.nametags().update(player);
        triggerCustomizableRanksRefresh();
    }

    private void applySkinOverride(Player player) {
        if (!plugin.getConfig().getBoolean("settings.skin-override-enabled", false)) return;
        Identity id = identities.get(player.getUniqueId());
        if (id == null || id.skinSource().isBlank()) return;
        String source = id.skinSource().trim();

        Player sourceOnline = Bukkit.getPlayerExact(source);
        if (sourceOnline != null) {
            applyTextures(player, sourceOnline.getPlayerProfile());
            return;
        }
        PlayerProfile lookup = Bukkit.createProfile(source);
        UUID targetId = player.getUniqueId();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            boolean ok = lookup.complete(true);
            Bukkit.getScheduler().runTask(plugin, () -> {
                Player p = Bukkit.getPlayer(targetId);
                if (p == null || !p.isOnline()) return;
                if (!ok || !lookup.hasTextures()) {
                    plugin.getLogger().warning("Could not fetch skin for '" + source + "'.");
                    return;
                }
                applyTextures(p, lookup);
            });
        });
    }

    /** Copies only the textures property so the player keeps their own UUID and name. */
    private void applyTextures(Player player, PlayerProfile source) {
        PlayerProfile profile = player.getPlayerProfile();
        profile.removeProperty("textures");
        for (ProfileProperty prop : source.getProperties()) {
            if (prop.getName().equals("textures")) profile.setProperty(prop);
        }
        player.setPlayerProfile(profile);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline()) plugin.nametags().update(player);
        }, 2L);
    }

    private void resetSkin(Player player) {
        PlayerProfile original = originalProfiles.get(player.getUniqueId());
        if (original == null) return;
        PlayerProfile profile = player.getPlayerProfile();
        profile.removeProperty("textures");
        for (ProfileProperty prop : original.getProperties()) {
            if (prop.getName().equals("textures")) profile.setProperty(prop);
        }
        player.setPlayerProfile(profile);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline()) plugin.nametags().update(player);
        }, 2L);
    }

    private void triggerCustomizableRanksRefresh() {
        if (!plugin.getConfig().getBoolean("settings.refresh-customizableranks", true)) return;
        Plugin ranks = Bukkit.getPluginManager().getPlugin("CustomizableRanks");
        if (ranks == null || !ranks.isEnabled()) return;
        Bukkit.getScheduler().runTask(plugin, () -> {
            try {
                ranks.getClass().getMethod("refreshAllDisplays").invoke(ranks);
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                // Optional integration.
            }
        });
    }
}
