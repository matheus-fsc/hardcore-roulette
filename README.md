# HardcoreRoulette

**English** | [Português (pt-BR)](README.pt-BR.md)

![Paper](https://img.shields.io/badge/Paper-26.3-blue) ![Java](https://img.shields.io/badge/Java-25-orange) ![Version](https://img.shields.io/badge/version-1.4.0-green) ![License](https://img.shields.io/badge/license-MIT-lightgrey) ![Status](https://img.shields.io/badge/status-beta-orange)

> **Beta.** HardcoreRoulette is in active development: expect changes and rough edges. It is fully open source (MIT) and community ideas are welcome. Suggest features or report bugs in the [issues](https://github.com/matheus-fsc/hardcore-roulette/issues/new/choose).


A Paper plugin for **group hardcore**: when someone dies, the world resets for everyone. It ships with pre-generated backup worlds, seed voting, a party mode with levels and a set of tools for small servers among friends. Zombie hordes live in the companion plugin [SmartHordes](https://github.com/matheus-fsc/smarthordes).

> Built for **Paper 26.3** (Java 25). In-game text is in Brazilian Portuguese (pt-BR).

## Features

### World reset
- **Voting mode**: someone died, a 15 minute countdown starts (or an instant reset once everyone votes). `/vote` opens a virtual chest with the candidate seeds (spawn biome, votes).
- **Lobotomy mode**: someone died, everyone goes straight to a random world. The next world is picked ahead of time and only that one is pre-rendered.
- **Party mode**: worlds become levels (World #1, #2, ...) with fixed seeds. Everyone starts at #1; when someone in a party dies, **only that party** moves on together. Several parties can share the same level.
- **Pre-generated backup worlds** (spawn plus optional [Chunky](https://modrinth.com/plugin/chunky)) so resets are instant.
- Nether and End **per world** (portals linked to the run's world).
- Full player reset: inventory, ender chest, XP, effects, advancements; offline players are reset when they join.
- **8 second grace period** after a reset (no stored fall damage from the old world, no items leaking between worlds).

### Players
- `/ranking`: podium for most deaths, fewest deaths plus play time, and most damage in a single life.
- `/dia` and automatic **Day N: Morning/Afternoon/Night** announcements with each player's time alive.
- `/coord`: coordinates saved on the server per run, private or public, with distance and a direction arrow (`coords.enabled: false` hands this over to a HUD plugin such as [DirectionHUD](https://modrinth.com/plugin/directionhud)).
- World tag in the Tab list: `[W#3] Nick` (party) or `[#12] Nick`.
- Skill plugins (for example AuraSkills) reset **only for whoever died**, through configurable commands.
- **Nether portal omen**: lighting a portal brings lightning, rain and instant night.
- **Quick Flask**: potion plus copper nugget makes a weaker potion you drink in half a second.

### Companion plugins
- **[SmartHordes](https://github.com/matheus-fsc/smarthordes)**: zombie hordes that dig, build and surround players, announced in the afternoon. It follows the current run's world through the API below.
- **[Classforge](https://github.com/matheus-fsc/classforge)**: level-based skills tied to each life, with party XP sharing.

### Infrastructure
- `/migrar [minutes]`: countdown and hand-off of the server to another machine, through a `migrate.flag` file an external launcher watches.
- Bedrock whitelist (Geyser/Floodgate): `/bwhitelist add <gamertag>`.
- Login screen for offline-mode servers (`auth`). With [AuthMe](https://modrinth.com/plugin/authmereloaded) installed it turns itself off, and Bedrock players (Floodgate) are logged in automatically through AuthMe's API.

## Commands

| Command | Who | Description |
|---|---|---|
| `/vote [n]` | everyone | Vote for the next seed |
| `/ranking` (`/podio`, `/metricas`) | everyone | Statistics podium |
| `/dia` | everyone | Day, period and time alive |
| `/coord [salvar\|publico\|lista\|mostrar\|apagar] <name>` | everyone | Coordinates |
| `/party [convidar <nick>\|aceitar\|sair\|lista]` | everyone | Party mode |
| `/hcstatus` | everyone | Run status |
| `/hcmodo <votacao\|lobotomia\|party>` | op | Switch mode |
| `/hcreset` | op | Force a reset or vote |
| `/migrar [min\|cancelar]` | op | Migrate to the fallback machine |
| `/bwhitelist <add\|remove> <gamertag>` | op | Bedrock whitelist |
| `/hcreport` | op | Create a report to attach to an issue |

## Configuration (`plugins/HardcoreRoulette/config.yml`)

On first start the plugin writes a commented [config.yml](src/main/resources/config.yml) with every default. New keys from updates are added automatically without touching what you changed.

| Section | What it controls |
|---------|------------------|
| `server-name` | Name shown in the `/ranking` title |
| `worlds.prefix` | Run world prefix (default `hc_`; only change it before the first run) |
| `mode`, `pregen-radius`, `on-death-commands` | Game mode, Chunky pre-generation, commands on death |
| `scoreboard`, `auth`, `announcements` | Sidebar, login screen (and AuthMe Bedrock auto-login), automatic messages |
| `coords` | Turn `/coord` off when using a HUD plugin |
| `party` | Party XP and skill XP sharing |
| `portal-omen` | Lightning, rain and night when a Nether portal is lit |
| `quick-flask` | Quick Flask: drink time, duration and levels lost |

## Reporting a problem

1. Run `/hcreport` (op; works from the console too).
2. The plugin writes `plugins/HardcoreRoulette/reports/report-<date>.txt` with versions, installed plugins, run state, the `config.yml` without secrets (token, URLs and passwords become `<removido>`, IPs become `<ip>`) and the latest log lines.
3. Open an [issue](https://github.com/matheus-fsc/hardcore-roulette/issues/new/choose) and attach the file.

The full log is in `plugins/HardcoreRoulette/logs/` (one file per day, kept for 7 days). `debug: true` in config.yml makes it more detailed.

## Addon API

HardcoreRoulette registers `dev.math.hcroulette.api.HardcoreRouletteAPI` in Paper's ServicesManager:

```java
HardcoreRouletteAPI api = HardcoreRouletteAPI.get(); // null when HardcoreRoulette is not enabled
if (api != null && api.isRunWorld(player.getWorld())) {
    List<UUID> party = api.partyMembers(player.getUniqueId());
}
```

| Method | Returns |
|--------|---------|
| `runNumber()` | Current run number (party mode: highest level) |
| `currentWorld()` | Current run world |
| `isRunWorld(World)` | Whether the world (or its Nether/End) is a run world |
| `worldPrefix()`, `baseWorldName(World)` | World prefix and name without `_nether`/`_the_end` |
| `isPartyMode()`, `partyMembers(UUID)` | Party mode and the player's party members |
| `xpShareTargets(Player)`, `xpShareRatio()` | Who receives shared XP and the ratio |
| `runAliveSeconds()`, `isVoting()` | How long the run has been alive and whether a vote is open (API 2) |

Events: `RunStartEvent` (new world, run number, players), `RunEndEvent` (the run ended) and `PlayerLifeEndEvent` (a player lost their life).

To compile against the API: run `mvn install` in this repository, then add `dev.math:HardcoreRoulette:1.4.0` with `<scope>provided</scope>` to your `pom.xml` and declare `softdepend: [HardcoreRoulette]` in plugin.yml. [Classforge](https://github.com/matheus-fsc/classforge) and [SmartHordes](https://github.com/matheus-fsc/smarthordes) use the API this way.

## Building

```bash
mvn package
# target/HardcoreRoulette.jar -> plugins/
```

Requires JDK 25 and Maven. Dependency: `paper-api 26.3` (provided scope).

## Recommended plugins
- [Chunky](https://modrinth.com/plugin/chunky): pre-renders the backup worlds.
- [SmartHordes](https://github.com/matheus-fsc/smarthordes): zombie hordes.
- [AuthMe](https://modrinth.com/plugin/authmereloaded): login for offline-mode servers.
- [DirectionHUD](https://modrinth.com/plugin/directionhud): waypoints and a compass HUD.
- [AuraSkills](https://modrinth.com/plugin/auraskills), [SkinsRestorer](https://modrinth.com/plugin/skinsrestorer), [Classforge](https://github.com/matheus-fsc/classforge).

## License

[MIT](LICENSE)
