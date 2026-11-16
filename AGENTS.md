# Agent notes

This repo holds MCP-style names for Minecraft Beta 1.7.3 as Enigma mappings in `mappings/`.
Any coding agent can name things with the `./mapcli` CLI. Full reference: `.claude/skills/mapcli/SKILL.md`
(plain markdown, readable by any agent).

## Naming loop

1. `./mapcli next -n 10` lists what still needs a name, most referenced first.
   `need:name` means the method has no name, `need:p0,p1` means those parameters have none.
2. `./mapcli view <id>` shows one method: signature (`p0?` is an unnamed parameter) and its Vineflower body.
   Use `--no-body` for the signature only.
3. Only if the purpose is still unclear: `./mapcli usages <id> --snippet -n 10`.
4. Apply names in one batch:
   ```
   ./mapcli apply <<'EOF'
   rename method_835 sendPacket
   param method_835 0 packet
   doc method_835 Queues the packet to be sent to this player.
   EOF
   ```
   Output is one `ok` or `err <line> :: reason` per line. Resend only the `err` lines.
5. Repeat, then run `./gradlew checkMappings` before finishing.

Ids: `method_1234`, `field_511`, `class_54`, `EntityPlayer`, `Owner.name(int,World)`, `Owner.<init>(int)`.
Output hints that mention "skill: mapcli" mean the file above.

## Rules

- Naming conventions are in `CONVENTIONS.md`. The CLI rejects bad casing, keywords and placeholder names.
- Do not copy names between this repo and other CC0 mapping projects (see the licensing note in `README.md`).
- Mapping files are in Enigma directory format. Edit them through `./mapcli`, not by hand.
- `./mapcli` needs Java 17+. The first run builds it with Gradle. Set `MAPCLI_DEBUG=1` for stack traces.
