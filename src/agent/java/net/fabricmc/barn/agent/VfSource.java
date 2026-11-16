package net.fabricmc.barn.agent;

import cuchaz.enigma.Enigma;
import cuchaz.enigma.bytecode.translators.TranslationClassVisitor;
import cuchaz.enigma.translation.representation.MethodDescriptor;
import cuchaz.enigma.translation.representation.entry.ClassEntry;
import cuchaz.enigma.translation.representation.entry.MethodEntry;
import org.jetbrains.java.decompiler.api.Decompiler;
import org.jetbrains.java.decompiler.main.extern.IContextSource;
import org.jetbrains.java.decompiler.main.extern.IFernflowerLogger;
import org.jetbrains.java.decompiler.main.extern.IResultSaver;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LocalVariableNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.jar.Manifest;

/** Decompiles classes with Vineflower, after applying the current mappings (including parameter names). */
final class VfSource {
	private final Workspace ws;
	private final Map<String, String> sources = new HashMap<>();
	private final Map<String, byte[]> remapped = new HashMap<>();
	private Map<String, Supplier<byte[]>> allClasses;

	VfSource(Workspace ws) {
		this.ws = ws;
	}

	/** Source of the outermost class (nested classes included). */
	String sourceOf(ClassEntry any) {
		ClassEntry top = any.getTopLevelClass();
		return sources.computeIfAbsent(top.getFullName(), k -> decompile(top));
	}

	/** Decompiled text of one method, or null when it cannot be located. */
	String methodSource(MethodEntry m) {
		ClassEntry owner = m.getParent();
		String src = sourceOf(owner);
		Slicer s = new Slicer(src);
		List<String> chain = chain(owner);
		int[] body = s.classBody(chain);
		if (body == null) return null;
		String name = ws.methodName(m);
		if (m.isConstructor()) name = chain.get(chain.size() - 1);
		List<String> args = new ArrayList<>();
		for (String a : Names.argDescs(ws.namedDesc(m))) args.add(Names.javaType(a));
		return s.method(body, name, args);
	}

	String fieldSource(cuchaz.enigma.translation.representation.entry.FieldEntry f) {
		Slicer s = new Slicer(sourceOf(f.getParent()));
		return s.field(s.classBody(chain(f.getParent())), ws.fieldName(f));
	}

	private List<String> chain(ClassEntry c) {
		String full = ws.named(c).getFullName();
		String simple = full.substring(full.lastIndexOf('/') + 1);
		return List.of(simple.split("\\$"));
	}

	// ---- decompile

	private Map<String, Supplier<byte[]>> classMap() {
		if (allClasses == null) {
			allClasses = new HashMap<>();
			for (ClassEntry c : ws.allClasses()) {
				String obf = c.getFullName();
				String named = ws.named(c).getFullName();
				allClasses.put(named, () -> remapped.computeIfAbsent(obf, this::remap));
			}
		}
		return allClasses;
	}

	private String decompile(ClassEntry top) {
		Map<String, Supplier<byte[]>> mine = new HashMap<>();
		for (ClassEntry c : ws.allClasses()) {
			if (c.getTopLevelClass().equals(top)) {
				String named = ws.named(c).getFullName();
				mine.put(named, classMap().get(named));
			}
		}
		Map<String, String> out = new HashMap<>();
		MemSource input = new MemSource("input", mine, false, out);
		MemSource lib = new MemSource("library", classMap(), true, null);
		IFernflowerLogger logger = new IFernflowerLogger() {
			@Override
			public void writeMessage(String message, Severity severity) {
				if (severity == Severity.ERROR && System.getenv("MAPCLI_DEBUG") != null) System.err.println("[vineflower] " + message);
			}

			@Override
			public void writeMessage(String message, Severity severity, Throwable t) {
				writeMessage(message, severity);
			}
		};
		Decompiler.builder()
				.inputs(input)
				.libraries(lib)
				.output(new NullSaver())
				.logger(logger)
				.option("dgs", "1")
				.option("asc", "1")
				.option("rsy", "1")
				.option("ind", "    ")
				.option("udv", "1")
				.build()
				.decompile();
		String key = ws.named(top).getFullName();
		String text = out.get(key);
		if (text == null) throw new CliException("Vineflower produced no output for " + key);
		return text;
	}

	/** Applies mappings to one class and adds LVT entries so parameters show their names. */
	private byte[] remap(String obfName) {
		ClassNode original = ws.project.getClassProvider().get(obfName);
		ClassWriter w0 = new ClassWriter(0);
		original.accept(w0);
		ClassNode node = new ClassNode();
		new ClassReader(w0.toByteArray()).accept(node, 0);
		injectParams(node);
		ClassWriter cw = new ClassWriter(0);
		node.accept(new TranslationClassVisitor(ws.mapper.getDeobfuscator(), Enigma.ASM_VERSION, cw));
		return cw.toByteArray();
	}

	private void injectParams(ClassNode node) {
		ClassEntry owner = new ClassEntry(node.name);
		for (MethodNode mn : node.methods) {
			if (mn.instructions.size() == 0) continue;
			MethodEntry me = new MethodEntry(owner, mn.name, new MethodDescriptor(mn.desc));
			MethodEntry root = ws.root(me);
			if (root == null) root = me;
			boolean isStatic = (mn.access & Opcodes.ACC_STATIC) != 0;
			List<String> args = Names.argDescs(mn.desc);
			int slot = isStatic ? 0 : 1;
			LabelNode start = new LabelNode();
			LabelNode end = new LabelNode();
			mn.instructions.insert(start);
			mn.instructions.add(end);
			if (mn.localVariables == null) mn.localVariables = new ArrayList<>();
			Set<Integer> paramSlots = new java.util.HashSet<>();
			int[] slots = new int[args.size()];
			for (int i = 0; i < args.size(); i++) {
				slots[i] = slot;
				paramSlots.add(slot);
				slot += Names.slotSize(args.get(i));
			}
			if (!isStatic) paramSlots.add(0);
			mn.localVariables.removeIf(lv -> paramSlots.contains(lv.index));
			if (!isStatic) {
				mn.localVariables.add(new LocalVariableNode("this", "L" + node.name + ";", null, start, end, 0));
			}
			for (int i = 0; i < args.size(); i++) {
				String name = root.equals(me) || ws.paramName(root, i) != null ? ws.paramName(root, i) : null;
				if (name == null) name = "p" + i;
				mn.localVariables.add(new LocalVariableNode(name, args.get(i), null, start, end, slots[i]));
			}
		}
	}

	// ---- vineflower plumbing

	private static final class MemSource implements IContextSource {
		private final String name;
		private final Map<String, Supplier<byte[]>> classes;
		private final boolean lazy;
		private final Map<String, String> sink;

		MemSource(String name, Map<String, Supplier<byte[]>> classes, boolean lazy, Map<String, String> sink) {
			this.name = name;
			this.classes = classes;
			this.lazy = lazy;
			this.sink = sink;
		}

		@Override
		public String getName() {
			return name;
		}

		@Override
		public Entries getEntries() {
			if (lazy) return Entries.EMPTY;
			List<Entry> list = new ArrayList<>();
			for (String c : classes.keySet()) list.add(Entry.atBase(c));
			return new Entries(list, List.of(), List.of());
		}

		@Override
		public boolean isLazy() {
			return lazy;
		}

		@Override
		public InputStream getInputStream(String resource) {
			if (!resource.endsWith(CLASS_SUFFIX)) return null;
			Supplier<byte[]> s = classes.get(resource.substring(0, resource.length() - CLASS_SUFFIX.length()));
			if (s == null) return null;
			try {
				return new ByteArrayInputStream(s.get());
			} catch (RuntimeException | Error e) {
				if (System.getenv("MAPCLI_DEBUG") != null) e.printStackTrace();
				throw e;
			}
		}

		@Override
		public IOutputSink createOutputSink(IResultSaver saver) {
			if (sink == null) return null;
			return new IOutputSink() {
				@Override
				public void begin() {
				}

				@Override
				public void acceptClass(String qualifiedName, String fileName, String content, int[] mapping) {
					sink.put(qualifiedName, content);
				}

				@Override
				public void acceptDirectory(String directory) {
				}

				@Override
				public void acceptOther(String path) {
				}

				@Override
				public void close() {
				}
			};
		}
	}

	private static final class NullSaver implements IResultSaver {
		@Override
		public void saveFolder(String path) {
		}

		@Override
		public void copyFile(String source, String path, String entryName) {
		}

		@Override
		public void saveClassFile(String path, String qualifiedName, String entryName, String content, int[] mapping) {
		}

		@Override
		public void createArchive(String path, String archiveName, Manifest manifest) {
		}

		@Override
		public void saveDirEntry(String path, String archiveName, String entryName) {
		}

		@Override
		public void copyEntry(String source, String path, String archiveName, String entry) {
		}

		@Override
		public void saveClassEntry(String path, String archiveName, String qualifiedName, String entryName, String content) {
		}

		@Override
		public void closeArchive(String path, String archiveName) {
		}
	}
}
