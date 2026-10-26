package io.github.stasbykov.jsondata;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class ReviewRegressionTest {
    @TempDir Path directory;

    private JsonDataBuilder schema(String content, int maxDepth) throws Exception {
        Files.writeString(directory.resolve("schema.json"), content);
        return new JsonData(new JsonDataConfig("file:" + directory, FillMode.REQUIRED_ONLY,
                false, maxDepth, 100, 1, 42L)).newBuilder("schema.json");
    }

    @Test void rejectsInvalidPointerEscapesEvenInMultilineNames() {
        var b = JsonData.builder("operation.json");
        assertThrows(JsonDataException.class, () -> b.set("/line\ninvalid~2", 1));
        assertThrows(JsonDataException.class, () -> b.set("/invalid~\nline", 1));
        assertEquals(1, b.set("/line\n~0~1", 1).buildTree().path("line\n~/").intValue());
    }

    @Test void manualArraySizeUsesActualStructureAfterReplacingSchemaArray() {
        JsonNode n = JsonData.builder("operation.json")
                .set("/entries", Map.of())
                .arraySize("/entries/custom", 2)
                .buildTree();
        assertEquals(2, n.at("/entries/custom").size());
        assertTrue(n.at("/entries/custom/0").isNull());
    }

    @Test void customGeneratorMayTargetPropertyUnderSchemaArray() {
        JsonNode n = JsonData.builder("operation.json")
                .generator("/entries", context -> Map.of())
                .generator("/entries/custom", context -> {
                    assertNull(context.schema());
                    return 7;
                })
                .buildTree();
        assertEquals(7, n.at("/entries/custom").intValue());
    }

    @Test void validationDoesNotUseAutomaticGenerationDepthLimit() throws Exception {
        var b = schema("""
                {"type":"object", "properties":{"child":{"type":"object",
                  "properties":{"value":{"type":"integer"}}}}}
                """, 1);
        assertDoesNotThrow(() -> b.set("/child/value", 1).validateOnBuild().build());
        assertThrows(JsonDataException.class,
                () -> b.set("/child/value", "wrong").validateOnBuild().build());
    }

    @Test void classpathLoadingWorksWithoutContextClassLoader() {
        Thread thread = Thread.currentThread();
        ClassLoader saved = thread.getContextClassLoader();
        try {
            thread.setContextClassLoader(null);
            assertTrue(JsonData.builder("operation.json").buildTree().has("id"));
        } finally { thread.setContextClassLoader(saved); }
    }
}
