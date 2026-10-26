package io.github.stasbykov.jsondata;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.time.*;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

/** Mutable, thread-confined builder. Returned data never exposes internal state. */
public final class JsonDataBuilder {
    private static final Logger LOG = LoggerFactory.getLogger(JsonDataBuilder.class);
    private static final AtomicLong IDS = new AtomicLong();
    private final long id = IDS.incrementAndGet();
    private final String schemaName;
    private final JsonDataConfig config;
    private final ObjectMapper mapper;
    private final List<Operation> operations = new ArrayList<>();
    private final Map<String, Function<GenerationContext, ?>> generators = new LinkedHashMap<>();
    private long seed;
    private long buildNumber;
    private Clock clock;
    private FillMode mode;
    private boolean validate;
    private JsonNode cached;
    private SchemaEngine engine;

    JsonDataBuilder(String schemaName, JsonDataConfig config, ObjectMapper mapper) {
        this.schemaName = Objects.requireNonNull(schemaName);
        this.config = config;
        this.mapper = mapper;
        seed = config.seed() == null ? ThreadLocalRandom.current().nextLong() : config.seed();
        clock = Clock.fixed(Instant.now(), ZoneOffset.UTC);
        mode = config.fillMode();
        validate = config.validationOnBuild();
    }
    public JsonDataBuilder seed(long value) { seed = value; return invalidate(); }
    public long getSeed() { return seed; }
    public Clock getClock() { return clock; }
    public JsonDataBuilder clock(Clock value) { clock = Clock.fixed(value.instant(), value.getZone()); return invalidate(); }
    public JsonDataBuilder requiredOnly() { mode = FillMode.REQUIRED_ONLY; return invalidate(); }
    public JsonDataBuilder allFields() { mode = FillMode.ALL_FIELDS; return invalidate(); }
    public JsonDataBuilder withoutRequired() { mode = FillMode.WITHOUT_REQUIRED; return invalidate(); }
    public JsonDataBuilder validateOnBuild() { return validateOnBuild(true); }
    public JsonDataBuilder validateOnBuild(boolean enabled) { validate = enabled; return invalidate(); }
    public JsonDataBuilder set(String path, Object value) {
        var tokens = PointerOps.tokens(path);
        JsonNode snapshot = value(value);
        operations.add(new Operation(path, (root, random) -> PointerOps.set(root, tokens, snapshot)));
        return invalidate();
    }
    public JsonDataBuilder setNull(String path) { return set(path, null); }
    public JsonDataBuilder omit(String path) {
        var tokens = PointerOps.tokens(path);
        if (tokens.isEmpty()) throw new JsonDataException("omit does not support root");
        operations.add(new Operation(path, (root, random) -> { PointerOps.omit(root, tokens); return root; }));
        return invalidate();
    }
    public JsonDataBuilder arraySize(String path, int size) {
        var tokens = PointerOps.tokens(path);
        if (size < 0) throw new JsonDataException("path=" + path + ": Negative array size");
        operations.add(new Operation(path, (root, random) -> {
            JsonNode current = PointerOps.get(root, tokens);
            ArrayNode array = current instanceof ArrayNode a ? a.deepCopy() : JsonNodeFactory.instance.arrayNode();
            while (array.size() > size) array.remove(array.size() - 1);
            JsonNode schema = engine.at(tokens);
            JsonNode items = schema == null ? null : schema.get("items");
            while (array.size() < size) {
                JsonNode next = items == null ? NullNode.instance : engine.generate(items, mode, random, clock, path + "/" + array.size(), 0);
                if (mode == FillMode.WITHOUT_REQUIRED && items != null) engine.removeRequired(items, next, 0);
                array.add(next);
            }
            return PointerOps.set(root, tokens, array);
        }));
        return invalidate();
    }
    public JsonDataBuilder generate(String path) {
        var tokens = PointerOps.tokens(path);
        operations.add(new Operation(path, (root, random) -> {
            JsonNode schema = engine.at(tokens);
            if (schema == null) throw new JsonDataException("No schema for generate");
            JsonNode next = engine.generate(schema, mode, random, clock, path, 0);
            if (mode == FillMode.WITHOUT_REQUIRED) engine.removeRequired(schema, next, 0);
            return PointerOps.set(root, tokens, next);
        }));
        return invalidate();
    }
    public JsonDataBuilder generator(String path, Function<GenerationContext, ?> generator) {
        PointerOps.tokens(path);
        generators.put(path, Objects.requireNonNull(generator));
        return invalidate();
    }
    public String build() { return result().toString(); }
    public JsonNode buildTree() { return result().deepCopy(); }
    public <T> T buildAs(Class<T> type) {
        try { return mapper.convertValue(result().deepCopy(), type); }
        catch (IllegalArgumentException e) { throw failure("Mapping to " + type.getTypeName(), e); }
    }
    public <T> T buildAs(TypeReference<T> type) {
        try { return mapper.convertValue(result().deepCopy(), type); }
        catch (IllegalArgumentException e) { throw failure("Mapping to " + type.getType(), e); }
    }
    private JsonDataBuilder invalidate() { cached = null; return this; }
    private JsonNode result() {
        if (cached != null) return cached;
        LOG.info("generation started builderId={} build={} schema={} seed={} clockInstant={} clockZone={}",
                id, ++buildNumber, schemaName, seed, clock.instant(), clock.getZone());
        try {
            if (engine == null) engine = new SchemaEngine(schemaName, config);
            Random random = new Random(seed);
            JsonNode root = engine.generate(engine.root(), mode, random, clock, "", 0);
            if (mode == FillMode.WITHOUT_REQUIRED) engine.removeRequired(engine.root(), root, 0);
            for (var e : generators.entrySet()) {
                var tokens = PointerOps.tokens(e.getKey());
                JsonNode schema = engine.at(tokens);
                JsonDataConfig effective = new JsonDataConfig(config.schemasLocation(), mode, validate, config.maxDepth(),
                        config.maxAttempts(), config.defaultArraySize(), seed);
                Object generated;
                try { generated = e.getValue().apply(new GenerationContext(e.getKey(), schema == null ? null : schema.deepCopy(), random, clock, effective)); }
                catch (RuntimeException ex) { throw new JsonDataException("generator path=" + e.getKey(), ex); }
                root = PointerOps.set(root, tokens, value(generated));
            }
            for (Operation operation : operations) {
                try { root = operation.action.apply(root, random); }
                catch (RuntimeException e) { throw new JsonDataException("operation path=" + operation.path + ": " + e.getMessage(), e); }
            }
            if (validate) engine.validate(root);
            cached = root.deepCopy();
            return cached;
        } catch (RuntimeException e) { throw failure(e.getMessage(), e); }
    }
    private JsonNode value(Object value) {
        try {
            JsonNode node = mapper.valueToTree(value);
            checkJson(node);
            return node.deepCopy();
        } catch (RuntimeException e) { throw failure("Cannot convert value to JSON", e); }
    }
    private static void checkJson(JsonNode value) {
        if (value.isPojo() || value.isMissingNode() || value.isBinary()) throw new JsonDataException("Unsupported JSON node");
        if (value.isFloatingPointNumber() && !value.isBigDecimal() && !Double.isFinite(value.doubleValue()))
            throw new JsonDataException("Non-finite number is not JSON");
        if (value.isContainerNode()) value.forEach(JsonDataBuilder::checkJson);
    }
    private JsonDataException failure(String reason, Throwable cause) {
        return new JsonDataException("schema=" + schemaName + " seed=" + seed + ": " + reason, cause);
    }
    private record Operation(String path, Action action) {}
    @FunctionalInterface private interface Action { JsonNode apply(JsonNode root, Random random); }
}
