package net.fabricmc.barn.agent;

import cuchaz.enigma.Enigma;
import cuchaz.enigma.EnigmaProfile;
import cuchaz.enigma.EnigmaProject;
import cuchaz.enigma.ProgressListener;
import cuchaz.enigma.analysis.index.JarIndex;
import cuchaz.enigma.classprovider.ClasspathClassProvider;
import cuchaz.enigma.translation.mapping.EntryMapping;
import cuchaz.enigma.translation.mapping.EntryRemapper;
import cuchaz.enigma.translation.mapping.ResolutionStrategy;
import cuchaz.enigma.translation.mapping.serde.MappingFormat;
import cuchaz.enigma.translation.mapping.serde.MappingSaveParameters;
import cuchaz.enigma.translation.mapping.tree.EntryTree;
import cuchaz.enigma.translation.representation.AccessFlags;
import cuchaz.enigma.translation.representation.entry.ClassEntry;
import cuchaz.enigma.translation.representation.entry.Entry;
import cuchaz.enigma.translation.representation.entry.FieldEntry;
import cuchaz.enigma.translation.representation.entry.LocalVariableEntry;
import cuchaz.enigma.translation.representation.entry.MethodEntry;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.zip.ZipFile;

/** The loaded jar index plus the current mappings. */
final class Workspace {
	final Path root;
	final Path mappingsDir;
	final Path jar;
	final EnigmaProject project;
	final JarIndex index;
	final MappingSaveParameters saveParams;
	final EntryRemapper mapper;

	private Workspace(Path root, Path mappingsDir, Path jar, EnigmaProject project, MappingSaveParameters params) {
		this.root = root;
		this.mappingsDir = mappingsDir;
		this.jar = jar;
		this.project = project;
		this.index = project.getJarIndex();
		this.saveParams = params;
		this.mapper = project.getMapper();
	}

	static Workspace open(Path root) throws Exception {
		Path unpicked = root.resolve("b1.7.3-intermediary-unpicked.jar");
		Path plain = root.resolve("b1.7.3-intermediary.jar");
		Path jar = isValidZip(unpicked) ? unpicked : plain;
		if (!isValidZip(jar)) {
			throw new CliException("No usable jar. Run: ./gradlew unpickIntermediaryJar (or mergeJars)");
		}
		Path mappings = root.resolve("mappings");
		EnigmaProfile profile = EnigmaProfile.read(root.resolve("enigma_profile.json"));
		Enigma enigma = Enigma.builder().setProfile(profile).build();
		PrintStream realOut = System.out;
		EnigmaProject project;
		try {
			// Enigma and its plugins can be chatty on stdout. Keep our output clean.
			System.setOut(System.err);
			project = enigma.openJar(jar, new ClasspathClassProvider(), ProgressListener.none());
			MappingSaveParameters params = profile.getMappingSaveParameters();
			if (Files.isDirectory(mappings)) {
				EntryTree<EntryMapping> tree = MappingFormat.ENIGMA_DIRECTORY.read(mappings, ProgressListener.none(), params);
				project.setMappings(tree);
			}
			return new Workspace(root, mappings, jar, project, params);
		} finally {
			System.setOut(realOut);
		}
	}

	private static boolean isValidZip(Path p) {
		if (!Files.isRegularFile(p)) return false;
		try (ZipFile z = new ZipFile(p.toFile())) {
			return z.size() > 0;
		} catch (IOException e) {
			return false;
		}
	}

	/** Writes only the changed mapping files. */
	void save() {
		var delta = mapper.takeMappingDelta();
		MappingFormat.ENIGMA_DIRECTORY.write(mapper.getObfToDeobf(), delta, mappingsDir, ProgressListener.none(), saveParams);
	}

	// ---- naming state

	EntryMapping mappingOf(Entry<?> e) {
		return mapper.getDeobfMapping(e);
	}

	/** True when the entry has a real (non placeholder) name. */
	boolean isNamed(Entry<?> e) {
		EntryMapping m = mappingOf(e);
		return m != null && m.targetName() != null && !Names.PLACEHOLDER.matcher(simpleOf(m.targetName())).matches();
	}

	private static String simpleOf(String name) {
		return name.substring(name.lastIndexOf('/') + 1);
	}

	ClassEntry named(ClassEntry c) {
		return mapper.deobfuscate(c);
	}

	/** Current display name of a class, without package (inner classes as Outer.Inner). */
	String className(ClassEntry c) {
		return Names.simple(named(c).getFullName());
	}

	String className(String internalObfName) {
		return className(new ClassEntry(internalObfName));
	}

	String methodName(MethodEntry m) {
		return mapper.deobfuscate(m).getName();
	}

	String fieldName(FieldEntry f) {
		return mapper.deobfuscate(f).getName();
	}

	/** Current descriptor of a method (named classes). */
	String namedDesc(MethodEntry m) {
		return mapper.deobfuscate(m).getDesc().toString();
	}

	/** (int,World):void */
	String shortSig(MethodEntry m) {
		String d = namedDesc(m);
		StringBuilder sb = new StringBuilder("(");
		List<String> args = Names.argDescs(d);
		for (int i = 0; i < args.size(); i++) {
			if (i > 0) sb.append(',');
			sb.append(Names.javaType(args.get(i)));
		}
		return sb.append("):").append(Names.javaType(Names.returnDesc(d))).toString();
	}

	String label(MethodEntry m) {
		return className(m.getParent()) + "." + methodName(m);
	}

	String label(FieldEntry f) {
		return className(f.getParent()) + "." + fieldName(f);
	}

	// ---- structure helpers

	AccessFlags access(MethodEntry m) {
		return index.getEntryIndex().getMethodAccess(m);
	}

	boolean isStatic(MethodEntry m) {
		AccessFlags a = access(m);
		return a != null && a.isStatic();
	}

	boolean isSyntheticLike(MethodEntry m) {
		AccessFlags a = access(m);
		return a == null || a.isSynthetic() || a.isBridge();
	}

	/** True when this method is the root declaration of its override chain (inside the jar). */
	boolean isRoot(MethodEntry m) {
		Collection<MethodEntry> roots = mapper.getObfResolver().resolveEntry(m, ResolutionStrategy.RESOLVE_ROOT);
		return roots.isEmpty() || roots.contains(m);
	}

	/** The root declaration of m. Null if the root is a library method (not renamable). */
	MethodEntry root(MethodEntry m) {
		Collection<MethodEntry> roots = mapper.getObfResolver().resolveEntry(m, ResolutionStrategy.RESOLVE_ROOT);
		if (roots.isEmpty() || roots.contains(m)) return m;
		List<MethodEntry> inJar = new ArrayList<>();
		for (MethodEntry r : roots) {
			if (index.getEntryIndex().hasMethod(r)) inJar.add(r);
		}
		if (inJar.isEmpty()) return null;
		inJar.sort(Comparator.comparing(Object::toString));
		return inJar.get(0);
	}

	/** Slot (LVT index) of each parameter. */
	int[] paramSlots(MethodEntry m) {
		List<String> args = Names.argDescs(m.getDesc().toString());
		int[] slots = new int[args.size()];
		int slot = isStatic(m) ? 0 : 1;
		for (int i = 0; i < slots.length; i++) {
			slots[i] = slot;
			slot += Names.slotSize(args.get(i));
		}
		return slots;
	}

	LocalVariableEntry paramEntry(MethodEntry m, int position) {
		return new LocalVariableEntry(m, paramSlots(m)[position], "", true, null);
	}

	/** Current name of param, or null if unnamed. */
	String paramName(MethodEntry m, int position) {
		LocalVariableEntry e = paramEntry(m, position);
		if (!isNamed(e)) return null;
		return mappingOf(e).targetName();
	}

	int paramCount(MethodEntry m) {
		return Names.argDescs(m.getDesc().toString()).size();
	}

	boolean methodNeedsName(MethodEntry m) {
		return Names.INTERMEDIARY.matcher(m.getName()).matches() && !isNamed(m);
	}

	List<Integer> unnamedParams(MethodEntry m) {
		List<Integer> out = new ArrayList<>();
		for (int i = 0; i < paramCount(m); i++) {
			if (paramName(m, i) == null) out.add(i);
		}
		return out;
	}

	int methodRefs(MethodEntry m) {
		int n = 0;
		for (Entry<?> eq : mapper.getObfResolver().resolveEquivalentEntries(m)) {
			if (eq instanceof MethodEntry me) n += index.getReferenceIndex().getReferencesToMethod(me).size();
		}
		return n;
	}

	int fieldRefs(FieldEntry f) {
		return index.getReferenceIndex().getReferencesToField(f).size();
	}

	int classRefs(ClassEntry c) {
		return index.getReferenceIndex().getReferencesToClass(c).size();
	}

	List<MethodEntry> allMethods() {
		List<MethodEntry> l = new ArrayList<>(index.getEntryIndex().getMethods());
		l.sort(Comparator.comparing(Object::toString));
		return l;
	}

	List<FieldEntry> allFields() {
		List<FieldEntry> l = new ArrayList<>(index.getEntryIndex().getFields());
		l.sort(Comparator.comparing(Object::toString));
		return l;
	}

	List<ClassEntry> allClasses() {
		List<ClassEntry> l = new ArrayList<>(index.getEntryIndex().getClasses());
		l.sort(Comparator.comparing(Object::toString));
		return l;
	}

	/** Matches --in filter against obf and named full names. */
	boolean inScope(ClassEntry owner, String scope) {
		// bundled libraries (argo, jorbis, paulscode) are not ours to name
		if (!owner.getTopLevelClass().getFullName().startsWith("net/minecraft/")) return false;
		if (scope == null) return true;
		return owner.getFullName().contains(scope) || named(owner).getFullName().contains(scope);
	}
}

final class CliException extends RuntimeException {
	CliException(String msg) {
		super(msg);
	}
}
