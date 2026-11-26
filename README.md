# Modern Dungeons Guide

Modern Dungeons Guide is an unofficial Minecraft 26.3 Fabric port of [Dungeons Guide](https://github.com/Dungeons-Guide/Skyblock-Dungeons-Guide), focused on Hypixel Skyblock dungeon secret routing and pathfinding.

> **Maintenance notice:** This fork is not actively maintained and is mainly for personal use. If you want precalcs, DM `@jzw.` on Discord.

This fork is a secret-routing-centered build. Puzzle solvers, bossfight helpers, cosmetics, Discord rich presence, party finder tools, and the original Dungeons Guide backend integrations are not part of the shipped 26.3 mod — use [Odin](https://github.com/odtheking/Odin) or other modern Skyblock dungeon mods for those features.

## Requirements

- Minecraft 26.3
- Fabric Loader 0.20.1 or newer
- Fabric API for Minecraft 26.3
- Java 25 or newer

## Installation

1. Install Fabric Loader for Minecraft 26.3.
2. Download the Modern Dungeons Guide release jar and a compatible Fabric API jar.
3. Place both jars in your Minecraft `mods` directory.
4. Launch the Fabric 26.3 profile with Java 25 or newer.

## Usage

- `/dg` opens the mod configuration. `/dungeonsguide`, `/dungeonguide`, and `/deegee` are aliases.
- `/dg gui` opens the HUD layout editor.
- `/dg scale <scale>` changes the global HUD scale; `/dg scale reset` restores the default.
- Configure pathfinding, secret display, community route overlays, presets, and HUD elements from the **Pathfinding & Secrets** and **Dungeon HUD** categories in `/dg`.

### Included feature areas

- Smart Route and single-target secret pathfinding
- Route lines, markers, route freezing/skipping, and secret-completion tracking
- Blood Rush routing
- Secret Browser and mechanic browsing
- Markers for remaining secrets, doors, and secret bats
- Community route overlays for user-installed Secret Routes Mod and Odin waypoint files
- Dungeon secret/crypt counters, local dungeon score estimation, room name display, and dungeon timers
- Pathfind precalculation preset import/export and management

Community route data is not bundled. At runtime the overlay can read JSON files you install in:

- `<minecraft config>/SecretRoutes/routes/`
- `<minecraft config>/odin/dungeon-waypoints/`
- `<minecraft config>/dungeonsguide/routes/`

The original remote pathfind-calculation service and store are dead, so remote calculation purchasing/requesting is hidden in this fork. Local/imported precalculation presets remain available.

## Server-rule safety

Use of this mod is at your own risk. The mod renders guidance and does not aim, rotate, mine, or send movement actions for you. Hypixel's rules can change, and server operators make the final decision about what is allowed.

## Building from source

Use Java 25:

```powershell
# Windows
set JAVA_HOME=C:\path\to\jdk-25
.\gradlew.bat :mod:build :mod:test
```

```sh
# Linux/macOS
export JAVA_HOME=/path/to/jdk-25
./gradlew :mod:build :mod:test
```

The built jar is written to `mod/build/libs/`.

## License and attribution

Modern Dungeons Guide is distributed under the GNU Affero General Public License v3.0 or later; see [LICENSE](LICENSE). It is derived from the original Dungeons Guide project by cyoung06/syeyoung.

See [THIRD-PARTY-NOTICES](THIRD-PARTY-NOTICES) for additional attribution. This project is not affiliated with or endorsed by Hypixel Inc.
