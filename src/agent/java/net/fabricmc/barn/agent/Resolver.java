package net.fabricmc.barn.agent;

import cuchaz.enigma.translation.representation.entry.ClassEntry;
import cuchaz.enigma.translation.representation.entry.Entry;
import cuchaz.enigma.translation.representation.entry.FieldEntry;
import cuchaz.enigma.translation.representation.entry.MethodEntry;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Turns the short ids used on the command line into entries.
 * <pre>
 *   class_54 | EntityPlayer | net/minecraft/src/EntityPlayer
 *   method_1234 | Entity.method_1234 | EntityPlayer.onUpdate | EntityPlayer.onUpdate(int,World)
 *   field_511 | EntityPlayer.timeUntilPortal
 * </pre>
 */
final class Resolver {
	private final Workspace ws;
	private Map<String, List<ClassEntry>> classes;
	private Map<String, List<MethodEntry>> methods;
	private Map<String, List<FieldEntry>> fields;

	Resolver(Workspace ws) {
		this.ws = ws;
	}

	private void build() {
		if (classes != null) return;
		classes = new LinkedHashMap<>();
		methods = new LinkedHashMap<>();
		fields = new LinkedHashMap<>();
		for (ClassEntry c : ws.allClasses()) {
			String obf = c.getFullName();
			String named = ws.named(c).getFullName();
			for (String k : new LinkedHashSet<>(List.of(obf, Names.simple(obf), named, Names.simple(named)))) {
				classes.computeIfAbsent(k, x -> new ArrayList<>()).add(c);
			}
		}
		for (MethodEntry m : ws.allMethods()) {
			if (m.getName().equals("<clinit>")) continue;
			for (String k : new LinkedHashSet<>(List.of(m.getName(), ws.methodName(m)))) {
				methods.computeIfAbsent(k, x -> new ArrayList<>()).add(m);
			}
		}
		for (FieldEntry f : ws.allFields()) {
			for (String k : new LinkedHashSet<>(List.of(f.getName(), ws.fieldName(f)))) {
				fields.computeIfAbsent(k, x -> new ArrayList<>()).add(f);
			}
		}
	}

	Entry<?> resolve(String id) {
		build();
		String s = id.trim();
		String argSpec = null;
		int paren = s.indexOf('(');
		if (paren >= 0) {
			argSpec = s.substring(paren + 1, s.lastIndexOf(')') >= 0 ? s.lastIndexOf(')') : s.length());
			s = s.substring(0, paren);
		}
		if (argSpec == null && classes.containsKey(s)) {
			return unique(classes.get(s), id, c -> ws.className(c));
		}
		String owner = null;
		String member = s;
		int dot = s.lastIndexOf('.');
		if (dot > 0) {
			owner = s.substring(0, dot);
			member = s.substring(dot + 1);
		}
		List<Entry<?>> found = new ArrayList<>();
		if (owner != null && !classes.containsKey(owner)) {
			throw new CliException("unknown class '" + owner + "'");
		}
		List<ClassEntry> owners = owner == null ? null : classes.get(owner);
		for (MethodEntry m : methods.getOrDefault(member, List.of())) {
			if ((owners == null || owners.contains(m.getParent())) && matchesArgs(m, argSpec)) found.add(m);
		}
		if (argSpec == null || argSpec.isEmpty() && found.isEmpty()) {
			for (FieldEntry f : fields.getOrDefault(member, List.of())) {
				if (owners == null || owners.contains(f.getParent())) found.add(f);
			}
		}
		if (found.isEmpty()) throw new CliException("not found: " + id);
		if (found.size() == 1) return found.get(0);
		// Overriding methods share a name: collapse to roots
		Set<Entry<?>> collapsed = new LinkedHashSet<>();
		for (Entry<?> e : found) {
			if (e instanceof MethodEntry m) {
				MethodEntry r = ws.root(m);
				collapsed.add(r != null ? r : m);
			} else {
				collapsed.add(e);
			}
		}
		if (collapsed.size() == 1) return collapsed.iterator().next();
		throw new CliException("ambiguous '" + id + "': " + collapsed.stream().limit(8).map(this::describe)
				.collect(Collectors.joining(", ")) + (collapsed.size() > 8 ? ", ..." : "") + ". Use Owner.name or add (argtypes)");
	}

	private boolean matchesArgs(MethodEntry m, String argSpec) {
		if (argSpec == null) return true;
		String sig = ws.shortSig(m);
		String args = sig.substring(1, sig.indexOf(')'));
		return args.equals(argSpec.replace(" ", ""));
	}

	private <T> T unique(List<T> l, String id, java.util.function.Function<T, String> name) {
		if (l.size() == 1) return l.get(0);
		throw new CliException("ambiguous '" + id + "': " + l.stream().limit(8).map(name).collect(Collectors.joining(", ")));
	}

	String describe(Entry<?> e) {
		if (e instanceof MethodEntry m) return m.getParent().getName() + "." + m.getName();
		if (e instanceof FieldEntry f) return f.getParent().getName() + "." + f.getName();
		return e.getName();
	}

	MethodEntry resolveMethod(String id) {
		Entry<?> e = resolve(id);
		if (e instanceof MethodEntry m) return m;
		throw new CliException("'" + id + "' is not a method");
	}

	List<ClassEntry> topLevelOrInnerSorted(List<ClassEntry> l) {
		l.sort(Comparator.comparing(Object::toString));
		return l;
	}
}
