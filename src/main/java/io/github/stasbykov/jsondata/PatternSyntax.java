package io.github.stasbykov.jsondata;

/** Checks the supported syntax and adapts literal class delimiters for RgxGen. */
final class PatternSyntax {
    private static final int MAX_PATTERN_LENGTH = 4096;
    private static final int MAX_GROUP_DEPTH = 32;
    private final String source;
    private final StringBuilder normalized = new StringBuilder();
    private int index;
    private int groupDepth;

    private PatternSyntax(String source) {
        this.source = source;
    }

    static String forGenerator(String source) {
        if (source.length() > MAX_PATTERN_LENGTH) {
            throw new IllegalArgumentException("Pattern exceeds " + MAX_PATTERN_LENGTH + " characters");
        }
        return new PatternSyntax(source).scan();
    }

    private String scan() {
        while (index < source.length()) {
            char c = source.charAt(index);
            checkBmp(c);
            switch (c) {
                case '\\' -> escape();
                case '[' -> characterClass();
                case '(' -> group();
                case ')' -> {
                    groupDepth--;
                    append();
                }
                case '^', '$' -> anchor(c);
                case '*', '+', '?' -> {
                    append();
                    rejectModifier();
                }
                case '{' -> repetitionOrLiteral();
                default -> append();
            }
        }
        return normalized.toString();
    }

    private void append() {
        normalized.append(source.charAt(index++));
    }

    private void escape() {
        int start = index++;
        if (index == source.length()) {
            throw new IllegalArgumentException("Trailing escape");
        }
        char escaped = source.charAt(index++);
        if (escaped == 'u' || escaped == 'x') {
            hexEscape(escaped == 'u' ? 4 : 2);
        } else if ("dDwWsSnrt\\.^$|?*+()[]{}-/".indexOf(escaped) < 0) {
            throw new IllegalArgumentException("Unsupported escape: \\" + escaped);
        }
        normalized.append(source, start, index);
    }

    private void hexEscape(int count) {
        if (index + count > source.length()) {
            throw new IllegalArgumentException("Incomplete hex escape");
        }
        String hex = source.substring(index, index + count);
        if (!hex.matches("[0-9a-fA-F]+")) {
            throw new IllegalArgumentException("Invalid hex escape");
        }
        checkBmp((char) Integer.parseInt(hex, 16));
        index += count;
    }

    private void characterClass() {
        append();
        if (index < source.length() && source.charAt(index) == '^') {
            append();
        }
        boolean first = true;
        while (index < source.length()) {
            char c = source.charAt(index);
            checkBmp(c);
            if (c == ']' && !first) {
                append();
                return;
            }
            if (c == '[' || source.startsWith("&&", index)) {
                throw new IllegalArgumentException("Nested classes and class intersections are not supported");
            }
            if (c == '\\') {
                escape();
            } else {
                if (c == ']' && first) {
                    normalized.append('\\');
                }
                append();
            }
            first = false;
        }
        throw new IllegalArgumentException("Unclosed character class");
    }

    private void group() {
        if (++groupDepth > MAX_GROUP_DEPTH) {
            throw new IllegalArgumentException("Pattern nesting exceeds " + MAX_GROUP_DEPTH + " groups");
        }
        append();
        if (index < source.length() && source.charAt(index) == '?') {
            if (!source.startsWith("?:", index)) {
                throw new IllegalArgumentException("Only ordinary and non-capturing groups are supported");
            }
            append();
            append();
        }
    }

    private void anchor(char c) {
        if (c == '^' && index != 0) {
            throw new IllegalArgumentException("^ is supported only at the beginning");
        }
        if (c == '$' && index != source.length() - 1) {
            throw new IllegalArgumentException("$ is supported only at the end");
        }
        append();
    }

    private void repetitionOrLiteral() {
        int end = source.indexOf('}', index);
        if (end < 0 || !source.substring(index + 1, end).matches("[0-9]+(,[0-9]*)?")) {
            append();
            return;
        }
        normalized.append(source, index, end + 1);
        index = end + 1;
        rejectModifier();
    }

    private void rejectModifier() {
        if (index < source.length() && (source.charAt(index) == '?' || source.charAt(index) == '+')) {
            throw new IllegalArgumentException("Lazy and possessive quantifiers are not supported");
        }
    }

    private static void checkBmp(char c) {
        if (Character.isSurrogate(c)) {
            throw new IllegalArgumentException("Use BMP characters in patterns");
        }
    }
}
