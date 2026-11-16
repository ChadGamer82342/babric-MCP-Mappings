---
name: mapcli
description: Name unmapped methods, parameters, fields and classes in this babric MCP mappings repo using the token-efficient ./mapcli CLI (Enigma index + Vineflower decompiler). Use when asked to rename, map, deobfuscate or document Minecraft b1.7.3 code, or to find what still needs a name.
---

# mapcli: naming workflow for agents

`./mapcli` edits `mappings/` (Enigma directory format, the same files `./gradlew yarn` shows in the GUI).
Output is short on purpose. Everything below is what the one-line hints in the output point to.

First run builds the CLI (`./gradlew installAgentCli`); later calls take about 2 s. Needs
`b1.7.3-intermediary-unpicked.jar` (`./gradlew unpickIntermediaryJar` if missing or broken).
Set `MAPCLI_DEBUG=1` to see stack traces and JVM warnings.

## Loop

1. `./mapcli next -n 10` lists what is missing, most referenced first.
   `method_645 MathHelper.floor_double(double):int need:p0 refs:112`
   - `?` instead of a name = method has no name yet. `need:name` = method needs a name.
   - `need:p0,p1` = parameters at those 0-based positions have no name.
   - `-- 10 of 2077 left, next page: --skip 10`
2. `./mapcli view <id>` shows one method: signature (`p0?` = unnamed param), owner/super types, and its
   Vineflower body (current names already applied). Callers and overrides are NOT included.
   Use `--no-body` for just the signature, `--max-lines N` to widen (default 80).
3. Only if the body does not make the purpose clear: `./mapcli usages <id> --snippet -n 10`
   (callers, with the decompiled lines that call it). Also `view <Class>` for a class overview.
4. Apply names in a batch, one save: 
   ```
   ./mapcli apply <<'EOF'
   rename method_835 sendPacket
   param method_835 0 packet
   doc method_835 Queues the packet to be sent to this player.
   EOF
   ```
   Output is one `ok ...` or `err <line> :: reason` per line. Fix the `err` lines and resend only those.
5. Repeat. Before finishing run `./gradlew checkMappings` (should report no errors).

Work package by package (`next --in net/minecraft/src/Block`) so related names stay consistent.

## Commands

| Command | Notes |
|---|---|
| `next [-n 10] [--kind fn\|method\|param\|field\|class] [--in text] [--skip N]` | `fn` (default) = name or params missing; `method` = names only; `param` = params only. `--in` matches obf or named class path. |
| `view <id> [--no-body] [--max-lines N]` | method, field or class. `view <Class> --members` lists members, `--src` prints decompiled source (use sparingly, large). |
| `usages <id> [-n 20] [--snippet]` | callers (grouped, `xN` = N call sites). Includes overrides of the method. |
| `rename <id> <name>` | methods rename the whole override chain. Classes: simple name keeps/gets package `net/minecraft/src`; pass a full path to move it. |
| `param <methodId> <idx> <name>` | `idx` is the 0-based position as printed (`p0`), not the bytecode slot. |
| `doc <id> <text>` | javadoc on a class, method or field (not params). |
| `apply` | stdin lines of the three edit commands. `#` comments allowed. |
| `search <regex> [--kind class\|method\|field] [-n 20]` | matches obf and current names. |
| `stats [--in text]` | named/total counts. |

## Ids

`method_1234`, `field_511`, `class_54` (intermediary, usually unique) · `EntityPlayer`, `net/minecraft/src/EntityPlayer`
· `Owner.member`, `Owner.name(int,World)` to pick an overload · `Owner.<init>(int)` for constructors.
Overriding methods share one intermediary name, so an id resolves to the root declaration
(`view` prints `(root: Owner.name)`). If `err ambiguous` appears, add the owner or the arg types.
Methods that override JDK/library methods (`run`, `equals`...) cannot be renamed.

## Naming rules (summary of CONVENTIONS.md; read it for details)

- Classes UpperCamelCase, methods/params/fields lowerCamelCase, `static final` fields UPPER_SNAKE_CASE.
  The CLI rejects other casing, Java keywords and placeholder names (`par1`, `field_123_a`, `var3`, `p0`).
- Methods are verbs (`tick`, `getBlockId`), fields/classes nouns, booleans adjectives (`powered`, `canOpen`).
- Natural word order (`ChestBlockEntity`, not `BlockEntityChest`). American English. Few abbreviations
  (`id`, `pos`, `nbt`, `init`, `min`, `max`), acronyms as words (`Json`, not `JSON`).
- Do not repeat the owner in the name (`Box.create`, not `Box.createBox`). Look at callers and overrides first.
- This repo keeps MCP-style names for Beta 1.7.3 (see README); prefer the established MCP name when you know it,
  otherwise pick a descriptive one. Never copy names from other CC0 projects into here or the other way round
  (licensing, see README).
- Parameters: name by meaning (`x`, `y`, `z`, `entity`, `world`); same name for the same thing across overloads.

## Tips

- `view` bodies show unnamed params as `p0`, `p1`. Local variables (`var3`) cannot be renamed here; ignore them.
- Naming a method makes its callers more readable: re-`view` a caller after renaming callees.
- `./gradlew yarn` opens the Enigma GUI on the same files if a human wants to look.
- `./gradlew decompileVineflower` writes the full named source to `namedSrc/` (large; grep it rather than reading it).
