package io.github.stasbykov.jsondata;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Objects;

/** Stateless factory; each builder owns its schema snapshot and random stream. */
public final class JsonData {
    private final JsonDataConfig config;
    private final ObjectMapper mapper;
    public JsonData(JsonDataConfig config) { this(config, new ObjectMapper()); }
    public JsonData(JsonDataConfig config, ObjectMapper mapper) {
        this.config = Objects.requireNonNull(config);
        this.mapper = Objects.requireNonNull(mapper).copy();
    }
    public static JsonDataBuilder builder(String schema) { return new JsonData(JsonDataConfig.defaults()).newBuilder(schema); }
    public JsonDataBuilder newBuilder(String schema) { return new JsonDataBuilder(schema, config, mapper.copy()); }
}
