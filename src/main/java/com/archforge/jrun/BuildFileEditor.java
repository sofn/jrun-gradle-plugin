package com.archforge.jrun;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.gradle.api.InvalidUserDataException;

/**
 * 改写构建脚本里的顶层 {@code dependencies { }}（./jrun add 用）。
 *
 * <p>只做最小的文本改动：定位顶层块时跳过字符串、注释和嵌套块（{@code buildscript { dependencies { } }}
 * 之类不会被误改）；插入的行沿用块内已有的缩进和换行符；已存在同一个 group:artifact 时只升级版本。
 */
final class BuildFileEditor {

    /** group:artifact[:version][:classifier][@ext]；version 允许范围写法，如 [1.0,2.0)。 */
    private static final Pattern COORDINATE = Pattern.compile(
            "[A-Za-z0-9_.\\-]+:[A-Za-z0-9_.\\-]+(:[A-Za-z0-9_.+\\-\\[\\](),]+)?(:[A-Za-z0-9_.\\-]+)?(@[A-Za-z0-9]+)?");
    private static final Pattern CONFIGURATION = Pattern.compile("[A-Za-z][A-Za-z0-9]*");
    private static final String DEFAULT_INDENT = "    ";

    private BuildFileEditor() {
    }

    static String addDependency(String script, boolean kotlinDsl, String configuration, String coordinate) {
        if (!COORDINATE.matcher(coordinate).matches()) {
            throw new InvalidUserDataException("Not a dependency coordinate: '" + coordinate
                    + "' (expected group:artifact[:version])");
        }
        if (!CONFIGURATION.matcher(configuration).matches()) {
            throw new InvalidUserDataException("Not a configuration name: '" + configuration + "'");
        }
        String eol = script.contains("\r\n") ? "\r\n" : "\n";
        String declaration = kotlinDsl
                ? configuration + "(\"" + coordinate + "\")"
                : configuration + " '" + coordinate + "'";

        int[] block = findTopLevelBlock(script, "dependencies");
        if (block == null) {
            String head = script.isEmpty() || script.endsWith("\n") ? script : script + eol;
            return head + (head.isEmpty() ? "" : eol)
                    + "dependencies {" + eol + DEFAULT_INDENT + declaration + eol + "}" + eol;
        }
        int open = block[0];
        int close = block[1];
        String body = script.substring(open + 1, close);

        // 已有同一个 group:artifact：原地替换坐标（升级版本），保留原来的配置名和写法
        String[] parts = coordinate.split(":");
        Matcher existing = Pattern.compile("([\"'])" + Pattern.quote(parts[0] + ":" + parts[1]) + "(:[^\"']*)?\\1")
                .matcher(body);
        if (existing.find()) {
            return script.substring(0, open + 1) + body.substring(0, existing.start())
                    + existing.group(1) + coordinate + existing.group(1)
                    + body.substring(existing.end()) + script.substring(close);
        }

        String indent = indentOf(body);
        int closingLine = script.lastIndexOf('\n', close - 1) + 1;
        if (closingLine > open && script.substring(closingLine, close).isBlank()) {
            // 右括号独占一行：插在它前面
            return script.substring(0, closingLine) + indent + declaration + eol + script.substring(closingLine);
        }
        // 右括号和内容在同一行（如 dependencies {}）：展开成多行
        int openLine = script.lastIndexOf('\n', open) + 1;
        String outer = script.substring(openLine, open).replaceAll("\\S.*", "");
        return script.substring(0, close).replaceAll("[ \\t]+$", "") + eol + indent + declaration + eol
                + outer + script.substring(close);
    }

    /** 块内第一条非空内容行的缩进；块是空的或只有一行时用 4 个空格。 */
    private static String indentOf(String body) {
        String[] lines = body.split("\\R", -1);
        for (int i = 1; i < lines.length; i++) {
            if (!lines[i].isBlank()) {
                return lines[i].substring(0, lines[i].length() - lines[i].stripLeading().length());
            }
        }
        return DEFAULT_INDENT;
    }

    /** 顶层 {@code keyword { … }} 的 {左括号下标, 右括号下标}；不存在时返回 null。 */
    static int[] findTopLevelBlock(String s, String keyword) {
        int depth = 0;
        for (int i = 0; i < s.length(); i++) {
            int next = skipNonCode(s, i);
            if (next != i) {
                i = next - 1;
                continue;
            }
            char c = s.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
            } else if (depth == 0 && s.startsWith(keyword, i) && isBoundary(s, i - 1)
                    && isBoundary(s, i + keyword.length())) {
                int j = i + keyword.length();
                while (j < s.length() && Character.isWhitespace(s.charAt(j))) {
                    j++;
                }
                if (j < s.length() && s.charAt(j) == '{') {
                    int close = matchingBrace(s, j);
                    if (close > 0) {
                        return new int[] {j, close};
                    }
                }
            }
        }
        return null;
    }

    private static int matchingBrace(String s, int open) {
        int depth = 0;
        for (int i = open; i < s.length(); i++) {
            int next = skipNonCode(s, i);
            if (next != i) {
                i = next - 1;
                continue;
            }
            if (s.charAt(i) == '{') {
                depth++;
            } else if (s.charAt(i) == '}' && --depth == 0) {
                return i;
            }
        }
        return -1;
    }

    /** 下标 i 处若是注释或字符串，返回其后第一个字符的下标；否则原样返回 i。 */
    private static int skipNonCode(String s, int i) {
        if (s.startsWith("//", i)) {
            int end = s.indexOf('\n', i);
            return end < 0 ? s.length() : end;
        }
        if (s.startsWith("/*", i)) {
            int end = s.indexOf("*/", i + 2);
            return end < 0 ? s.length() : end + 2;
        }
        for (String triple : new String[] {"\"\"\"", "'''"}) {
            if (s.startsWith(triple, i)) {
                int end = s.indexOf(triple, i + 3);
                return end < 0 ? s.length() : end + 3;
            }
        }
        char c = s.charAt(i);
        if (c == '"' || c == '\'') {
            for (int j = i + 1; j < s.length(); j++) {
                if (s.charAt(j) == '\\') {
                    j++;
                } else if (s.charAt(j) == c || s.charAt(j) == '\n') {
                    return j + 1;
                }
            }
            return s.length();
        }
        return i;
    }

    private static boolean isBoundary(String s, int index) {
        if (index < 0 || index >= s.length()) {
            return true;
        }
        char c = s.charAt(index);
        return !Character.isJavaIdentifierPart(c) && c != '.';
    }
}
