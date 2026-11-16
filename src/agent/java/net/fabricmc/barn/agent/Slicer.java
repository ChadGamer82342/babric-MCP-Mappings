package net.fabricmc.barn.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Cuts single members out of decompiled source without parsing it. */
final class Slicer {
	private final String src;
	private final int[] depth; // brace depth before char i
	private final int[] lineStart;

	Slicer(String src) {
		this.src = src;
		this.depth = new int[src.length() + 1];
		List<Integer> starts = new ArrayList<>();
		starts.add(0);
		int d = 0;
		int i = 0;
		int n = src.length();
		while (i < n) {
			char c = src.charAt(i);
			depth[i] = d;
			if (c == '\n') starts.add(i + 1);
			if (c == '/' && i + 1 < n && src.charAt(i + 1) == '/') {
				while (i < n && src.charAt(i) != '\n') depth[i++] = d;
				continue;
			}
			if (c == '/' && i + 1 < n && src.charAt(i + 1) == '*') {
				int end = src.indexOf("*/", i + 2);
				end = end < 0 ? n : end + 2;
				for (int k = i; k < end; k++) {
					depth[k] = d;
					if (src.charAt(k) == '\n') starts.add(k + 1);
				}
				i = end;
				continue;
			}
			if (c == '"' || c == '\'') {
				int k = i + 1;
				while (k < n && src.charAt(k) != c && src.charAt(k) != '\n') k += src.charAt(k) == '\\' ? 2 : 1;
				for (int j = i; j <= k && j < n; j++) depth[j] = d;
				i = k + 1;
				continue;
			}
			if (c == '{') d++;
			else if (c == '}') d--;
			i++;
		}
		depth[n] = d;
		this.lineStart = starts.stream().mapToInt(Integer::intValue).toArray();
	}

	/** Index of the '}' closing the '{' at open. */
	private int closeOf(int open) {
		int d = depth[open] + 1;
		for (int i = open + 1; i < src.length(); i++) {
			if (src.charAt(i) == '}' && depth[i] == d) return i;
		}
		return src.length() - 1;
	}

	/** Range [start,end] of the body of a (possibly nested) class. chain = outermost first, simple names. */
	int[] classBody(List<String> chain) {
		int from = 0;
		int to = src.length();
		int[] body = {0, src.length() - 1};
		for (String name : chain) {
			Matcher m = Pattern.compile("\\b(class|interface|enum|record|@interface)\\s+" + Pattern.quote(name) + "\\b").matcher(src);
			m.region(from, to);
			boolean ok = false;
			while (m.find()) {
				int open = src.indexOf('{', m.end());
				if (open < 0) break;
				int close = closeOf(open);
				body = new int[] {open + 1, close};
				from = open + 1;
				to = close;
				ok = true;
				break;
			}
			if (!ok) return null;
		}
		return body;
	}

	/**
	 * Finds a member method declaration directly inside the class body.
	 *
	 * @param argTypes simple java type names of the parameters (generics are ignored)
	 * @return the full text of the method (modifiers, header and body) or null
	 */
	String method(int[] body, String name, List<String> argTypes) {
		if (body == null) return null;
		int bodyDepth = depth[body[0]];
		Pattern p = Pattern.compile("(?m)^[ \\t]*[^\\n;{}=]*?\\b" + Pattern.quote(name) + "\\s*\\(");
		Matcher m = p.matcher(src);
		m.region(body[0], body[1]);
		String best = null;
		int bestScore = -1;
		while (m.find()) {
			int lineBegin = m.start();
			if (depth[lineBegin] != bodyDepth) continue;
			int open = m.end() - 1;
			int close = matchParen(open);
			if (close < 0) continue;
			int after = close + 1;
			while (after < src.length() && Character.isWhitespace(src.charAt(after))) after++;
			// skip "throws X, Y"
			if (src.startsWith("throws", after)) {
				while (after < src.length() && src.charAt(after) != '{' && src.charAt(after) != ';') after++;
			}
			if (after >= src.length() || (src.charAt(after) != '{' && src.charAt(after) != ';')) continue;
			List<String> params = splitParams(src.substring(open + 1, close));
			int score = 1;
			if (params.size() == argTypes.size()) {
				score = 2;
				boolean all = true;
				for (int i = 0; i < params.size(); i++) {
					if (!typeMatches(params.get(i), argTypes.get(i))) all = false;
				}
				if (all) score = 3;
			}
			if (score > bestScore) {
				int end = src.charAt(after) == ';' ? after : closeOf(after);
				best = withLeadingAnnotations(lineBegin, end + 1);
				bestScore = score;
				if (score == 3) break;
			}
		}
		return bestScore >= 2 ? best : null;
	}

	/** Strips the declaration header from a method slice, leaving "{ ... }" (null for abstract methods). */
	static String bodyOnly(String method) {
		int open = method.indexOf('(');
		if (open < 0) return method;
		int d = 0;
		int i = open;
		for (; i < method.length(); i++) {
			char c = method.charAt(i);
			if (c == '(') d++;
			else if (c == ')' && --d == 0) break;
		}
		int brace = method.indexOf('{', i);
		int semi = method.indexOf(';', i);
		if (brace < 0 || (semi >= 0 && semi < brace)) return null;
		StringBuilder sb = new StringBuilder();
		String[] lines = method.substring(brace).split("\n", -1);
		for (int k = 0; k < lines.length; k++) {
			String l = lines[k];
			int strip = 0;
			while (k > 0 && strip < 4 && strip < l.length() && l.charAt(strip) == ' ') strip++;
			sb.append(l, strip, l.length());
			if (k < lines.length - 1) sb.append('\n');
		}
		return sb.toString();
	}

	private String withLeadingAnnotations(int start, int end) {
		return src.substring(start, end);
	}

	private static boolean typeMatches(String param, String want) {
		String t = param.trim().replaceAll("\\bfinal\\s+", "");
		int sp = t.lastIndexOf(' ');
		if (sp > 0) t = t.substring(0, sp);
		t = t.replaceAll("<[^<>]*(<[^<>]*>[^<>]*)*>", "").replace("...", "[]").trim();
		int dot = t.lastIndexOf('.');
		// compare last segments only (decompiler may print inner classes with or without outer)
		String a = dot >= 0 && !t.contains("[") ? t.substring(dot + 1) : t;
		String b = want.contains(".") && !want.contains("[") ? want.substring(want.lastIndexOf('.') + 1) : want;
		return a.equals(b) || t.equals(want);
	}

	private int matchParen(int open) {
		int d = 0;
		for (int i = open; i < src.length(); i++) {
			char c = src.charAt(i);
			if (c == '(') d++;
			else if (c == ')' && --d == 0) return i;
			else if (c == '{' || c == ';') return -1;
		}
		return -1;
	}

	private static List<String> splitParams(String s) {
		List<String> out = new ArrayList<>();
		if (s.isBlank()) return out;
		int d = 0;
		int start = 0;
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			if (c == '<' || c == '(') d++;
			else if (c == '>' || c == ')') d--;
			else if (c == ',' && d == 0) {
				out.add(s.substring(start, i));
				start = i + 1;
			}
		}
		out.add(s.substring(start));
		return out;
	}

	/** Field declaration line(s) matching name directly inside body. */
	String field(int[] body, String name) {
		if (body == null) return null;
		int bodyDepth = depth[body[0]];
		Matcher m = Pattern.compile("(?m)^[ \\t]*[^\\n(){}]*\\b" + Pattern.quote(name) + "\\b\\s*(=|;)").matcher(src);
		m.region(body[0], body[1]);
		while (m.find()) {
			if (depth[m.start()] != bodyDepth) continue;
			int end = src.indexOf(';', m.start());
			if (end < 0) continue;
			return src.substring(m.start(), end + 1);
		}
		return null;
	}

	/** Declaration header of a class: everything up to and including the opening brace. */
	String classHeader(List<String> chain) {
		int[] body = classBody(chain);
		if (body == null) return null;
		int open = body[0] - 1;
		int ls = open;
		while (ls > 0 && src.charAt(ls - 1) != '\n') ls--;
		// pull in previous lines until a blank line or previous statement end, for multi-line headers
		return src.substring(ls, open + 1).strip();
	}
}
