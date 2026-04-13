# Character Export

A RuneLite plugin that writes your character's full state to local JSON files, for use by external tools.

## What it exports

| File | Trigger | Contents |
|------|---------|----------|
| `character.json` | Login, stat change | Skills (level, boosted level, XP), world, player name |
| `quests.json` | Login, varbit change | All quest states (NOT_STARTED / IN_PROGRESS / FINISHED) + counts |
| `diaries.json` | Login, varbit change, diary journal open | 12 areas × 4 tiers: completion flag, tasks-done count, named task list |
| `combat_achievements.json` | Login, varbit change | All 637 named combat tasks with individual completion, grouped by tier |
| `collection_log.json` | Collection log entry viewed | Incremental per-page accumulation, organised by tab |
| `bank.json` | Bank opened | Full bank contents with item names |
| `seed_vault.json` | Seed vault opened | Full seed vault contents |
| `inventory.json` | Inventory changed | Carried items |
| `equipment.json` | Equipment changed | Worn items |

### Notes on coverage

- **Diaries**: tier completion and tasks-done counts are always available. Named per-task lists require opening the diary journal in-game (all areas except Karamja, which has individual varbits). Widget-scraped data persists across sessions.
- **Collection log**: requires physically opening each entry in the collection log. Data accumulates incrementally as you browse and persists across sessions.
- **Quests**: only 3-state progress (not started / in progress / finished). Per-step progress is not exposed by the RuneLite API.

## Output location

All files are written under the RuneLite directory, separated by account:

```
.runelite/character-exporter/<Character Name>/
```

- Windows: `C:\Users\<user>\.runelite\character-exporter\<Character Name>\`
- Linux / WSL: `~/.runelite/character-exporter/<Character Name>/`

There is intentionally no configurable output path.

## Debug logging

Enable **Debug logging** in the plugin config to write additional observability files to `.runelite/character-exporter/`:

| File | Contents |
|------|----------|
| `exporter.log` | Per-event log: writes, failures, collection log scrape results |
| `status.json` | Current dataset state and readiness snapshot |
| `recent_events.json` | Rolling last-200 events |

The toggle takes effect immediately — no restart needed.

## Sidebar panel

The plugin adds a sidebar panel showing:

- Current account name and output folder
- Per-dataset status (last export time or a contextual hint)
- **Sync Available** — refreshes all datasets the client currently has in memory (skips bank/vault if not opened, skips collection log which requires in-game browsing)
- **Open Folder** — opens the active account directory in your file browser
- **Open File** dropdown — opens any existing export file directly

## Safety model

This plugin uses only standard RuneLite plugin APIs. It does not:

- intercept packets or sniff network traffic
- hook process memory
- automate gameplay
- write files outside the RuneLite directory

## Building

Requires JDK 11+, Gradle.

```bash
gradle build         # compile and test
gradle run           # launch RuneLite dev harness with the plugin loaded
gradle shadowJar     # produce a runnable fat jar
```

Dev harness uses `--developer-mode` so the plugin loads without hub verification.

## Support

[github.com/DZWNK/character-export/issues](https://github.com/DZWNK/character-export/issues)
