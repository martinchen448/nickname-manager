package com.example.nametageditor;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Name color specs, stored as plain strings in identities.yml.
 * <p>
 * Canonical form: {@code <color>[|style,style...]} where color is one of
 * {@code named:<name>}, {@code hex:#RRGGBB}, {@code gradient:#RRGGBB:#RRGGBB[:...]},
 * {@code preset:<name>} or {@code rainbow}. Styles: bold, italic, underline, strike, magic.
 */
public final class NameColor {

    public static final Map<String, NamedTextColor> NAMED;
    public static final List<String> STYLES = List.of("bold", "italic", "underline", "strike", "magic");
    private static final Map<String, String> STYLE_TAGS = Map.of(
            "bold", "bold", "italic", "italic", "underline", "underlined",
            "strike", "strikethrough", "magic", "obfuscated");
    private static final Map<String, String> STYLE_ALIASES = Map.of(
            "underlined", "underline", "strikethrough", "strike", "obfuscated", "magic", "italics", "italic");
    private static final Pattern HEX = Pattern.compile("^(?:&?#)?([0-9A-Fa-f]{6})$");

    static {
        Map<String, NamedTextColor> m = new LinkedHashMap<>();
        m.put("black", NamedTextColor.BLACK);
        m.put("darkblue", NamedTextColor.DARK_BLUE);
        m.put("darkgreen", NamedTextColor.DARK_GREEN);
        m.put("darkaqua", NamedTextColor.DARK_AQUA);
        m.put("darkred", NamedTextColor.DARK_RED);
        m.put("darkpurple", NamedTextColor.DARK_PURPLE);
        m.put("gold", NamedTextColor.GOLD);
        m.put("gray", NamedTextColor.GRAY);
        m.put("darkgray", NamedTextColor.DARK_GRAY);
        m.put("blue", NamedTextColor.BLUE);
        m.put("green", NamedTextColor.GREEN);
        m.put("aqua", NamedTextColor.AQUA);
        m.put("red", NamedTextColor.RED);
        m.put("pink", NamedTextColor.LIGHT_PURPLE);
        m.put("yellow", NamedTextColor.YELLOW);
        m.put("white", NamedTextColor.WHITE);
        NAMED = Collections.unmodifiableMap(m);
    }

    private final NametagEditorPlugin plugin;
    private final Map<String, List<String>> presets = new LinkedHashMap<>();

    public NameColor(NametagEditorPlugin plugin) {
        this.plugin = plugin;
    }

    public void loadPresets() {
        presets.clear();
        File file = new File(plugin.getDataFolder(), "gradients.yml");
        if (!file.exists()) plugin.saveResource("gradients.yml", false);
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection section = yml.getConfigurationSection("presets");
        if (section == null) return;
        for (String key : section.getKeys(false)) {
            List<String> colors = new ArrayList<>();
            if (section.isList(key)) {
                for (String c : section.getStringList(key)) {
                    String hex = parseHex(c);
                    if (hex != null) colors.add(hex);
                }
            } else if (section.isConfigurationSection(key)) {
                // PedestriaNameColor style: color1/color2/...
                ConfigurationSection sub = section.getConfigurationSection(key);
                for (String k : sub.getKeys(false)) {
                    String hex = parseHex(sub.getString(k, ""));
                    if (hex != null) colors.add(hex);
                }
            }
            if (colors.size() >= 2) {
                presets.put(key.toLowerCase(Locale.ROOT), List.copyOf(colors));
            } else {
                plugin.getLogger().warning("gradients.yml: preset '" + key + "' needs at least two valid hex colors.");
            }
        }
    }

    public Set<String> presetNames() {
        return Collections.unmodifiableSet(presets.keySet());
    }

    public List<String> presetColors(String name) {
        return presets.get(name.toLowerCase(Locale.ROOT));
    }

    /** Returns "#RRGGBB" (upper case) or null. Accepts RRGGBB, #RRGGBB and &#RRGGBB. */
    public static String parseHex(String input) {
        if (input == null) return null;
        var m = HEX.matcher(input.trim());
        return m.matches() ? "#" + m.group(1).toUpperCase(Locale.ROOT) : null;
    }

    public static String normalizeStyle(String input) {
        if (input == null) return null;
        String s = input.toLowerCase(Locale.ROOT);
        s = STYLE_ALIASES.getOrDefault(s, s);
        return STYLES.contains(s) ? s : null;
    }

    /**
     * Parses a single color token (no styles): named color, hex, preset name, "rainbow",
     * or an already-canonical spec. Returns the canonical color part, or null if unknown.
     */
    public String parseColorToken(String token) {
        if (token == null || token.isBlank()) return null;
        String t = token.trim();
        String tl = t.toLowerCase(Locale.ROOT);
        if (tl.startsWith("named:") || tl.startsWith("preset:")) {
            return validCanonicalColor(tl) ? tl : null;
        }
        if (tl.startsWith("hex:")) {
            String hex = parseHex(t.substring(4));
            return hex == null ? null : "hex:" + hex;
        }
        if (tl.startsWith("gradient:")) {
            List<String> cols = new ArrayList<>();
            for (String c : t.substring(9).split(":")) {
                String hex = parseHex(c);
                if (hex == null) return null;
                cols.add(hex);
            }
            return cols.size() >= 2 ? gradientSpec(cols) : null;
        }
        String lower = tl.replace("_", "").replace("-", "");
        if (lower.equals("rainbow")) return "rainbow";
        if (NAMED.containsKey(lower)) return "named:" + lower;
        if (lower.equals("lightpurple") || lower.equals("magenta")) return "named:pink";
        if (lower.equals("darkgrey")) return "named:darkgray";
        if (lower.equals("grey")) return "named:gray";
        String hex = parseHex(t);
        if (hex != null) return "hex:" + hex;
        if (presets.containsKey(t.toLowerCase(Locale.ROOT))) return "preset:" + t.toLowerCase(Locale.ROOT);
        return null;
    }

    public static String gradientSpec(List<String> hexColors) {
        return "gradient:" + String.join(":", hexColors);
    }

    public static String withStyles(String colorPart, List<String> styles) {
        if (styles == null || styles.isEmpty()) return colorPart;
        return colorPart + "|" + String.join(",", styles);
    }

    /** Validates/normalizes a full spec string (as typed in /nametag color or stored). Null if invalid. */
    public String normalizeSpec(String spec) {
        if (spec == null || spec.isBlank()) return null;
        String[] parts = spec.trim().split("\\|", 2);
        String color = parseColorToken(parts[0]);
        if (color == null) return null;
        List<String> styles = new ArrayList<>();
        if (parts.length > 1) {
            for (String s : parts[1].split(",")) {
                String n = normalizeStyle(s.trim());
                if (n != null && !styles.contains(n)) styles.add(n);
            }
        }
        return withStyles(color, styles);
    }

    private boolean validCanonicalColor(String lower) {
        if (lower.startsWith("named:")) return NAMED.containsKey(lower.substring(6));
        return lower.startsWith("preset:") && presets.containsKey(lower.substring(7));
    }

    /** Renders the given plain name with the spec. Unknown/empty spec renders a plain (unstyled) name. */
    public Component render(String name, String spec) {
        if (spec == null || spec.isBlank()) return Component.text(name);
        String[] parts = spec.split("\\|", 2);
        String color = parts[0];
        String lower = color.toLowerCase(Locale.ROOT);

        String open;
        String close;
        if (lower.startsWith("named:") && NAMED.containsKey(lower.substring(6))) {
            String tag = NAMED.get(lower.substring(6)).toString();
            open = "<" + tag + ">";
            close = "</" + tag + ">";
        } else if (lower.startsWith("hex:") && parseHex(color.substring(4)) != null) {
            open = "<color:" + parseHex(color.substring(4)) + ">";
            close = "</color>";
        } else if (lower.startsWith("gradient:")) {
            List<String> cols = new ArrayList<>();
            for (String c : color.substring(9).split(":")) {
                String hex = parseHex(c);
                if (hex != null) cols.add(hex);
            }
            if (cols.size() < 2) return Component.text(name);
            open = "<gradient:" + String.join(":", cols) + ">";
            close = "</gradient>";
        } else if (lower.startsWith("preset:")) {
            List<String> cols = presets.get(lower.substring(7));
            if (cols == null) return Component.text(name);
            open = "<gradient:" + String.join(":", cols) + ">";
            close = "</gradient>";
        } else if (lower.equals("rainbow")) {
            open = "<rainbow>";
            close = "</rainbow>";
        } else {
            return Component.text(name);
        }

        StringBuilder styleOpen = new StringBuilder();
        StringBuilder styleClose = new StringBuilder();
        if (parts.length > 1) {
            List<String> styles = new ArrayList<>();
            for (String s : parts[1].split(",")) {
                String n = normalizeStyle(s.trim());
                if (n != null) styles.add(n);
            }
            for (String s : styles) styleOpen.append('<').append(STYLE_TAGS.get(s)).append('>');
            for (int i = styles.size() - 1; i >= 0; i--) styleClose.append("</").append(STYLE_TAGS.get(styles.get(i))).append('>');
        }

        String escaped = MiniMessage.miniMessage().escapeTags(name);
        return MiniMessage.miniMessage().deserialize(open + styleOpen + escaped + styleClose + close);
    }

    /** Human-readable form for messages / GUI. */
    public static String describe(String spec) {
        if (spec == null || spec.isBlank()) return "";
        String[] parts = spec.split("\\|", 2);
        String c = parts[0];
        String lower = c.toLowerCase(Locale.ROOT);
        String desc;
        if (lower.startsWith("named:")) desc = lower.substring(6);
        else if (lower.startsWith("hex:")) desc = c.substring(4);
        else if (lower.startsWith("gradient:")) desc = "gradient " + c.substring(9).replace(":", " → ");
        else if (lower.startsWith("preset:")) desc = "preset " + lower.substring(7);
        else desc = lower;
        if (parts.length > 1 && !parts[1].isBlank()) desc += " (" + parts[1].replace(",", ", ") + ")";
        return desc;
    }
}
