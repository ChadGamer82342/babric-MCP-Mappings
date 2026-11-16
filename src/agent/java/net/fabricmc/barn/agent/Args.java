package net.fabricmc.barn.agent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Tiny argument parser: positional args plus --flag / --opt value. */
final class Args {
	private static final Set<String> BOOLEAN = Set.of("no-body", "class", "members", "snippet", "src", "all");
	final List<String> pos = new ArrayList<>();
	private final Map<String, String> opts = new HashMap<>();

	Args(List<String> raw) {
		for (int i = 0; i < raw.size(); i++) {
			String a = raw.get(i);
			if (a.startsWith("-") && a.length() > 1 && !Character.isDigit(a.charAt(1))) {
				String key = a.replaceFirst("^--?", "");
				if (BOOLEAN.contains(key)) {
					opts.put(key, "true");
				} else if (i + 1 < raw.size()) {
					opts.put(key, raw.get(++i));
				} else {
					throw new CliException("option " + a + " needs a value");
				}
			} else {
				pos.add(a);
			}
		}
	}

	boolean flag(String key) {
		return opts.containsKey(key);
	}

	String str(String key, String def) {
		return opts.getOrDefault(key, def);
	}

	int num(String key, int def) {
		String v = opts.get(key);
		if (v == null) return def;
		try {
			return Integer.parseInt(v);
		} catch (NumberFormatException e) {
			throw new CliException("option -" + key + " needs a number, got '" + v + "'");
		}
	}

	String need(int i, String what) {
		if (i >= pos.size()) throw new CliException("missing <" + what + ">");
		return pos.get(i);
	}
}
