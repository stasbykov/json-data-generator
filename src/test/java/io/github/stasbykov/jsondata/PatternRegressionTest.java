package io.github.stasbykov.jsondata;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

class PatternRegressionTest {
    @TempDir Path directory;
    private final ObjectMapper mapper = new ObjectMapper();

    private JsonDataBuilder builder(ObjectNode schema, long seed, int attempts) throws Exception {
        Path file = Files.createTempFile(directory, "schema", ".json");
        Files.writeString(file, schema.toString());
        JsonDataConfig defaults = JsonDataConfig.defaults();
        JsonDataConfig config = new JsonDataConfig("file:" + directory, defaults.fillMode(), true,
                defaults.maxDepth(), attempts, defaults.defaultArraySize(), seed);
        return new JsonData(config).newBuilder(file.getFileName().toString()).seed(seed);
    }

    private ObjectNode schema(String pattern) {
        return mapper.createObjectNode().put("type", "string").put("pattern", pattern);
    }

    @Test void retriesAfterOversizedCandidate() throws Exception {
        ObjectNode schema = schema("^(a{10001}|b)$").put("maxLength", 1);
        assertEquals("b", builder(schema, 4096, 100).buildTree().textValue());
    }

    @Test void discardedCandidatesStillConsumeAttemptBudget() throws Exception {
        ObjectNode schema = schema("^(a{10001}|b)$").put("maxLength", 1);
        JsonDataException error = assertThrows(JsonDataException.class, () -> builder(schema, 4096, 1).build());
        assertTrue(error.getMessage().contains("max-attempts"));
        assertTrue(error.getMessage().contains("pattern="));
    }

    @ParameterizedTest
    @ValueSource(strings = {"[]^]", "[^]a]", "}+", "a}?", "^[]^]{3}$", "^\\{2}+$"})
    void handlesLiteralDelimiters(String expression) throws Exception {
        for (int seed = 0; seed < 10; seed++) {
            String value = builder(schema(expression), seed, 100).buildTree().textValue();
            assertTrue(Pattern.compile(expression).matcher(value).find());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"a{2}+", "a{2,}?", "a{1,3}+", "a++", "(?:ab)+?", "[a]++"})
    void stillRejectsQuantifierModifiers(String expression) {
        assertThrows(JsonDataException.class, () -> new PatternRule(expression, "#/pattern", 0, 100));
    }

    @Test void usesFormatFirstThenPatternWithinAttemptBudget() throws Exception {
        ObjectNode schema = schema("^specific@example\\.com$").put("format", "email");
        assertThrows(JsonDataException.class, () -> builder(schema, 1, 1).build());
        assertEquals("specific@example.com", builder(schema, 1, 2).buildTree().textValue());
    }

    @Test void patternOnlyWorksWithOneAttempt() throws Exception {
        assertEquals("fixed", builder(schema("^fixed$"), 1, 1).buildTree().textValue());
    }

    @Test void reportsRegexStackExhaustionWithContext() {
        PatternRule rule = new PatternRule("^(a|b)+$", "#/properties/code/pattern", 0, 10000);
        JsonDataException error = assertThrows(JsonDataException.class, () -> rule.matches("a".repeat(100000)));
        assertInstanceOf(StackOverflowError.class, error.getCause());
        assertTrue(error.getMessage().contains("path=#/properties/code/pattern"));
        assertTrue(error.getMessage().contains("pattern=^(a|b)+$"));
    }

    @Test void ordinarySeededGenerationIsUnchanged() {
        String expression = "^[A-Z]{2}-[0-9]{4}$";
        var original = com.github.curiousoddman.rgxgen.RgxGen.parse(expression);
        var rule = new PatternRule(expression, "#/pattern", 0, Integer.MAX_VALUE);
        for (int seed = 0; seed < 20; seed++) {
            assertEquals(original.generate(new Random(seed)), rule.generate(new Random(seed)).orElseThrow());
        }
    }
}
