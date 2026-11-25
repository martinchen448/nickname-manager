package com.example.nametageditor;

import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Loads lang/<code>.yml from the data folder, merging in keys missing vs. the bundled copy; English is the fallback. */
public final class LanguageManager {

    private static final Set<String> SUPPORTED_CODES = Set.of(
            "en", "zh", "es", "hi", "ar", "fr", "bn", "pt", "ru", "ur",
            "id", "de", "ja", "tr", "vi", "ko", "it", "nl", "pl", "sv");

    private final NametagEditorPlugin plugin;
    private YamlConfiguration fallback;
    private YamlConfiguration active;

    public LanguageManager(NametagEditorPlugin plugin) {
        this.plugin = plugin;
    }

    public void reload() {
        ensureLanguageFileSynced("en");
        fallback = loadBundledLanguage("en");
        String code = resolveConfiguredLanguageCode();
        ensureLanguageFileSynced(code);
        active = loadLang(code);
    }

    public String tr(String key) {
        String value = active == null ? null : active.getString(key);
        if (value == null && fallback != null) value = fallback.getString(key);
        return value == null ? key : value;
    }

    public String tr(String key, Map<String, String> placeholders) {
        String value = tr(key);
        for (Map.Entry<String, String> e : placeholders.entrySet()) {
            value = value.replace("%" + e.getKey() + "%", e.getValue() == null ? "" : e.getValue());
        }
        return value;
    }

    private String resolveConfiguredLanguageCode() {
        var cfg = plugin.getConfig();
        String raw = firstNonBlank(
                cfg.getString("language"),
                cfg.getString("settings.language"),
                cfg.getString("messages.language"),
                cfg.getString("lang"),
                cfg.getString("locale"),
                "en");
        String code = raw.trim().toLowerCase(Locale.ROOT);
        int sep = code.indexOf('_') >= 0 ? code.indexOf('_') : code.indexOf('-');
        if (sep > 0) code = code.substring(0, sep);
        return SUPPORTED_CODES.contains(code) ? code : "en";
    }

    private String firstNonBlank(String... values) {
        for (String v : values) if (v != null && !v.isBlank()) return v;
        return "en";
    }

    private void ensureLanguageFileSynced(String code) {
        File file = new File(plugin.getDataFolder(), "lang/" + code + ".yml");
        if (!file.exists()) {
            if (plugin.getResource("lang/" + code + ".yml") != null) {
                plugin.saveResource("lang/" + code + ".yml", false);
            }
            return;
        }
        mergeMissingKeysFromResource(code, file);
    }

    private YamlConfiguration loadLang(String code) {
        File file = new File(plugin.getDataFolder(), "lang/" + code + ".yml");
        if (file.exists()) return YamlConfiguration.loadConfiguration(file);
        YamlConfiguration bundled = loadBundledLanguage(code);
        return bundled != null ? bundled : new YamlConfiguration();
    }

    private void mergeMissingKeysFromResource(String code, File file) {
        YamlConfiguration bundled = loadBundledLanguage(code);
        if (bundled == null) return;
        YamlConfiguration onDisk = YamlConfiguration.loadConfiguration(file);
        boolean changed = false;
        for (String key : bundled.getKeys(true)) {
            if (bundled.isConfigurationSection(key)) continue;
            if (!onDisk.contains(key)) {
                onDisk.set(key, bundled.get(key));
                changed = true;
            }
        }
        if (changed) {
            try {
                onDisk.save(file);
            } catch (IOException e) {
                plugin.getLogger().warning("Could not update " + file.getName() + ": " + e.getMessage());
            }
        }
    }

    private YamlConfiguration loadBundledLanguage(String code) {
        try (InputStream in = plugin.getResource("lang/" + code + ".yml")) {
            if (in == null) return null;
            try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                return YamlConfiguration.loadConfiguration(reader);
            }
        } catch (IOException e) {
            return null;
        }
    }
}
