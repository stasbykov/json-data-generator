package io.github.stasbykov.jsondata;

import com.github.curiousoddman.rgxgen.RgxGen;
import com.github.curiousoddman.rgxgen.config.RgxGenOption;
import com.github.curiousoddman.rgxgen.config.RgxGenProperties;
import com.github.curiousoddman.rgxgen.nodes.*;
import com.github.curiousoddman.rgxgen.visitors.NodeVisitor;
import java.util.Random;
import java.util.regex.Pattern;

/** A checked regex and a builder-local generator using the build's random stream. */
final class PatternRule {
    private static final int MAX_PATTERN_LENGTH = 4096;
    private static final int MAX_GENERATED_LENGTH = 10000;
    private final String source;
    private final String schemaPath;
    private final Pattern matcher;
    private final RgxGen generator;
    private final int minLength;
    private final int maxLength;

    PatternRule(String source, String schemaPath, int minLength, int maxLength) {
        this.source = source;
        this.schemaPath = schemaPath;
        this.minLength = minLength;
        this.maxLength = maxLength;
        try {
            checkSyntax(source);
            matcher = Pattern.compile(source);
            RgxGenProperties properties = new RgxGenProperties();
            // Keep open-ended repetitions finite while allowing schema-requested lengths.
            int repeatLimit = (int) Math.min(MAX_GENERATED_LENGTH, Math.min((long) maxLength,
                    Math.max(32L, (long) minLength + 32)));
            RgxGenOption.INFINITE_PATTERN_REPETITION.setInProperties(properties, repeatLimit);
            generator = RgxGen.parse(properties, source);
            generator.visit(new WorkLimit(repeatLimit));
        } catch (RuntimeException e) {
            throw error("Invalid or unsupported pattern: " + e.getMessage(), e);
        }
    }

    boolean matches(String value) {
        // JSON Schema patterns match anywhere unless the author adds anchors.
        return matcher.matcher(value).find();
    }

    String generate(Random random) {
        if (minLength > MAX_GENERATED_LENGTH)
            throw error("Pattern generation supports minLength up to " + MAX_GENERATED_LENGTH, null);
        String value;
        try { value = generator.generate(random); }
        catch (RuntimeException e) { throw error("Pattern generation failed", e); }
        if (value.length() > MAX_GENERATED_LENGTH)
            throw error("Generated pattern value exceeds " + MAX_GENERATED_LENGTH + " characters", null);
        int length = value.codePointCount(0, value.length());
        if (length > maxLength) {
            String prefix = value.substring(0, value.offsetByCodePoints(0, maxLength));
            String suffix = value.substring(value.offsetByCodePoints(0, length - maxLength));
            if (matches(prefix)) value = prefix;
            else if (matches(suffix)) value = suffix;
        }
        int missing = minLength - value.codePointCount(0, value.length());
        if (missing > 0) {
            // Try padding on either side; never return padding that breaks an anchor.
            StringBuilder padding = new StringBuilder(missing);
            for (int i = 0; i < missing; i++) padding.append((char) ('a' + random.nextInt(26)));
            if (matches(value + padding)) value += padding;
            else if (matches(padding + value)) value = padding + value;
            else if (!value.isEmpty()) {
                // Repeating existing text can satisfy a minimum without breaking a character class.
                String repeated = value.repeat((minLength + value.length() - 1) / value.length());
                String candidate = repeated.substring(0, minLength);
                String tail = value + value.substring(value.length() - 1).repeat(missing);
                if (matches(candidate)) value = candidate;
                else if (matches(tail)) value = tail;
            }
        }
        return value;
    }

    private JsonDataException error(String message, Throwable cause) {
        return new JsonDataException("path=" + schemaPath + " pattern=" + source + ": " + message, cause);
    }

    /** Reject syntax that the generator ignores or interprets differently. */
    private static void checkSyntax(String source) {
        if (source.length() > MAX_PATTERN_LENGTH) throw new IllegalArgumentException("Pattern exceeds 4096 characters");
        boolean inClass = false;
        int groupDepth = 0;
        for (int i = 0; i < source.length(); i++) {
            char c = source.charAt(i);
            if (Character.isSurrogate(c)) throw new IllegalArgumentException("Use BMP characters in patterns");
            if (c == '\\') {
                if (++i == source.length()) throw new IllegalArgumentException("Trailing escape");
                char escaped = source.charAt(i);
                if (escaped == 'u' || escaped == 'x') {
                    int count = escaped == 'u' ? 4 : 2;
                    if (i + count >= source.length()) throw new IllegalArgumentException("Incomplete hex escape");
                    String hex = source.substring(i + 1, i + 1 + count);
                    if (!hex.matches("[0-9a-fA-F]+")) throw new IllegalArgumentException("Invalid hex escape");
                    if (Character.isSurrogate((char) Integer.parseInt(hex, 16)))
                        throw new IllegalArgumentException("Surrogate escapes are not supported");
                    i += count;
                } else if ("dDwWsSnrt\\.^$|?*+()[]{}-/".indexOf(escaped) < 0) {
                    throw new IllegalArgumentException("Unsupported escape: \\" + escaped);
                }
                continue;
            }
            if (inClass) {
                if (c == '[' || (c == '&' && i + 1 < source.length() && source.charAt(i + 1) == '&'))
                    throw new IllegalArgumentException("Nested classes and class intersections are not supported");
                if (c == ']') inClass = false;
                continue;
            }
            if (c == '[') { inClass = true; continue; }
            if (c == '^' && i != 0) throw new IllegalArgumentException("^ is supported only at the beginning");
            if (c == '$' && i != source.length() - 1) throw new IllegalArgumentException("$ is supported only at the end");
            if (c == '(') {
                if (++groupDepth > 32) throw new IllegalArgumentException("Pattern nesting exceeds 32 groups");
                if (i + 1 < source.length() && source.charAt(i + 1) == '?') {
                    if (!source.startsWith("(?:", i))
                        throw new IllegalArgumentException("Only ordinary and non-capturing groups are supported");
                    i += 2;
                }
            }
            if (c == ')') groupDepth--;
            if ((c == '*' || c == '+' || c == '?' || c == '}') && i + 1 < source.length()
                    && (source.charAt(i + 1) == '?' || source.charAt(i + 1) == '+'))
                throw new IllegalArgumentException("Lazy and possessive quantifiers are not supported");
        }
    }

    /** Bound nested repetitions before a candidate can allocate a large string. */
    private static final class WorkLimit implements NodeVisitor {
        private static final long LIMIT = 100000;
        private final int repeatLimit;
        private long work;

        WorkLimit(int repeatLimit) { this.repeatLimit = repeatLimit; }
        private long measure(Node node) {
            WorkLimit child = new WorkLimit(repeatLimit);
            node.visit(child);
            return child.work;
        }
        private void set(long value) {
            if (value > LIMIT) throw new IllegalArgumentException("Pattern generation work exceeds " + LIMIT);
            work = value;
        }
        @Override public void visit(SymbolSet node) { set(1); }
        @Override public void visit(FinalSymbol node) { set(Math.max(1, node.getValue().length())); }
        @Override public void visit(Sequence node) {
            long total = 1;
            for (Node child : node.getNodes()) { total += measure(child); set(total); }
            set(total);
        }
        @Override public void visit(Choice node) {
            long largest = 0;
            for (Node child : node.getNodes()) largest = Math.max(largest, measure(child));
            set(largest + 1);
        }
        @Override public void visit(Repeat node) {
            long count = node.getMax() < 0 ? Math.max(node.getMin(), repeatLimit) : node.getMax();
            set(1 + count * (1 + measure(node.getNode())));
        }
        @Override public void visit(Group node) { set(1 + measure(node.getNode())); }
        @Override public void visit(GroupRef node) { throw new IllegalArgumentException("Backreferences are not supported"); }
        @Override public void visit(NotSymbol node) { throw new IllegalArgumentException("Lookaround is not supported"); }
    }
}
