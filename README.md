# NametagEditor

A Paper plugin for **Minecraft 26.2** that adds nicknames, name colors and gradients, and skin overrides.

Nicknames (with their colors) appear in:
- chat
- the tab list
- the nametag above the player's head
- join and quit messages
- death messages

## Commands

| Command | Description |
|---|---|
| `/nick <name\|clear>` | Set or clear your own nickname |
| `/namecolor <color\|#hex\|preset\|rainbow\|clear> [styles…] [player]` | Set a name color |
| `/gradient <#hex1> <#hex2> [#hex…] [styles…] [player]` | Apply a custom gradient |
| `/gradient <preset> [player]` / `/gradient list` | Apply or list gradient presets |
| `/nametag gui` | Open the editor GUI |
| `/nametag set <player> <nickname>` | Set a player's nickname |
| `/nametag clear <player>` | Clear a player's nickname |
| `/nametag color <player> <spec\|clear>` | Set or clear a player's name color |
| `/nametag skin <player> <skinName\|clear>` | Set or clear a skin source |
| `/nametag info <player>` | Show stored nickname, skin and color |
| `/nametag reload` | Reload the config, languages, presets and data |

**Colors:** the 16 named colors (`red`, `gold`, `darkaqua`, `pink`, …), `#RRGGBB`, `rainbow`, or a preset.
**Styles:** `bold`, `italic`, `underline`, `strike`, `magic`.

**Presets:** defined in `plugins/NametagEditor/gradients.yml`. The plugin ships with green-purple, sunset, ocean, fire, mint, candy, gold, ice, lavender, toxic, blood and galaxy.

## Permissions

| Permission | Default | Grants |
|---|---|---|
| `nametageditor.self` | everyone | `/nick` and the GUI for yourself |
| `nametageditor.color` / `nametageditor.gradient` | everyone | Color your own name |
| `nametageditor.color.others` / `nametageditor.gradient.others` | op | Color other players' names |
| `nametageditor.admin` | op | Everything, including editing others and reload |

## Notes

- The above-head name is a text display entity that follows the player. The vanilla nametag is hidden by putting the player in the main-scoreboard team `nte_hidden`. A player who is already in another team keeps that team, so their vanilla tag stays visible.
- Skin overrides are off by default. To use them, set `settings.skin-override-enabled: true`.
- Chat formatting can be turned off with `settings.chat-format-enabled: false` if another plugin formats chat.

## Building

Requires **JDK 25** and Maven.

```sh
mvn package
# -> target/NametagEditor-2.0.jar
```
