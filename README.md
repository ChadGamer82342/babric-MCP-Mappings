# babric MCP Mappings

MCP-style names for Minecraft **Beta 1.7.3**, packaged as mappings for the
[babric](https://github.com/babric) toolchain (Fabric for Beta).

This is a fork of [babric/barn](https://github.com/babric/barn) ("**B**eta y**arn**"). It keeps
Barn's build pipeline but swaps Barn's community names for the names from the old Mod Coder Pack. If you
learned modding on `net.minecraft.src.EntityPlayer`, `World` and `Block` rather than
`net.minecraft.entity.player.PlayerEntity`, this project lets you keep those names with a modern Fabric-style
setup (Loom, Mixin, Fabric Loader).

```
CLASS net/minecraft/class_54 net/minecraft/src/EntityPlayer
	FIELD field_511 timeUntilPortal I
	FIELD field_506 playerCloakUrl Ljava/lang/String;
```

Names are mapped on top of babric's [intermediary](https://github.com/babric/intermediary). This means code
compiled against these mappings is still remapped to intermediary at build time. So, at runtime it works with
mods built against Barn or any other babric mapping set.

## ⚠️ Licensing

The upstream Barn project is released under CC0. **The names in this fork come from MCP and are not original
work, so CC0 cannot be assumed to apply to them.** MCP's mappings were never released under an open licence.
The `LICENSE` file covers the build scripts and other upstream material, not the MCP-derived names.

That's why Barn forbids copying MCP names into Barn itself (see [CONTRIBUTING.md](CONTRIBUTING.md)). Please
**do not submit names from this repository upstream to Barn, Yarn or any other CC0 project**. Use these
mappings for your own personal, educational or preservation projects. Check for yourself whether that fits
what you plan to do with them.

## Using the mappings

### In a babric mod (Loom)

The repository builds on [JitPack](https://jitpack.io/#ChadGamer82342/babric-MCP-Mappings). Point your mod's
`mappings` dependency at it instead of Barn:

```groovy
repositories {
	maven { url = "https://jitpack.io" }
}

dependencies {
	// Replace <version> with a commit hash, tag or "b1.7.3-SNAPSHOT"
	mappings "com.github.ChadGamer82342:babric-MCP-Mappings:<version>"
}
```

See the [babric Loom fork](https://github.com/babric/fabric-loom) and the
[babric example mod](https://github.com/babric/example-mod) for a full project setup.

### As a deobfuscated jar

```sh
./gradlew mapNamedJar     # -> b1.7.3-named.jar
./gradlew decompileCFR    # -> namedSrc/ (readable source, not meant to be recompiled)
```

## Building

Requirements:

- **Java 17 or newer** (checked in `settings.gradle`)
- Nothing else: the Gradle wrapper (Gradle 9.9) downloads everything, including the Minecraft jars, from the
  [babric manifest polyfill](https://github.com/babric/manifest-polyfill).

```sh
./gradlew build
```

Outputs in `build/libs/`:

| File | Contents |
| --- | --- |
| `barn-b1.7.3+build.<n>.jar` | `mappings/mappings.tiny` (intermediary → named) plus unpick definitions. This is what Loom consumes. |
| `barn-tiny-b1.7.3+build.<n>.gz` | The same tiny file, gzipped |
| `barn-b1.7.3+build.<n>-constants.jar` | Constant classes used by unpick |

`<n>` is `$BUILD_NUMBER`, or `local` when unset. The artifacts are still called `barn` because the build is
inherited from upstream.

## Editing mappings

Mappings live in [`mappings/`](mappings) as [Enigma](https://github.com/FabricMC/Enigma) `.mapping` files,
one per top-level class, laid out by package (`mappings/net/minecraft/src/EntityPlayer.mapping`).

1. Fork and clone the repository.
2. Run `./gradlew yarn` to open Enigma on the merged, unpicked jar with these mappings loaded.
   (`./gradlew yarnWithoutUnpick` skips the unpick step if it causes trouble.)
3. Rename things, save, and run `./gradlew checkMappings` to catch mistakes.
4. Commit and open a pull request.

Keep to MCP's naming style so the set stays consistent: `net.minecraft.src` for most classes, `EntityX` /
`BlockX` / `ItemX` / `GuiX` class names, and the original `field_NNNNN_x` / `func_NNNNN_x` SRG-style names
for anything MCP left unnamed. [CONVENTIONS.md](CONVENTIONS.md) is Barn's/Yarn's guide. Use it for
formatting and Javadoc, but not for choosing names.

## Useful Gradle tasks

| Task | What it does |
| --- | --- |
| `yarn` | Launch Enigma with the mappings and the name-proposal plugin |
| `build` | Build the tiny mappings jar, the gzipped tiny file and the constants jar |
| `checkMappings` | Validate the Enigma mappings against the merged jar |
| `mergeJars` | Download and remap the client and server, then merge them into `b1.7.3-intermediary.jar` |
| `mapNamedJar` | Produce `b1.7.3-named.jar` with these names applied |
| `decompileCFR` | Decompile the named jar into `namedSrc/` |
| `javadocJar` | Generate Javadoc from the mappings' comments |
| `clean` | Delete `build/` and the cached Minecraft files in `.gradle/minecraft` |

## Credits

- [babric](https://github.com/babric) for Barn, intermediary, Loom and the rest of the Beta toolchain
- [FabricMC](https://github.com/FabricMC) for Yarn, Enigma, Stitch, tiny-remapper and unpick
- The original Mod Coder Pack team, whose names this project uses

## Using with fabric-loom 1.17+ / babric-loom-extension

Builds also publish a tiny v2 (`intermediary` → `named`) jar with the `v2` classifier. The default artifact stays tiny v1 for babric-loom 0.12.

```groovy
mappings 'com.github.ChadGamer82342:babric-MCP-Mappings:<tag>:v2'
loom { clientOnlyMinecraftJar() } // instead of gluedMinecraftJar()
```

This needs a full JDK 21+ as `JAVA_HOME`. A JRE fails with `JAVA_COMPILER` missing.
