package io.github.stasbykov.jsondata;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.core.type.TypeReference;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class JsonDataTest {
    @TempDir Path temp;
    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-26T10:00:00Z"), ZoneId.of("UTC"));
    JsonDataBuilder builder() { return JsonData.builder("operation.json").seed(42).clock(CLOCK); }
    JsonDataBuilder schema(String json) throws Exception {
        Files.writeString(temp.resolve("schema.json"), json);
        return new JsonData(JsonDataConfig.defaults().withSchemasLocation("file:" + temp)).newBuilder("schema.json").seed(42).clock(CLOCK);
    }
    @Test void requiredOnlyIncludesNestedRequiredButNotOptionalParent() {
        var n = builder().validateOnBuild().buildTree();
        assertTrue(n.has("id")); assertEquals(2, n.path("entries").size());
        assertTrue(n.path("customer").has("name")); assertFalse(n.path("customer").has("note")); assertFalse(n.has("optional"));
    }
    @Test void fillModesAndLaterSettingsPreserveOperations() {
        assertTrue(builder().allFields().buildTree().path("optional").has("requiredChild"));
        var n = builder().withoutRequired().buildTree();
        assertFalse(n.has("id")); assertFalse(n.path("optional").has("requiredChild")); assertTrue(n.path("optional").has("note"));
        assertEquals("wrong", builder().set("/amount", "wrong").requiredOnly().buildTree().path("amount").asText());
    }
    @Test void editsIgnoreSchemaAndReplaceScalars() {
        var n = builder().set("/customer", "old").set("/customer/new", 1).set("/unknown/0/name", "x").omit("/id").buildTree();
        assertEquals(1, n.at("/customer/new").intValue()); assertFalse(n.path("customer").has("name"));
        assertTrue(n.path("unknown").isObject()); assertFalse(n.has("id"));
        assertThrows(JsonDataException.class, () -> builder().set("/amount", "wrong").validateOnBuild().build());
    }
    @Test void explicitNullVersusAbsentAndSafeOmit() {
        var n = builder().setNull("/id").omit("/amount").omit("/missing/path").set("/scalar", 1).omit("/scalar/child").buildTree();
        assertTrue(n.get("id").isNull()); assertFalse(n.has("amount")); assertFalse(n.has("missing")); assertEquals(1,n.get("scalar").intValue());
    }
    @Test void arrayExpansionAndManualSizeIgnoreLimits() {
        var n = builder().set("/entries", List.of("a", "b")).set("/entries/4", "x").buildTree().path("entries");
        assertEquals(5, n.size()); assertTrue(n.get(2).isNull()); assertTrue(n.get(3).isNull()); assertEquals("x", n.get(4).asText());
        assertEquals(10, builder().arraySize("/entries", 10).buildTree().path("entries").size());
        assertEquals(0, builder().arraySize("/entries", 0).buildTree().path("entries").size());
        assertTrue(builder().arraySize("/unknown", 2).buildTree().at("/unknown/1").isNull());
    }
    @Test void arrayOperationsAreOrderedAndPreserveElements() {
        var n = builder().set("/entries", List.of(999)).arraySize("/entries",3).buildTree().path("entries");
        assertEquals(999,n.get(0).intValue()); assertTrue(n.get(1).intValue() <= 10);
        assertEquals(1,builder().arraySize("/entries",3).set("/entries",List.of(999)).buildTree().path("entries").size());
    }
    @Test void pointersHandleEscapesAndRejectInvalidArraySyntax() {
        assertEquals(7, builder().set("/a~1b/~0",7).buildTree().path("a/b").path("~").intValue());
        assertThrows(JsonDataException.class,()->builder().set("/bad~2",1));
        assertThrows(JsonDataException.class,()->builder().set("noSlash",1));
        for(String index:List.of("-1","01","-","1.2")) assertThrows(JsonDataException.class,()->builder().set("/entries/"+index,1).build());
        assertThrows(JsonDataException.class,()->builder().omit("/entries/0").build());
        assertThrows(JsonDataException.class,()->builder().arraySize("/entries",-1));
    }
    @Test void defensiveCopiesAndReplay() {
        var b = builder(); String first = b.build();
        ((ObjectNode)b.buildTree()).put("id","changed"); assertEquals(first,b.build()); assertEquals(first,builder().build());
        List<Integer> source = new ArrayList<>(List.of(1)); b.set("/entries",source); source.add(2);
        assertEquals(1,b.buildTree().path("entries").size());
        b.set("/new",1); assertEquals(builder().buildTree().path("id"),b.buildTree().path("id"));
    }
    @Test void generatorsRunOnceAndManualEditsWin() {
        AtomicInteger calls = new AtomicInteger();
        var b = builder().set("/generated",999).generator("/generated", c -> { calls.incrementAndGet(); return c.random().nextInt(); });
        assertEquals(999,b.buildTree().path("generated").intValue()); b.build(); assertEquals(1,calls.get());
        b.set("/new",1).build(); assertEquals(2,calls.get());
    }
    record Item(int value) {}
    @Test void dtoRootReplacementAndParameterizedTypes() {
        assertEquals(new Item(1),builder().set("",Map.of("value",1)).buildAs(Item.class));
        List<Item> items = builder().set("",List.of(Map.of("value",2))).buildAs(new TypeReference<List<Item>>() {});
        assertEquals(List.of(new Item(2)),items);
        assertEquals("null",builder().setNull("").build());
    }
    @Test void autoArraySizesAreDeterministic() throws Exception {
        for (String constraints: List.of("",",\"maxItems\":10",",\"maxItems\":0",",\"minItems\":0,\"maxItems\":10",",\"minItems\":3")) {
            var b = schema("{\"type\":\"array\",\"items\":{\"type\":\"boolean\"}"+constraints+"}");
            int expected=constraints.contains("minItems\":3")?3:constraints.contains(":0")?0:1;
            assertEquals(expected,b.buildTree().size());
        }
    }
    @Test void internalRefsAndGenerate() throws Exception {
        var b = schema("{\"type\":\"object\",\"properties\":{\"x\":{\"$ref\":\"#/$defs/n\"}},\"$defs\":{\"n\":{\"const\":12}}}");
        assertEquals(12,b.generate("/x").buildTree().path("x").intValue());
        assertThrows(JsonDataException.class,()->b.generate("/unknown").build());
    }
    @Test void contradictionsAndUnsupportedFeaturesAreErrors() throws Exception {
        for(String s: List.of("{\"type\":\"array\",\"minItems\":3,\"maxItems\":2}","{\"type\":\"string\",\"patternProperties\":{}}",
                "{\"type\":\"array\",\"minItems\":3,\"uniqueItems\":true,\"items\":{\"type\":\"boolean\"}}", "{\"type\":\"integer\",\"minimum\":0.1,\"maximum\":0.9}")) {
            var b=schema(s); assertThrows(JsonDataException.class,b::build);
        }
    }
    @Test void numericEqualityUsesJsonSchemaSemantics() throws Exception {
        assertDoesNotThrow(()->schema("{\"const\":1}").set("",1.0).validateOnBuild().build());
        assertThrows(JsonDataException.class,()->schema("{\"type\":\"array\",\"uniqueItems\":true}").set("",List.of(1,1.0)).validateOnBuild().build());
    }
    @Test void mandatoryRecursionFailsWithoutHanging() throws Exception {
        var b = schema("{\"$defs\":{\"node\":{\"type\":\"object\",\"properties\":{\"child\":{\"$ref\":\"#/$defs/node\"}},\"required\":[\"child\"]}},\"type\":\"object\",\"properties\":{\"head\":{\"$ref\":\"#/$defs/node\"}},\"required\":[\"head\"]}");
        assertThrows(JsonDataException.class,b::build);
    }
    @Test void parallelBuildersAreIndependent() throws Exception {
        try(var pool=Executors.newFixedThreadPool(4)) {
            List<Callable<String>> tasks=new ArrayList<>(); for(int i=0;i<20;i++) tasks.add(()->builder().build());
            String expected=builder().build(); for(var result:pool.invokeAll(tasks)) assertEquals(expected,result.get());
        }
    }
    @Test void nonFiniteNumbersAreRejected() { assertThrows(JsonDataException.class,()->builder().set("/n",Double.NaN)); }
}
