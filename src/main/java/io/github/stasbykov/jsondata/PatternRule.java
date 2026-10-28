package io.github.stasbykov.jsondata;

import com.github.curiousoddman.rgxgen.RgxGen;
import com.github.curiousoddman.rgxgen.config.RgxGenOption;
import com.github.curiousoddman.rgxgen.config.RgxGenProperties;
import com.github.curiousoddman.rgxgen.nodes.*;
import com.github.curiousoddman.rgxgen.visitors.NodeVisitor;
import java.util.Random;
import java.util.Optional;
import java.util.regex.Pattern;

/** A checked regex and a builder-local generator using the build's random stream. */
final class PatternRule {
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
            String expression = PatternSyntax.forGenerator(source);
            matcher = Pattern.compile(source);
            generator = createGenerator(expression);
        } catch (RuntimeException e) {
            throw error("Invalid or unsupported pattern: " + e.getMessage(), e);
        }
    }

    private int repeatLimit() {
        return (int) Math.min(MAX_GENERATED_LENGTH, Math.min((long) maxLength,
                Math.max(32L, (long) minLength + 32)));
    }

    private RgxGen createGenerator(String expression) {
        int limit = repeatLimit();
        RgxGenProperties properties = new RgxGenProperties();
        RgxGenOption.INFINITE_PATTERN_REPETITION.setInProperties(properties, limit);
        RgxGen result = RgxGen.parse(properties, expression);
        result.visit(new WorkLimit(limit));
        return result;
    }

    boolean matches(String value) {
        try {
            // JSON Schema patterns match anywhere unless the author adds anchors.
            return matcher.matcher(value).find();
        } catch (StackOverflowError e) {
            throw error("Pattern matching exceeded the regex engine stack capacity", e);
        }
    }

    /** An empty result discards this candidate without consuming the remaining attempts. */
    Optional<String> generate(Random random) {
        if (minLength > MAX_GENERATED_LENGTH) {
            throw error("Pattern generation supports minLength up to " + MAX_GENERATED_LENGTH, null);
        }
        String value = generateCandidate(random);
        if (value.length() > MAX_GENERATED_LENGTH) {
            return Optional.empty();
        }
        return Optional.of(extendToMinimum(shortenToMaximum(value), random));
    }

    private String generateCandidate(Random random) {
        try {
            return generator.generate(random);
        } catch (RuntimeException e) {
            throw error("Pattern generation failed", e);
        }
    }

    private String shortenToMaximum(String value) {
        int length = value.codePointCount(0, value.length());
        if (length <= maxLength) {
            return value;
        }
        String prefix = value.substring(0, value.offsetByCodePoints(0, maxLength));
        if (matches(prefix)) {
            return prefix;
        }
        String suffix = value.substring(value.offsetByCodePoints(0, length - maxLength));
        return matches(suffix) ? suffix : value;
    }

    private String extendToMinimum(String value, Random random) {
        int missing = minLength - value.codePointCount(0, value.length());
        if (missing <= 0) {
            return value;
        }
        String padding = padding(missing, random);
        if (matches(value + padding)) {
            return value + padding;
        }
        if (matches(padding + value)) {
            return padding + value;
        }
        return repeatToMinimum(value, missing);
    }

    private String padding(int length, Random random) {
        StringBuilder result = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            result.append((char) ('a' + random.nextInt(26)));
        }
        return result.toString();
    }

    private String repeatToMinimum(String value, int missing) {
        if (value.isEmpty()) {
            return value;
        }
        String repeated = value.repeat((minLength + value.length() - 1) / value.length());
        String candidate = repeated.substring(0, minLength);
        if (matches(candidate)) {
            return candidate;
        }
        String tail = value + value.substring(value.length() - 1).repeat(missing);
        return matches(tail) ? tail : value;
    }

    private JsonDataException error(String message, Throwable cause) {
        return new JsonDataException("path=" + schemaPath + " pattern=" + source + ": " + message, cause);
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
