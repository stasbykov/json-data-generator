package io.github.stasbykov.jsondata;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;
import static org.junit.jupiter.api.Assertions.*;

class PatternTest {
    @TempDir Path directory;
    private final ObjectMapper mapper = new ObjectMapper();
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-28T10:00:00Z"), ZoneOffset.UTC);

    private ObjectNode stringSchema(String pattern) {
        return mapper.createObjectNode().put("type", "string").put("pattern", pattern);
    }
    private JsonDataBuilder builder(ObjectNode schema, long seed) throws Exception {
        Path file = Files.createTempFile(directory, "schema", ".json");
        Files.writeString(file, schema.toString());
        return new JsonData(JsonDataConfig.defaults().withSchemasLocation("file:" + directory))
                .newBuilder(file.getFileName().toString()).seed(seed).clock(CLOCK);
    }

    @ParameterizedTest
    @ValueSource(strings = {"^[0-9]{20}$", "^[A-Z]{2}-[0-9]{4}$", "^(foo|bar)-[a-z]{1,4}$",
            "^(?:ab|cd){2,3}$", "^[^0-9]{2,4}$", "^\\d{3}\\w{2}\\s$", "^a?b+c*$",
            "^x{3,}$", "^\\u0410[Б-Я]{2}$", "^\\x41\\.\\+\\?$", "^.{3}$", "", "^$"})
    void generatesMatchingStrings(String pattern) throws Exception {
        for (int seed = 0; seed < 10; seed++) {
            String result = builder(stringSchema(pattern), seed).validateOnBuild().buildTree().textValue();
            assertTrue(Pattern.compile(pattern).matcher(result).find(), () -> pattern + " did not match " + result);
        }
    }

    @Test void respectsLengthTogetherWithPattern() throws Exception {
        ObjectNode schema = stringSchema("^[A-Z]{4,8}$").put("minLength", 5).put("maxLength", 6);
        for (int seed = 0; seed < 30; seed++) {
            String value = builder(schema, seed).validateOnBuild().buildTree().textValue();
            assertTrue(value.length() >= 5 && value.length() <= 6);
            assertTrue(value.matches("[A-Z]{5,6}"));
        }
    }

    @Test void openEndedRepetitionsRespectExactLengthAcrossSeeds() throws Exception {
        for (String pattern : List.of("^[0-9]+$", "^[A-Z]*$", "^(ab)+$")) {
            for (int seed = 0; seed < 100; seed++) {
                String value = builder(stringSchema(pattern).put("minLength", 20).put("maxLength", 20), seed)
                        .validateOnBuild().buildTree().textValue();
                assertEquals(20, value.length());
                assertTrue(Pattern.compile(pattern).matcher(value).find());
            }
        }
    }

    @Test void unanchoredPatternCanBePaddedToMinLength() throws Exception {
        for (String pattern : List.of("cat", "^cat", "cat$")) {
            String value = builder(stringSchema(pattern).put("minLength", 9).put("maxLength", 9), 5)
                    .validateOnBuild().buildTree().textValue();
            assertEquals(9, value.length());
            assertTrue(Pattern.compile(pattern).matcher(value).find());
        }
    }

    @Test void validationUsesSubstringSearchAndAnchors() throws Exception {
        assertDoesNotThrow(() -> builder(stringSchema("cat"), 1).set("", "a cat here").validateOnBuild().build());
        assertThrows(JsonDataException.class,
                () -> builder(stringSchema("^cat$"), 1).set("", "a cat here").validateOnBuild().build());
        assertThrows(JsonDataException.class,
                () -> builder(stringSchema("cat"), 1).set("", "dog").validateOnBuild().build());
    }

    @Test void manualEditsRemainUnrestricted() throws Exception {
        var b = builder(stringSchema("^[0-9]{3}$"), 1);
        assertEquals("wrong", b.set("", "wrong").buildTree().textValue());
        assertThrows(JsonDataException.class, () -> b.validateOnBuild().build());
        assertEquals("wrong", b.validateOnBuild(false).buildTree().textValue());
    }

    @Test void patternIsOnlyAppliedToStrings() throws Exception {
        ObjectNode schema = stringSchema("^a$");
        schema.remove("type");
        assertEquals(12, builder(schema, 1).set("", 12).validateOnBuild().buildTree().intValue());
    }

    @Test void combinesPatternWithEnumAndConst() throws Exception {
        ObjectNode schema = stringSchema("^[A-Z]{2}$");
        schema.putArray("enum").add("bad").add("OK");
        assertEquals("OK", builder(schema, 1).validateOnBuild().buildTree().textValue());
        schema.remove("enum");
        schema.put("const", "NO");
        assertEquals("NO", builder(schema, 1).validateOnBuild().buildTree().textValue());
        schema.put("const", "bad");
        assertThrows(JsonDataException.class, () -> builder(schema, 1).build());
    }

    @Test void combinesPatternWithFormatsUsingEitherCandidateSource() throws Exception {
        ObjectNode date = stringSchema("^2026-10-28$").put("format", "date");
        assertEquals("2026-10-28", builder(date, 1).validateOnBuild().buildTree().textValue());
        ObjectNode email = stringSchema("^specific@example\\.com$").put("format", "email");
        assertEquals("specific@example.com", builder(email, 1).validateOnBuild().buildTree().textValue());
        email.put("pattern", "^no-at-sign$");
        assertThrows(JsonDataException.class, () -> builder(email, 1).build());
    }

    @Test void supportsRefsArrayItemsGenerateAndResize() throws Exception {
        ObjectNode root = mapper.createObjectNode().put("type", "object");
        root.putObject("$defs").set("code", stringSchema("^[A-Z]{2}[0-9]{3}$"));
        var props = root.putObject("properties");
        props.putObject("code").put("$ref", "#/$defs/code");
        props.putObject("items").put("type", "array").putObject("items").put("$ref", "#/$defs/code");
        var value = builder(root, 1).generate("/code").arraySize("/items", 5).validateOnBuild().buildTree();
        assertTrue(value.path("code").asText().matches("[A-Z]{2}[0-9]{3}"));
        value.path("items").forEach(item -> assertTrue(item.asText().matches("[A-Z]{2}[0-9]{3}")));
    }

    @Test void repeatsWithSeedAndKeepsBuildersIndependent() throws Exception {
        ObjectNode schema = stringSchema("^[A-Z]{8}[0-9]{8}$");
        var first = builder(schema, 42);
        String expected = first.build();
        assertEquals(expected, first.buildTree().toString());
        assertEquals(expected, builder(schema, 42).build());
        assertNotEquals(expected, builder(schema, 43).build());
        try (var pool = Executors.newFixedThreadPool(4)) {
            var tasks = new java.util.ArrayList<java.util.concurrent.Callable<String>>();
            for (int i = 0; i < 12; i++) {
                var b = builder(schema, 42);
                tasks.add(b::build);
            }
            for (var result : pool.invokeAll(tasks)) assertEquals(expected, result.get());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"[", "a{3,2}", "(?=a)a", "(?!a)b", "(?<=a)b", "(?<x>a)",
            "(a)\\1", "\\bword\\b", "(?i)abc", "a+?", "a++", "[a-z&&[^b]]", "\\p{L}", "a^b", "a$b", "\\Qx\\E"})
    void rejectsInvalidOrUnsupportedSyntaxWithContext(String pattern) throws Exception {
        var b = builder(stringSchema(pattern), 99);
        var error = assertThrows(JsonDataException.class, b::build);
        assertTrue(error.getMessage().contains("pattern="));
        assertTrue(error.getMessage().contains("seed=99"));
        assertTrue(error.getMessage().contains("path=#/pattern"));
    }

    @Test void rejectsNonStringPatternAndChecksOptionalSchemas() throws Exception {
        ObjectNode schema = stringSchema("ok").put("pattern", 123);
        assertThrows(JsonDataException.class, () -> builder(schema, 1).build());
        ObjectNode root = mapper.createObjectNode().put("type", "object");
        root.putObject("properties").set("optional", stringSchema("(?=x)x"));
        assertThrows(JsonDataException.class, () -> builder(root, 1).build());
    }

    @Test void impossibleLengthFailsWithPatternInMessage() throws Exception {
        var b = builder(stringSchema("^[A-Z]{2}$").put("minLength", 3), 1);
        var error = assertThrows(JsonDataException.class, b::build);
        assertTrue(error.getMessage().contains("max-attempts"));
        assertTrue(error.getMessage().contains("^[A-Z]{2}$"));
    }

    @Test void rejectsExcessiveNestedExpansionBeforeGenerating() throws Exception {
        var b = builder(stringSchema("^(a{10000}){10000}$"), 1);
        assertThrows(JsonDataException.class, b::build);
    }
}
