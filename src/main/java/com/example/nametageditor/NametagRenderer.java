package com.example.nametageditor;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;
import org.bukkit.util.BoundingBox;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Shows the styled nickname above each player's head with a {@link TextDisplay} and hides the
 * vanilla nametag through a scoreboard team. Players ignore customName, so this is the only
 * packet-free way to change the above-head name.
 * <p>
 * The display follows the player every tick (with client interpolation) instead of riding them:
 * on Paper 26.x a player with a passenger cannot be teleported to another world by plugins
 * (CraftPlayer#teleport returns false before any event), which would break /spawn, /home etc.
 */
public final class NametagRenderer implements Listener {

    private static final String HIDE_TEAM = "nte_hidden";
    private static final byte SNEAK_OPACITY = (byte) 0x7F;
    private static final double SNAP_DISTANCE_SQ = 8 * 8;

    private final NametagEditorPlugin plugin;
    private final NamespacedKey markerKey;
    private final Map<UUID, TextDisplay> displays = new ConcurrentHashMap<>();
    private BukkitTask followTask;
    private BukkitTask syncTask;

    public NametagRenderer(NametagEditorPlugin plugin) {
        this.plugin = plugin;
        this.markerKey = new NamespacedKey(plugin, "nametag");
    }

    private boolean enabled() {
        return plugin.getConfig().getBoolean("settings.custom-nametag-enabled", true);
    }

    public void start() {
        removeStrayDisplays();
        followTask = Bukkit.getScheduler().runTaskTimer(plugin, this::followAll, 1L, 1L);
        syncTask = Bukkit.getScheduler().runTaskTimer(plugin, this::syncAll, 20L, 10L);
        for (Player p : Bukkit.getOnlinePlayers()) update(p);
    }

    public void stop() {
        if (followTask != null) followTask.cancel();
        if (syncTask != null) syncTask.cancel();
        followTask = syncTask = null;
        for (UUID id : Set.copyOf(displays.keySet())) removeDisplay(id);
        Team team = hideTeam(false);
        if (team != null) {
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (team.hasEntity(p)) team.removeEntity(p);
            }
        }
    }

    /** Called whenever a player's name changes or their state may need a fresh display. */
    public void update(Player player) {
        if (!player.isOnline()) return;
        if (!enabled()) {
            removeDisplay(player.getUniqueId());
            showVanillaTag(player);
            return;
        }
        if (shouldHide(player)) {
            removeDisplay(player.getUniqueId());
            return;
        }
        hideVanillaTag(player);
        TextDisplay display = displays.get(player.getUniqueId());
        if (display == null || !display.isValid() || !display.getWorld().equals(player.getWorld())) {
            removeDisplay(player.getUniqueId());
            display = spawnDisplay(player);
        }
        display.text(plugin.nickManager().styledName(player));
        applySneakState(display, player.isSneaking());
        syncViewers(player, display);
    }

    public void handleQuit(Player player) {
        removeDisplay(player.getUniqueId());
        showVanillaTag(player);
    }

    private boolean shouldHide(Player player) {
        return player.isDead()
                || player.getGameMode() == GameMode.SPECTATOR
                || player.hasPotionEffect(PotionEffectType.INVISIBILITY)
                || player.isInvisible();
    }

    /** Above the hitbox, so it drops when sneaking/swimming/crawling like the vanilla tag. */
    private Location tagLocation(Player player) {
        BoundingBox box = player.getBoundingBox();
        double offset = plugin.getConfig().getDouble("settings.nametag-y-offset", 0.3);
        Location feet = player.getLocation();
        return new Location(player.getWorld(), feet.getX(), box.getMaxY() + offset, feet.getZ());
    }

    private TextDisplay spawnDisplay(Player player) {
        boolean background = plugin.getConfig().getBoolean("settings.nametag-background", true);
        boolean shadow = plugin.getConfig().getBoolean("settings.nametag-shadow", false);
        World world = player.getWorld();
        TextDisplay display = world.spawn(tagLocation(player), TextDisplay.class, d -> {
            d.setPersistent(false);
            d.setBillboard(Display.Billboard.CENTER);
            d.setDefaultBackground(background);
            if (!background) d.setBackgroundColor(org.bukkit.Color.fromARGB(0, 0, 0, 0));
            d.setShadowed(shadow);
            d.setSeeThrough(true);
            d.setAlignment(TextDisplay.TextAlignment.CENTER);
            d.setTeleportDuration(1);
            d.getPersistentDataContainer().set(markerKey, PersistentDataType.STRING, player.getUniqueId().toString());
            d.text(plugin.nickManager().styledName(player));
        });
        // The owner never sees their own tag, like vanilla.
        player.hideEntity(plugin, display);
        displays.put(player.getUniqueId(), display);
        return display;
    }

    private void applySneakState(TextDisplay display, boolean sneaking) {
        display.setSeeThrough(!sneaking);
        display.setTextOpacity(sneaking ? SNEAK_OPACITY : (byte) -1);
    }

    /** Viewers that cannot see the player (vanish plugins) must not see the tag either. */
    private void syncViewers(Player owner, TextDisplay display) {
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            if (viewer.equals(owner)) continue;
            boolean canSeeOwner = viewer.canSee(owner);
            boolean canSeeTag = viewer.canSee(display);
            if (canSeeOwner && !canSeeTag) viewer.showEntity(plugin, display);
            else if (!canSeeOwner && canSeeTag) viewer.hideEntity(plugin, display);
        }
    }

    private void followAll() {
        for (Map.Entry<UUID, TextDisplay> e : displays.entrySet()) {
            Player p = Bukkit.getPlayer(e.getKey());
            TextDisplay d = e.getValue();
            if (p == null || !d.isValid()) continue;
            if (!d.getWorld().equals(p.getWorld())) {
                update(p); // respawns in the new world
                continue;
            }
            Location target = tagLocation(p);
            Location current = d.getLocation();
            double distSq = current.distanceSquared(target);
            if (distSq < 1.0E-6) continue;
            int wanted = distSq > SNAP_DISTANCE_SQ ? 0 : 1; // snap on teleports, glide otherwise
            if (d.getTeleportDuration() != wanted) d.setTeleportDuration(wanted);
            d.teleport(target);
        }
    }

    private void syncAll() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!enabled()) {
                if (displays.containsKey(p.getUniqueId())) update(p);
                continue;
            }
            TextDisplay d = displays.get(p.getUniqueId());
            boolean hide = shouldHide(p);
            if (hide && d != null) {
                removeDisplay(p.getUniqueId());
            } else if (!hide && (d == null || !d.isValid())) {
                update(p);
            } else if (d != null) {
                syncViewers(p, d);
            }
        }
    }

    private void removeDisplay(UUID owner) {
        TextDisplay d = displays.remove(owner);
        if (d != null && d.isValid()) d.remove();
    }

    private void removeStrayDisplays() {
        for (World world : Bukkit.getWorlds()) {
            for (TextDisplay d : world.getEntitiesByClass(TextDisplay.class)) {
                if (d.getPersistentDataContainer().has(markerKey, PersistentDataType.STRING)) d.remove();
            }
        }
    }

    private Team hideTeam(boolean create) {
        Scoreboard board = Bukkit.getScoreboardManager().getMainScoreboard();
        Team team = board.getTeam(HIDE_TEAM);
        if (team == null && create) {
            team = board.registerNewTeam(HIDE_TEAM);
            team.setOption(Team.Option.NAME_TAG_VISIBILITY, Team.OptionStatus.NEVER);
        }
        return team;
    }

    private void hideVanillaTag(Player player) {
        if (!plugin.getConfig().getBoolean("settings.hide-vanilla-nametag", true)) {
            showVanillaTag(player);
            return;
        }
        Scoreboard board = Bukkit.getScoreboardManager().getMainScoreboard();
        Team current = board.getEntityTeam(player);
        if (current != null && !current.getName().equals(HIDE_TEAM)) return; // don't steal players from other teams
        Team team = hideTeam(true);
        if (!team.hasEntity(player)) team.addEntity(player);
    }

    private void showVanillaTag(Player player) {
        Team team = hideTeam(false);
        if (team != null && team.hasEntity(player)) team.removeEntity(player);
    }

    // ---- events -----------------------------------------------------------------------------

    private void later(Player player, long ticks) {
        UUID id = player.getUniqueId();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Player p = Bukkit.getPlayer(id);
            if (p != null) update(p);
        }, ticks);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        removeDisplay(event.getPlayer().getUniqueId());
        later(event.getPlayer(), 1L);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDeath(org.bukkit.event.entity.PlayerDeathEvent event) {
        removeDisplay(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent event) {
        later(event.getPlayer(), 1L);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGameMode(PlayerGameModeChangeEvent event) {
        later(event.getPlayer(), 1L);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSneak(PlayerToggleSneakEvent event) {
        TextDisplay d = displays.get(event.getPlayer().getUniqueId());
        if (d != null && d.isValid()) applySneakState(d, event.isSneaking());
    }
}
