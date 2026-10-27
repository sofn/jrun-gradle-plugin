package com.archforge.jrun;

/** Java @argfile 的引用规则：整体加双引号，反斜杠与双引号用反斜杠转义。 */
final class ArgFiles {

    private ArgFiles() {
    }

    static String quote(String arg) {
        return "\"" + arg.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
