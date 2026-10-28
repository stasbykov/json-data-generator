package io.github.stasbykov.jsondata;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.io.*;
import java.math.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;

/** Generates and validates values for the supported schema keywords. */
final class SchemaEngine {
    private static final Set<String> KEYS = Set.of("$schema", "$defs", "$ref", "title", "description", "default", "examples",
            "type", "properties", "required", "additionalProperties", "items", "minItems", "maxItems", "uniqueItems",
            "minLength", "maxLength", "pattern", "minimum", "maximum", "enum", "const", "format");
    private static final Set<String> TYPES = Set.of("object", "array", "string", "integer", "number", "boolean", "null");
    private static final Set<String> FORMATS = Set.of("uuid", "date", "date-time", "email", "ipv4", "uri");
    private final JsonDataConfig config;
    private final JsonNode root;
    private final Map<JsonNode, PatternRule> patterns = new IdentityHashMap<>();
    SchemaEngine(String name, JsonDataConfig config) {
        this.config = config;
        ObjectMapper parser = new ObjectMapper().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        String location = config.schemasLocation();
        try (InputStream in = location.startsWith("classpath:")
                ? ResourceLoader.open(
                    (location.substring(10) + (location.endsWith("/") ? "" : "/") + name).replaceFirst("^/", ""))
                : location.startsWith("file:") ? Files.newInputStream(Path.of(location.substring(5)).resolve(name))
                : null) {
            if (in == null) throw new JsonDataException("Schema unavailable: " + location + name);
            root = parser.readTree(in);
            check(root, "#");
        } catch (IOException e) { throw new JsonDataException("Cannot load schema " + name, e); }
    }
    JsonNode root() { return root; }
    private void check(JsonNode s, String path) {
        if (s == null || (!s.isObject() && !s.isBoolean())) fail(path, "Schema must be an object or boolean");
        if (s.isBoolean()) return;
        s.fieldNames().forEachRemaining(k -> { if (!KEYS.contains(k)) fail(path, "Unsupported keyword: " + k); });
        if (s.has("$schema") && !s.get("$schema").asText().equals("https://json-schema.org/draft/2020-12/schema"))
            fail(path, "Only the documented Draft 2020-12 subset is supported");
        if (s.has("$ref")) {
            String ref = s.get("$ref").asText();
            if (!ref.startsWith("#/")) fail(path, "Only internal JSON Pointer references are supported");
            if (s.size() != 1) fail(path, "$ref must be the only keyword in its schema object");
            if (root.at(ref.substring(1)).isMissingNode()) fail(path, "Unresolved reference " + ref);
        }
        if (s.has("type")) {
            JsonNode type = s.get("type");
            if (type.isTextual()) { if (!TYPES.contains(type.textValue())) fail(path, "Invalid type"); }
            else if (type.isArray() && !type.isEmpty()) {
                Set<String> seen = new HashSet<>();
                for (JsonNode t : type) if (!t.isTextual() || !TYPES.contains(t.textValue()) || !seen.add(t.textValue())) fail(path, "Invalid type union");
            } else fail(path, "Invalid type");
        }
        for (String key : List.of("minItems", "maxItems", "minLength", "maxLength"))
            if (s.has(key) && (!s.get(key).isIntegralNumber() || !s.get(key).canConvertToInt() || s.get(key).intValue() < 0)) fail(path, "Invalid " + key);
        for (String key : List.of("minimum", "maximum")) if (s.has(key) && !s.get(key).isNumber()) fail(path, "Invalid " + key);
        if (s.has("pattern")) {
            if (!s.get("pattern").isTextual()) fail(path + "/pattern", "pattern must be a string");
            patterns.put(s, new PatternRule(s.get("pattern").textValue(), path + "/pattern",
                    s.path("minLength").asInt(0), s.path("maxLength").asInt(Integer.MAX_VALUE)));
        }
        if (s.has("uniqueItems") && !s.get("uniqueItems").isBoolean()) fail(path, "Invalid uniqueItems");
        if (s.has("enum") && (!s.get("enum").isArray() || s.get("enum").isEmpty())) fail(path, "Invalid enum");
        if (s.has("format") && !FORMATS.contains(s.get("format").asText())) fail(path, "Unsupported format");
        if (s.has("required")) {
            if (!s.get("required").isArray()) fail(path, "required must be an array");
            Set<String> names = new HashSet<>();
            for (JsonNode n : s.get("required")) if (!n.isTextual() || !names.add(n.textValue())) fail(path, "Invalid required entry");
        }
        for (String key : List.of("properties", "$defs")) if (s.has(key)) {
            if (!s.get(key).isObject()) fail(path, key + " must be an object");
            s.get(key).fields().forEachRemaining(e -> check(e.getValue(), path + "/" + key + "/" + e.getKey()));
        }
        for (String key : List.of("items", "additionalProperties")) if (s.has(key)) check(s.get(key), path + "/" + key);
    }
    JsonNode resolve(JsonNode s) {
        Set<String> refs = new HashSet<>();
        while (s != null && s.has("$ref")) {
            String ref = s.get("$ref").asText();
            if (!refs.add(ref)) fail(ref, "Reference-only cycle");
            s = root.at(ref.substring(1));
        }
        return s;
    }
    JsonNode at(List<String> path) {
        JsonNode s = root;
        for (String token : path) {
            s = resolve(s);
            if (s == null) return null;
            if ("array".equals(type(s))) {
                // Schema lookup must not reject a manually replaced object with named keys.
                if (!token.matches("0|[1-9][0-9]*")) return null;
                s = s.get("items");
            }
            else {
                JsonNode child = s.path("properties").get(token);
                s = child != null ? child : s.get("additionalProperties");
            }
        }
        return resolve(s);
    }
    private String type(JsonNode s) {
        JsonNode type = s.get("type");
        if (type == null) return s.has("properties") || s.has("required") ? "object" : s.has("items") ? "array" : "string";
        if (type.isTextual()) return type.textValue();
        for (JsonNode t : type) if (!t.asText().equals("null")) return t.asText();
        return "null";
    }
    JsonNode generate(JsonNode raw, FillMode mode, Random random, Clock clock, String path, int depth) {
        if (depth > config.maxDepth()) fail(path, "Maximum generation depth exceeded");
        JsonNode s = resolve(raw);
        if (s == null || s.equals(BooleanNode.TRUE)) return NullNode.instance;
        if (s.equals(BooleanNode.FALSE)) fail(path, "False schema cannot generate a value");
        for (int attempt = 0; attempt < config.maxAttempts(); attempt++) {
            JsonNode value = candidate(s, mode, random, clock, path, depth, attempt);
            if (value != null && valid(s, value)) return value;
        }
        fail(path, "No valid value within max-attempts; constraints may be contradictory"
                + (s.has("pattern") ? "; pattern=" + s.get("pattern").asText() : ""));
        return null;
    }
    private JsonNode candidate(JsonNode s, FillMode mode, Random random, Clock clock, String path, int depth, int attempt) {
        if (s.has("const")) return s.get("const").deepCopy();
        if (s.has("enum")) return s.get("enum").get(random.nextInt(s.get("enum").size())).deepCopy();
        return switch (type(s)) {
            case "object" -> {
                ObjectNode object = JsonNodeFactory.instance.objectNode();
                Set<String> required = required(s);
                Set<String> names = new LinkedHashSet<>(required);
                if (mode != FillMode.REQUIRED_ONLY) s.path("properties").fieldNames().forEachRemaining(names::add);
                for (String name : names) {
                    JsonNode child = s.path("properties").get(name);
                    if (child == null) child = s.get("additionalProperties");
                    if (depth >= config.maxDepth() && !required.contains(name)) continue;
                    object.set(name, generate(child, mode, random, clock, path + "/" + name, depth + 1));
                }
                yield object;
            }
            case "array" -> {
                int count = s.has("minItems") ? s.get("minItems").intValue()
                        : Math.min(config.defaultArraySize(), s.path("maxItems").asInt(Integer.MAX_VALUE));
                if (count > s.path("maxItems").asInt(Integer.MAX_VALUE)) fail(path, "minItems exceeds maxItems");
                ArrayNode array = JsonNodeFactory.instance.arrayNode();
                for (int i = 0; i < count; i++) {
                    JsonNode item = null;
                    for (int n = 0; n < config.maxAttempts(); n++) {
                        item = generate(s.get("items"), mode, random, clock, path + "/" + i, depth + 1);
                        boolean duplicate = false;
                        if (s.path("uniqueItems").asBoolean()) for (JsonNode old : array) if (equal(old, item)) { duplicate = true; break; }
                        if (!duplicate) break;
                        item = null;
                    }
                    if (item == null) fail(path, "Cannot generate enough unique items");
                    array.add(item);
                }
                yield array;
            }
            case "integer", "number" -> {
                BigDecimal min = s.has("minimum") ? s.get("minimum").decimalValue() : s.has("maximum") ? s.get("maximum").decimalValue().subtract(BigDecimal.valueOf(100)) : BigDecimal.ZERO;
                BigDecimal max = s.has("maximum") ? s.get("maximum").decimalValue() : min.add(BigDecimal.valueOf(100));
                if (min.compareTo(max) > 0) fail(path, "minimum exceeds maximum");
                if (type(s).equals("integer")) {
                    BigInteger low = min.setScale(0, RoundingMode.CEILING).toBigIntegerExact();
                    BigInteger high = max.setScale(0, RoundingMode.FLOOR).toBigIntegerExact();
                    BigInteger width = high.subtract(low).add(BigInteger.ONE);
                    if (width.signum() <= 0) fail(path, "No integer in interval");
                    BigInteger offset;
                    do { offset = new BigInteger(width.bitLength(), random); } while (offset.compareTo(width) >= 0);
                    yield BigIntegerNode.valueOf(low.add(offset));
                }
                yield DecimalNode.valueOf(min.add(max.subtract(min).multiply(BigDecimal.valueOf(random.nextDouble()))));
            }
            case "boolean" -> BooleanNode.valueOf(random.nextBoolean());
            case "null" -> NullNode.instance;
            case "string" -> stringCandidate(s, random, clock, path, attempt);
            default -> throw new JsonDataException("Unsupported generation type: " + type(s));
        };
    }

    private JsonNode stringCandidate(JsonNode schema, Random random, Clock clock, String path, int attempt) {
        PatternRule pattern = patterns.get(schema);
        if (usePattern(pattern, schema.has("format"), attempt)) {
            // null means this candidate was discarded; generate() owns the attempt budget.
            return pattern.generate(random).map(TextNode::valueOf).orElse(null);
        }
        return TextNode.valueOf(formattedString(schema, random, clock, path));
    }

    private boolean usePattern(PatternRule pattern, boolean hasFormat, int attempt) {
        if (pattern == null) {
            return false;
        }
        // Preserve format-first ordering and the seeded random stream.
        return !hasFormat || attempt % 2 == 1;
    }

    private String formattedString(JsonNode s, Random random, Clock clock, String path) {
        String format = s.path("format").asText();
        String value = switch (format) {
            case "uuid" -> new UUID((random.nextLong() & 0xffffffffffff0fffL) | 0x4000L,
                    (random.nextLong() & 0x3fffffffffffffffL) | 0x8000000000000000L).toString();
            case "date" -> LocalDate.now(clock).toString();
            case "date-time" -> clock.instant().toString();
            case "email" -> "user" + random.nextInt(100000) + "@example.com";
            case "ipv4" -> "192.0.2." + random.nextInt(256);
            case "uri" -> "https://example.com/" + random.nextInt(100000);
            default -> plainString(s, random, path);
        };
        return value;
    }

    private String plainString(JsonNode s, Random random, String path) {
        int length = s.path("minLength").asInt(Math.min(8, s.path("maxLength").asInt(8)));
        if (length > s.path("maxLength").asInt(Integer.MAX_VALUE)) fail(path, "minLength exceeds maxLength");
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < length; i++) b.append((char) ('a' + random.nextInt(26)));
        return b.toString();
    }

    void removeRequired(JsonNode raw, JsonNode value, int depth) {
        if (depth > config.maxDepth()) return;
        JsonNode s = resolve(raw);
        if (s == null) return;
        if (value instanceof ObjectNode object) {
            required(s).forEach(object::remove);
            object.fields().forEachRemaining(e -> removeRequired(s.path("properties").get(e.getKey()), e.getValue(), depth + 1));
        } else if (value.isArray()) for (JsonNode child : value) removeRequired(s.get("items"), child, depth + 1);
    }
    private Set<String> required(JsonNode s) {
        Set<String> result = new LinkedHashSet<>();
        s.path("required").forEach(n -> result.add(n.asText()));
        return result;
    }
    void validate(JsonNode value) { if (!valid(root, value)) fail("", "Final JSON violates schema"); }
    private boolean valid(JsonNode raw, JsonNode v) {
        JsonNode s = resolve(raw);
        if (s == null || s.equals(BooleanNode.TRUE)) return true;
        if (s.equals(BooleanNode.FALSE)) return false;
        if (s.has("type")) {
            JsonNode t = s.get("type");
            boolean match = t.isTextual() && matches(t.asText(), v);
            if (t.isArray()) for (JsonNode item : t) match |= matches(item.asText(), v);
            if (!match) return false;
        }
        if (s.has("const") && !equal(s.get("const"), v)) return false;
        if (s.has("enum")) {
            boolean found = false;
            for (JsonNode item : s.get("enum")) found |= equal(item, v);
            if (!found) return false;
        }
        if (v.isNumber()) {
            if (s.has("minimum") && v.decimalValue().compareTo(s.get("minimum").decimalValue()) < 0) return false;
            if (s.has("maximum") && v.decimalValue().compareTo(s.get("maximum").decimalValue()) > 0) return false;
        }
        if (v.isTextual()) {
            int length = v.textValue().codePointCount(0, v.textValue().length());
            if (length < s.path("minLength").asInt(0) || length > s.path("maxLength").asInt(Integer.MAX_VALUE)) return false;
            if (s.has("pattern") && !patterns.get(s).matches(v.textValue())) return false;
            if (s.has("format") && !formatValid(s.get("format").asText(), v.textValue())) return false;
        }
        if (v.isArray()) {
            if (v.size() < s.path("minItems").asInt(0) || v.size() > s.path("maxItems").asInt(Integer.MAX_VALUE)) return false;
            for (int i = 0; i < v.size(); i++) {
                if (!valid(s.get("items"), v.get(i))) return false;
                if (s.path("uniqueItems").asBoolean()) for (int j = 0; j < i; j++) if (equal(v.get(i), v.get(j))) return false;
            }
        }
        if (v.isObject()) {
            for (String key : required(s)) if (!v.has(key)) return false;
            for (Iterator<Map.Entry<String, JsonNode>> it = v.fields(); it.hasNext();) {
                var e = it.next();
                JsonNode child = s.path("properties").get(e.getKey());
                if (!valid(child == null ? s.get("additionalProperties") : child, e.getValue())) return false;
            }
        }
        return true;
    }
    private static boolean matches(String t, JsonNode v) {
        return switch (t) {
            case "object" -> v.isObject(); case "array" -> v.isArray(); case "string" -> v.isTextual();
            case "integer" -> v.isNumber() && v.decimalValue().stripTrailingZeros().scale() <= 0;
            case "number" -> v.isNumber(); case "boolean" -> v.isBoolean(); case "null" -> v.isNull(); default -> false;
        };
    }
    private static boolean equal(JsonNode a, JsonNode b) {
        if (a.isNumber() && b.isNumber()) return a.decimalValue().compareTo(b.decimalValue()) == 0;
        if (a.isArray() && b.isArray()) {
            if (a.size() != b.size()) return false;
            for (int i = 0; i < a.size(); i++) if (!equal(a.get(i), b.get(i))) return false;
            return true;
        }
        if (a.isObject() && b.isObject()) {
            if (a.size() != b.size()) return false;
            var fields = a.fields();
            while (fields.hasNext()) { var e = fields.next(); if (!b.has(e.getKey()) || !equal(e.getValue(), b.get(e.getKey()))) return false; }
            return true;
        }
        return a.equals(b);
    }
    private static boolean formatValid(String format, String value) {
        try {
            return switch (format) {
                case "uuid" -> value.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
                case "date" -> { LocalDate.parse(value); yield true; }
                case "date-time" -> { OffsetDateTime.parse(value); yield true; }
                case "email" -> value.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+");
                case "ipv4" -> {
                    String[] parts = value.split("\\.", -1);
                    boolean ok = parts.length == 4;
                    for (String part : parts) ok &= part.matches("0|[1-9][0-9]{0,2}") && Integer.parseInt(part) <= 255;
                    yield ok;
                }
                case "uri" -> new java.net.URI(value).isAbsolute();
                default -> false;
            };
        } catch (Exception e) { return false; }
    }
    private static void fail(String path, String message) { throw new JsonDataException("path=" + path + ": " + message); }
}
