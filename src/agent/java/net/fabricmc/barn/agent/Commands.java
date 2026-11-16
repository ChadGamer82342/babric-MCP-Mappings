package net.fabricmc.barn.agent;

import cuchaz.enigma.analysis.EntryReference;
import cuchaz.enigma.translation.mapping.EntryMapping;
import cuchaz.enigma.translation.representation.AccessFlags;
import cuchaz.enigma.translation.representation.entry.ClassDefEntry;
import cuchaz.enigma.translation.representation.entry.ClassEntry;
import cuchaz.enigma.translation.representation.entry.Entry;
import cuchaz.enigma.translation.representation.entry.FieldEntry;
import cuchaz.enigma.translation.representation.entry.LocalVariableEntry;
import cuchaz.enigma.translation.representation.entry.MethodEntry;
import cuchaz.enigma.utils.validation.ParameterizedMessage;
import cuchaz.enigma.utils.validation.ValidationContext;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** All CLI commands. Output is line oriented and short on purpose. */
final class Commands {
	private final Workspace ws;
	private final Resolver res;
	private final PrintStream out;
	private VfSource vf;

	Commands(Workspace ws, PrintStream out) {
		this.ws = ws;
		this.res = new Resolver(ws);
		this.out = out;
	}

	private VfSource vf() {
		if (vf == null) vf = new VfSource(ws);
		return vf;
	}

	// ------------------------------------------------------------------ ids / labels

	private String idOf(Entry<?> e) {
		if (e instanceof MethodEntry m) {
			if (m.isConstructor()) return ws.className(m.getParent()) + ".<init>" + ws.shortSig(m).replaceAll("\\):.*", ")");
			if (!Names.INTERMEDIARY.matcher(m.getName()).matches()) return ws.className(m.getParent()) + "." + m.getName() + ws.shortSig(m).replaceAll("\\):.*", ")");
			return m.getName();
		}
		if (e instanceof FieldEntry f) return Names.INTERMEDIARY.matcher(f.getName()).matches() ? f.getName() : ws.className(f.getParent()) + "." + f.getName();
		if (e instanceof ClassEntry c) return Names.simple(c.getFullName());
		return e.getName();
	}

	/** Owner.name(sig) where unnamed things print as '?' */
	private String methodLine(MethodEntry m) {
		String name = ws.isNamed(m) || !ws.methodNeedsName(m) ? ws.methodName(m) : "?";
		return ws.className(m.getParent()) + "." + name + ws.shortSig(m);
	}

	private static String paramNeeds(List<Integer> unnamed) {
		return unnamed.stream().map(i -> "p" + i).collect(Collectors.joining(","));
	}

	// ------------------------------------------------------------------ next

	void next(Args a) {
		String kind = a.str("kind", "fn");
		int n = a.num("n", 10);
		int skip = a.num("skip", 0);
		String scope = a.str("in", null);
		List<String[]> rows = new ArrayList<>(); // sortKey(refs), line
		List<int[]> sortKeys = new ArrayList<>();
		List<Object[]> items = new ArrayList<>();
		switch (kind) {
			case "fn", "method", "param" -> {
				for (MethodEntry m : ws.allMethods()) {
					if (m.getName().equals("<clinit>") || ws.isSyntheticLike(m) || !ws.isRoot(m)) continue;
					if (!ws.inScope(m.getParent(), scope)) continue;
					boolean needName = !kind.equals("param") && !m.isConstructor() && ws.methodNeedsName(m) && ws.project.isRenamable(m);
					List<Integer> needParams = kind.equals("method") ? List.of() : ws.unnamedParams(m);
					if (!needName && needParams.isEmpty()) continue;
					List<String> need = new ArrayList<>();
					if (needName) need.add("name");
					if (!needParams.isEmpty()) need.add(paramNeeds(needParams));
					int refs = ws.methodRefs(m);
					items.add(new Object[] {refs, idOf(m) + " " + methodLine(m) + " need:" + String.join(",", need) + " refs:" + refs});
				}
			}
			case "field" -> {
				for (FieldEntry f : ws.allFields()) {
					if (!Names.INTERMEDIARY.matcher(f.getName()).matches() || ws.isNamed(f) || !ws.inScope(f.getParent(), scope)) continue;
					int refs = ws.fieldRefs(f);
					items.add(new Object[] {refs, f.getName() + " " + ws.className(f.getParent()) + ".?:" + Names.javaType(f.getDesc().toString()) + " refs:" + refs});
				}
			}
			case "class" -> {
				for (ClassEntry c : ws.allClasses()) {
					if (!Names.INTERMEDIARY.matcher(Names.innermost(c.getFullName())).matches() || ws.isNamed(c) || !ws.inScope(c, scope)) continue;
					int refs = ws.classRefs(c);
					ClassDefEntry def = ws.index.getEntryIndex().getDefinition(c);
					String sup = def != null && def.getSuperClass() != null ? " extends " + ws.className(def.getSuperClass()) : "";
					items.add(new Object[] {refs, idOf(c) + sup + " refs:" + refs});
				}
			}
			default -> throw new CliException("--kind must be fn|method|param|field|class");
		}
		items.sort(Comparator.<Object[], Integer>comparing(o -> (Integer) o[0]).reversed().thenComparing(o -> (String) o[1]));
		int total = items.size();
		if (total == 0) {
			out.println("none left" + (scope != null ? " in " + scope : "") + " (kind=" + kind + ")");
			return;
		}
		for (int i = skip; i < Math.min(total, skip + n); i++) out.println(items.get(i)[1]);
		int shown = Math.min(total, skip + n) - skip;
		out.println("-- " + shown + " of " + total + " left" + (skip + shown < total ? ", next page: --skip " + (skip + shown) : "")
				+ ". view <id> | usages <id> | skill: mapcli");
	}

	// ------------------------------------------------------------------ stats

	void stats(Args a) {
		String scope = a.str("in", null);
		int[] cls = new int[2], mth = new int[2], fld = new int[2], prm = new int[2];
		for (ClassEntry c : ws.allClasses()) {
			if (!Names.INTERMEDIARY.matcher(Names.innermost(c.getFullName())).matches() || !ws.inScope(c, scope)) continue;
			cls[1]++;
			if (ws.isNamed(c)) cls[0]++;
		}
		for (MethodEntry m : ws.allMethods()) {
			if (m.getName().equals("<clinit>") || ws.isSyntheticLike(m) || !ws.isRoot(m) || !ws.inScope(m.getParent(), scope)) continue;
			if (ws.methodNeedsName(m) || (Names.INTERMEDIARY.matcher(m.getName()).matches() && ws.isNamed(m))) {
				mth[1]++;
				if (ws.isNamed(m)) mth[0]++;
			}
			int count = ws.paramCount(m);
			prm[1] += count;
			prm[0] += count - ws.unnamedParams(m).size();
		}
		for (FieldEntry f : ws.allFields()) {
			if (!Names.INTERMEDIARY.matcher(f.getName()).matches() || !ws.inScope(f.getParent(), scope)) continue;
			fld[1]++;
			if (ws.isNamed(f)) fld[0]++;
		}
		out.println(stat("classes", cls));
		out.println(stat("methods", mth));
		out.println(stat("fields", fld));
		out.println(stat("params", prm));
	}

	private static String stat(String what, int[] c) {
		return String.format("%-8s %5d/%-5d %5.1f%%", what, c[0], c[1], c[1] == 0 ? 100.0 : 100.0 * c[0] / c[1]);
	}

	// ------------------------------------------------------------------ search

	void search(Args a) {
		Pattern p = Pattern.compile(a.need(0, "regex"), Pattern.CASE_INSENSITIVE);
		String kind = a.str("kind", "any");
		int n = a.num("n", 20);
		List<String> hits = new ArrayList<>();
		if (kind.equals("any") || kind.equals("class")) {
			for (ClassEntry c : ws.allClasses()) {
				String named = ws.className(c);
				if (p.matcher(c.getFullName()).find() || p.matcher(named).find()) hits.add("class  " + idOf(c) + " " + named);
			}
		}
		if (kind.equals("any") || kind.equals("method")) {
			for (MethodEntry m : ws.allMethods()) {
				if (m.getName().startsWith("<") || ws.isSyntheticLike(m) || !ws.isRoot(m)) continue;
				String named = ws.methodName(m);
				if (p.matcher(m.getName()).find() || p.matcher(named).find()) hits.add("method " + idOf(m) + " " + ws.className(m.getParent()) + "." + named + ws.shortSig(m));
			}
		}
		if (kind.equals("any") || kind.equals("field")) {
			for (FieldEntry f : ws.allFields()) {
				String named = ws.fieldName(f);
				if (p.matcher(f.getName()).find() || p.matcher(named).find()) hits.add("field  " + idOf(f) + " " + ws.className(f.getParent()) + "." + named + ":" + Names.javaType(f.getDesc().toString()));
			}
		}
		hits.stream().limit(n).forEach(out::println);
		if (hits.size() > n) out.println("-- " + (hits.size() - n) + " more (use -n)");
		if (hits.isEmpty()) out.println("no match");
	}

	// ------------------------------------------------------------------ view

	void view(Args a) {
		Entry<?> e = res.resolve(a.need(0, "id"));
		int maxLines = a.num("max-lines", e instanceof MethodEntry ? 80 : 200);
		if (e instanceof MethodEntry m) {
			MethodEntry root = ws.root(m);
			String rootNote = root != null && !root.equals(m) ? " (root: " + ws.label(root) + ")" : "";
			if (root != null) m = root;
			viewMethod(m, a, maxLines, rootNote);
		} else if (e instanceof FieldEntry f) {
			viewField(f, a, maxLines);
		} else if (e instanceof ClassEntry c) {
			viewClass(c, a, maxLines);
		}
	}

	private String access(AccessFlags f, boolean method) {
		if (f == null) return "";
		StringBuilder sb = new StringBuilder();
		if (f.isPublic()) sb.append("public ");
		else if (f.isProtected()) sb.append("protected ");
		else if (f.isPrivate()) sb.append("private ");
		if (f.isStatic()) sb.append("static ");
		if (f.isAbstract() && method) sb.append("abstract ");
		if (f.isFinal()) sb.append("final ");
		return sb.toString();
	}

	private void viewMethod(MethodEntry m, Args a, int maxLines, String rootNote) {
		List<String> args = Names.argDescs(ws.namedDesc(m));
		StringBuilder sig = new StringBuilder(access(ws.access(m), true));
		if (!m.isConstructor()) sig.append(Names.javaType(Names.returnDesc(ws.namedDesc(m)))).append(' ');
		sig.append(m.isConstructor() ? Names.innermost(ws.named(m.getParent()).getFullName()) : (ws.methodNeedsName(m) ? m.getName() + "?" : ws.methodName(m))).append('(');
		for (int i = 0; i < args.size(); i++) {
			String pn = ws.paramName(m, i);
			if (i > 0) sig.append(", ");
			sig.append(Names.javaType(args.get(i))).append(' ').append(pn != null ? pn : "p" + i + "?");
		}
		sig.append(')');
		out.println("# " + ws.label(m) + "  id=" + idOf(m) + "  refs=" + ws.methodRefs(m)
				+ "  overrides=" + Math.max(0, ws.mapper.getObfResolver().resolveEquivalentEntries(m).size() - 1) + rootNote);
		out.println(sig);
		out.println(classLine(m.getParent()));
		EntryMapping mp = ws.mappingOf(m);
		if (mp != null && mp.javadoc() != null) out.println("doc: " + mp.javadoc());
		if (!a.flag("no-body")) {
			String body = vf().methodSource(m);
			String inner = body == null ? null : Slicer.bodyOnly(body);
			if (body == null) {
				out.println("(body not found in decompiled source; try: view " + idOf(m.getParent()) + " --src)");
			} else if (inner == null) {
				out.println("(abstract/native: no body)");
			} else {
				printLimited(inner, maxLines);
			}
		}
		out.println("more: usages " + idOf(m) + " [--snippet] | view " + idOf(m.getParent()) + " | param " + idOf(m) + " <idx> <name> | skill: mapcli");
	}

	private String classLine(ClassEntry c) {
		ClassDefEntry def = ws.index.getEntryIndex().getDefinition(c);
		StringBuilder sb = new StringBuilder("in ").append(ws.className(c));
		if (def != null) {
			if (def.getSuperClass() != null && !def.getSuperClass().getFullName().equals("java/lang/Object")) {
				sb.append(" extends ").append(ws.className(def.getSuperClass()));
			}
			if (def.getInterfaces().length > 0) {
				sb.append(" implements ");
				sb.append(java.util.Arrays.stream(def.getInterfaces()).map(ws::className).collect(Collectors.joining(",")));
			}
		}
		return sb.toString();
	}

	private void viewField(FieldEntry f, Args a, int maxLines) {
		out.println("# " + ws.label(f) + "  id=" + idOf(f) + "  refs=" + ws.fieldRefs(f));
		AccessFlags acc = ws.index.getEntryIndex().getFieldAccess(f);
		out.println(access(acc, false) + Names.javaType(f.getDesc().toString()) + " " + (ws.isNamed(f) || !Names.INTERMEDIARY.matcher(f.getName()).matches() ? ws.fieldName(f) : f.getName() + "?"));
		out.println(classLine(f.getParent()));
		EntryMapping mp = ws.mappingOf(f);
		if (mp != null && mp.javadoc() != null) out.println("doc: " + mp.javadoc());
		out.println("more: usages " + idOf(f) + " --snippet | skill: mapcli");
	}

	private void viewClass(ClassEntry c, Args a, int maxLines) {
		if (a.flag("src")) {
			printLimited(vf().sourceOf(c), maxLines);
			return;
		}
		int[] unnamedM = {0};
		List<String> fields = new ArrayList<>();
		List<String> methods = new ArrayList<>();
		for (FieldEntry f : ws.allFields()) {
			if (!f.getParent().equals(c)) continue;
			boolean un = Names.INTERMEDIARY.matcher(f.getName()).matches() && !ws.isNamed(f);
			fields.add("  " + (un ? f.getName() + " ?" : ws.fieldName(f)) + ":" + Names.javaType(ws.mapper.deobfuscate(f).getDesc().toString()) + (un ? " refs:" + ws.fieldRefs(f) : ""));
		}
		for (MethodEntry m : ws.allMethods()) {
			if (!m.getParent().equals(c) || m.getName().equals("<clinit>") || ws.isSyntheticLike(m)) continue;
			boolean un = ws.methodNeedsName(m);
			if (un) unnamedM[0]++;
			String nm = m.isConstructor() ? "<init>" : un ? "?" : ws.methodName(m);
			String up = ws.isRoot(m) && !ws.unnamedParams(m).isEmpty() ? " need:" + paramNeeds(ws.unnamedParams(m)) : "";
			methods.add("  " + (un ? m.getName() + " " : "") + nm + ws.shortSig(m) + up);
		}
		out.println("# " + ws.className(c) + "  id=" + idOf(c) + "  refs=" + ws.classRefs(c) + (ws.isNamed(c) ? "" : "  (unnamed)"));
		out.println(classLine(c).replaceFirst("^in [^ ]+ ?", "") .isEmpty() ? "" : classLine(c).replaceFirst("^in ", ""));
		out.println("fields: " + fields.size() + ", methods: " + methods.size() + " (" + unnamedM[0] + " unnamed)");
		if (a.flag("members") || a.flag("class")) {
			List<String> all = new ArrayList<>();
			if (!fields.isEmpty()) all.add("fields:");
			all.addAll(fields);
			if (!methods.isEmpty()) all.add("methods:");
			all.addAll(methods);
			printLimited(String.join("\n", all), maxLines);
		}
		out.println("more: view " + idOf(c) + " --members | --src | skill: mapcli");
	}

	private void printLimited(String text, int maxLines) {
		String[] lines = text.stripTrailing().split("\n", -1);
		for (int i = 0; i < Math.min(lines.length, maxLines); i++) out.println(lines[i]);
		if (lines.length > maxLines) out.println("... +" + (lines.length - maxLines) + " lines (--max-lines N)");
	}

	// ------------------------------------------------------------------ usages

	void usages(Args a) {
		Entry<?> e = res.resolve(a.need(0, "id"));
		int n = a.num("n", 20);
		boolean snippet = a.flag("snippet");
		Map<MethodEntry, Integer> callers = new LinkedHashMap<>();
		String targetName;
		String title;
		if (e instanceof MethodEntry m) {
			MethodEntry root = ws.root(m);
			if (root != null) m = root;
			targetName = m.isConstructor() ? Names.innermost(ws.named(m.getParent()).getFullName()) : ws.methodName(m);
			title = ws.label(m);
			for (Entry<?> eq : ws.mapper.getObfResolver().resolveEquivalentEntries(m)) {
				if (eq instanceof MethodEntry me) {
					for (EntryReference<MethodEntry, cuchaz.enigma.translation.representation.entry.MethodDefEntry> r : ws.index.getReferenceIndex().getReferencesToMethod(me)) {
						addCaller(callers, r.context);
					}
				}
			}
		} else if (e instanceof FieldEntry f) {
			targetName = ws.fieldName(f);
			title = ws.label(f);
			for (EntryReference<FieldEntry, cuchaz.enigma.translation.representation.entry.MethodDefEntry> r : ws.index.getReferenceIndex().getReferencesToField(f)) {
				addCaller(callers, r.context);
			}
		} else {
			ClassEntry c = (ClassEntry) e;
			targetName = Names.innermost(ws.named(c).getFullName());
			title = ws.className(c);
			for (EntryReference<ClassEntry, cuchaz.enigma.translation.representation.entry.MethodDefEntry> r : ws.index.getReferenceIndex().getReferencesToClass(c)) {
				addCaller(callers, r.context);
			}
			for (EntryReference<ClassEntry, cuchaz.enigma.translation.representation.entry.MethodDefEntry> r : ws.index.getReferenceIndex().getMethodTypeReferencesToClass(c)) {
				addCaller(callers, r.context);
			}
		}
		int total = callers.values().stream().mapToInt(Integer::intValue).sum();
		out.println("# " + title + ": " + total + " refs in " + callers.size() + " methods");
		List<Map.Entry<MethodEntry, Integer>> sorted = new ArrayList<>(callers.entrySet());
		sorted.sort(Comparator.comparing(en -> ws.label(en.getKey()) + ws.shortSig(en.getKey())));
		int shown = 0;
		for (Map.Entry<MethodEntry, Integer> en : sorted) {
			if (shown++ >= n) break;
			MethodEntry caller = en.getKey();
			out.println(ws.label(caller) + ws.shortSig(caller) + (en.getValue() > 1 ? " x" + en.getValue() : "") + "  [" + idOf(caller) + "]");
			if (snippet) {
				String body = null;
				try {
					body = vf().methodSource(caller);
				} catch (RuntimeException ex) {
					// decompile problems must not break the listing
				}
				if (body != null) {
					Pattern tp = Pattern.compile("\\b" + Pattern.quote(targetName) + "\\b");
					int count = 0;
					for (String line : body.split("\n")) {
						if (tp.matcher(line).find() && count++ < 2) out.println("    > " + line.strip());
					}
				}
			}
		}
		if (sorted.size() > n) out.println("-- " + (sorted.size() - n) + " more callers (-n)");
	}

	private void addCaller(Map<MethodEntry, Integer> callers, MethodEntry ctx) {
		if (ctx == null) return;
		MethodEntry plain = new MethodEntry(ctx.getParent(), ctx.getName(), ctx.getDesc());
		callers.merge(plain, 1, Integer::sum);
	}

	// ------------------------------------------------------------------ editing

	/** @return true if something changed */
	boolean edit(String cmd, List<String> args) {
		switch (cmd) {
			case "rename" -> {
				need(args, 2, "rename <id> <name>");
				rename(args.get(0), args.get(1));
			}
			case "param" -> {
				need(args, 3, "param <methodId> <idx> <name>");
				param(args.get(0), args.get(1), args.get(2));
			}
			case "doc" -> {
				need(args, 2, "doc <id> <text>");
				doc(args.get(0), String.join(" ", args.subList(1, args.size())));
			}
			default -> throw new CliException("unknown edit command " + cmd);
		}
		return true;
	}

	private static void need(List<String> args, int n, String usage) {
		if (args.size() < n) throw new CliException("usage: " + usage);
	}

	private void checkName(Entry<?> e, String name) {
		if (!Names.IDENT.matcher(name).matches() || Names.KEYWORDS.contains(name)) {
			throw new CliException("'" + name + "' is not a valid java identifier");
		}
		if (Names.PLACEHOLDER.matcher(name).matches()) throw new CliException("'" + name + "' looks like a placeholder name");
		if (e instanceof ClassEntry && !Names.isUpperCamel(name)) throw new CliException("class names are UpperCamelCase");
		if (e instanceof MethodEntry && !Names.isLowerCamel(name)) throw new CliException("method names are lowerCamelCase");
		if (e instanceof LocalVariableEntry && !Names.isLowerCamel(name)) throw new CliException("parameter names are lowerCamelCase");
		if (e instanceof FieldEntry f) {
			var acc = ws.index.getEntryIndex().getFieldAccess(f);
			boolean constant = acc != null && acc.isStatic() && acc.isFinal();
			if (constant && !Names.isUpperSnake(name)) throw new CliException("static final fields are UPPER_SNAKE_CASE");
			if (!constant && !Names.isLowerCamel(name)) throw new CliException("fields are lowerCamelCase");
		}
	}

	private void put(Entry<?> e, EntryMapping mapping) {
		ValidationContext vc = new ValidationContext();
		ws.mapper.validatePutMapping(vc, e, mapping);
		if (!vc.canProceed()) throw new CliException(messages(vc));
		ws.mapper.putMapping(vc, e, mapping);
		if (!vc.canProceed()) throw new CliException(messages(vc));
	}

	private static String messages(ValidationContext vc) {
		return vc.getMessages().stream().map(ParameterizedMessage::getText).collect(Collectors.joining("; "));
	}

	private EntryMapping withName(Entry<?> e, String name) {
		EntryMapping old = ws.mappingOf(e);
		return old == null ? new EntryMapping(name) : old.withName(name);
	}

	private void rename(String id, String name) {
		Entry<?> e = res.resolve(id);
		if (e instanceof MethodEntry m) {
			MethodEntry root = ws.root(m);
			if (root == null || !ws.project.isRenamable(root)) throw new CliException(ws.label(m) + " overrides a library method; not renamable");
			e = root;
		} else if (e instanceof FieldEntry f && !ws.project.isRenamable(f)) {
			throw new CliException("not renamable");
		}
		String shortName = name.substring(name.lastIndexOf('/') + 1);
		checkName(e, shortName);
		String target = name;
		if (e instanceof ClassEntry c) {
			if (c.isInnerClass()) {
				if (name.contains("/")) throw new CliException("inner classes take a simple name");
			} else if (!name.contains("/")) {
				String current = ws.isNamed(c) ? ws.named(c).getFullName() : "net/minecraft/src/x";
				target = current.substring(0, current.lastIndexOf('/') + 1) + name;
			}
		}
		String before = e instanceof MethodEntry m0 ? ws.label(m0) : e instanceof FieldEntry f0 ? ws.label(f0) : ws.className((ClassEntry) e);
		put(e, withName(e, target));
		int overrides = e instanceof MethodEntry ? ws.mapper.getObfResolver().resolveEquivalentEntries(e).size() - 1 : 0;
		out.println("ok " + idOf(e) + " -> " + shortName + (overrides > 0 ? " (+" + overrides + " overrides)" : ""));
	}

	private void param(String id, String idx, String name) {
		MethodEntry m = res.resolveMethod(id);
		MethodEntry root = ws.root(m);
		if (root == null) throw new CliException("library method; params not renamable");
		int i;
		try {
			i = Integer.parseInt(idx.replaceFirst("^p", ""));
		} catch (NumberFormatException ex) {
			throw new CliException("param index must be a number (0-based), got '" + idx + "'");
		}
		if (i < 0 || i >= ws.paramCount(root)) throw new CliException(ws.label(root) + " has " + ws.paramCount(root) + " params");
		LocalVariableEntry le = ws.paramEntry(root, i);
		checkName(le, name);
		put(le, withName(le, name));
		out.println("ok " + idOf(root) + " p" + i + " -> " + name);
	}

	private void doc(String id, String text) {
		Entry<?> e = res.resolve(id);
		if (e instanceof MethodEntry m && ws.root(m) != null) e = ws.root(m);
		EntryMapping old = ws.mappingOf(e);
		EntryMapping nu = (old == null ? EntryMapping.DEFAULT : old).withDocs(text.isBlank() ? null : text);
		put(e, nu);
		out.println("ok doc " + idOf(e));
	}

	/** Reads edit commands from lines and applies them all. Returns number applied. */
	int apply(List<String> lines) {
		int ok = 0;
		for (String raw : lines) {
			String line = raw.strip();
			if (line.isEmpty() || line.startsWith("#")) continue;
			List<String> t = new ArrayList<>(List.of(line.split("\\s+")));
			String cmd = t.remove(0);
			try {
				edit(cmd, t);
				ok++;
			} catch (CliException ex) {
				out.println("err " + line + " :: " + ex.getMessage());
			}
		}
		return ok;
	}
}
