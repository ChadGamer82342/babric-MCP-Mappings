package net.fabricmc.barn.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** Small helpers for names and descriptors. */
final class Names {
	/** Obfuscated (intermediary) names: the things that need a real name. */
	static final Pattern INTERMEDIARY = Pattern.compile("(class|field|method)_\\d+");
	/** Mapped names that are really still placeholders (MCP leftovers such as field_28146_a or par1). */
	static final Pattern PLACEHOLDER = Pattern.compile("(class|field|method|func)_\\d+(_\\w+)?|par\\d+\\w*|p_\\d+_\\d+_|var\\d+|param\\d+|p\\d+");
	static final Pattern IDENT = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*");
	static final Set<String> KEYWORDS = Set.of("abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class",
			"const", "continue", "default", "do", "double", "else", "enum", "extends", "final", "finally", "float", "for", "goto",
			"if", "implements", "import", "instanceof", "int", "interface", "long", "native", "new", "package", "private",
			"protected", "public", "return", "short", "static", "strictfp", "super", "switch", "synchronized", "this", "throw",
			"throws", "transient", "try", "void", "volatile", "while", "true", "false", "null", "_");

	private Names() {
	}

	/** net/minecraft/src/Foo$Bar -> Foo.Bar */
	static String simple(String internal) {
		return internal.substring(internal.lastIndexOf('/') + 1).replace('$', '.');
	}

	/** Last segment only: net/minecraft/src/Foo$Bar -> Bar */
	static String innermost(String internal) {
		String s = internal.substring(internal.lastIndexOf('/') + 1);
		return s.substring(s.lastIndexOf('$') + 1);
	}

	static String javaType(String desc) {
		int dims = 0;
		while (desc.charAt(dims) == '[') dims++;
		String base = switch (desc.charAt(dims)) {
			case 'B' -> "byte";
			case 'C' -> "char";
			case 'D' -> "double";
			case 'F' -> "float";
			case 'I' -> "int";
			case 'J' -> "long";
			case 'S' -> "short";
			case 'Z' -> "boolean";
			case 'V' -> "void";
			default -> simple(desc.substring(dims + 1, desc.length() - 1));
		};
		return base + "[]".repeat(dims);
	}

	/** Splits the argument part of a method descriptor into field descriptors. */
	static List<String> argDescs(String methodDesc) {
		List<String> out = new ArrayList<>();
		int i = 1;
		while (methodDesc.charAt(i) != ')') {
			int start = i;
			while (methodDesc.charAt(i) == '[') i++;
			if (methodDesc.charAt(i) == 'L') i = methodDesc.indexOf(';', i);
			i++;
			out.add(methodDesc.substring(start, i));
		}
		return out;
	}

	static String returnDesc(String methodDesc) {
		return methodDesc.substring(methodDesc.indexOf(')') + 1);
	}

	static int slotSize(String fieldDesc) {
		return fieldDesc.equals("J") || fieldDesc.equals("D") ? 2 : 1;
	}

	static boolean isLowerCamel(String s) {
		return Character.isLowerCase(s.charAt(0)) && !s.contains("_");
	}

	static boolean isUpperCamel(String s) {
		return Character.isUpperCase(s.charAt(0)) && !s.contains("_");
	}

	static boolean isUpperSnake(String s) {
		return s.matches("[A-Z][A-Z0-9]*(_[A-Z0-9]+)*");
	}
}
