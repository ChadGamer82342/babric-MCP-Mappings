package net.fabricmc.barn.agent;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public final class Main {
	private static final String HELP = """
			mapcli - token-efficient mapping helper for agents (details: skill 'mapcli')
			  next   [-n 10] [--kind fn|method|param|field|class] [--in pkg|Class] [--skip N]   what still needs a name
			  view   <id> [--no-body] [--max-lines N] [--src] [--members]                      scoped view of a method/field/class
			  usages <id> [-n 20] [--snippet]                                                  where it is used
			  rename <id> <name>   param <methodId> <idx> <name>   doc <id> <text>
			  apply  (stdin: one rename/param/doc per line; saves once)
			  search <regex> [--kind class|method|field] [-n 20]      stats [--in pkg]
			ids: method_1234 | Owner.method_1234 | Owner.name(int,World) | Owner.<init>(int) | field_5 | class_54 | EntityPlayer""";

	private Main() {
	}

	public static void main(String[] argv) {
		List<String> raw = new ArrayList<>(List.of(argv));
		if (raw.isEmpty() || Set.of("help", "-h", "--help").contains(raw.get(0))) {
			System.out.println(HELP);
			return;
		}
		String cmd = raw.remove(0);
		try {
			Path root = findRoot();
			Workspace ws = Workspace.open(root);
			Commands c = new Commands(ws, System.out);
			switch (cmd) {
				case "next" -> c.next(new Args(raw));
				case "stats" -> c.stats(new Args(raw));
				case "search" -> c.search(new Args(raw));
				case "view" -> c.view(new Args(raw));
				case "usages" -> c.usages(new Args(raw));
				case "rename", "param", "doc" -> {
					c.edit(cmd, raw);
					ws.save();
				}
				case "roundtrip" -> {
					// internal check: write all loaded mappings to <dir> so they can be diffed against mappings/
					cuchaz.enigma.translation.mapping.serde.MappingFormat.ENIGMA_DIRECTORY.write(ws.mapper.getObfToDeobf(),
							Path.of(raw.get(0)), cuchaz.enigma.ProgressListener.none(), ws.saveParams);
					System.out.println("ok");
				}
				case "apply" -> {
					List<String> lines = new ArrayList<>();
					try (BufferedReader r = new BufferedReader(new InputStreamReader(System.in))) {
						String l;
						while ((l = r.readLine()) != null) lines.add(l);
					}
					if (c.apply(lines) > 0) ws.save();
				}
				default -> {
					System.err.println("unknown command '" + cmd + "'. Try: mapcli help");
					System.exit(2);
				}
			}
			System.out.flush();
		} catch (CliException e) {
			System.out.println("err " + e.getMessage());
			System.exit(1);
		} catch (Exception e) {
			System.out.println("err " + e);
			if (System.getenv("MAPCLI_DEBUG") != null) e.printStackTrace();
			System.exit(1);
		}
	}

	private static Path findRoot() {
		String env = System.getenv("MAPCLI_ROOT");
		if (env != null) return Path.of(env);
		Path p = Path.of("").toAbsolutePath();
		while (p != null) {
			if (Files.exists(p.resolve("enigma_profile.json"))) return p;
			p = p.getParent();
		}
		throw new CliException("run inside the mappings repo (enigma_profile.json not found) or set MAPCLI_ROOT");
	}
}
