package io.github.stasbykov.jsondata;

import java.io.InputStream;
import java.util.Properties;

/** Immutable configuration. defaults() snapshots classpath properties and JVM overrides. */
public record JsonDataConfig(String schemasLocation, FillMode fillMode, boolean validationOnBuild,
                             int maxDepth, int maxAttempts, int defaultArraySize, Long seed) {
    public JsonDataConfig {
        if (schemasLocation == null || fillMode == null || maxDepth < 1 || maxAttempts < 1 || defaultArraySize < 0)
            throw new IllegalArgumentException("Invalid generation configuration");
    }
    public static JsonDataConfig defaults() {
        Properties p = new Properties();
        try (InputStream in = ResourceLoader.open("jsondata.properties")) {
            if (in != null) p.load(in);
        } catch (Exception e) { throw new JsonDataException("Cannot load jsondata.properties", e); }
        for (String key : System.getProperties().stringPropertyNames())
            if (key.startsWith("jsondata.")) p.setProperty(key, System.getProperty(key));
        try {
            String validate = p.getProperty("jsondata.validation.on-build", "false");
            if (!validate.equals("true") && !validate.equals("false")) throw new IllegalArgumentException("Invalid boolean: " + validate);
            return new JsonDataConfig(p.getProperty("jsondata.schemas.location", "classpath:/json-schemas/"),
                    FillMode.valueOf(p.getProperty("jsondata.fill.mode", "REQUIRED_ONLY")), Boolean.parseBoolean(validate),
                    Integer.parseInt(p.getProperty("jsondata.generation.max-depth", "20")),
                    Integer.parseInt(p.getProperty("jsondata.generation.max-attempts", "100")),
                    Integer.parseInt(p.getProperty("jsondata.generation.default-array-size", "1")),
                    p.containsKey("jsondata.seed") ? Long.valueOf(p.getProperty("jsondata.seed")) : null);
        } catch (RuntimeException e) { throw new JsonDataException("Invalid jsondata configuration", e); }
    }
    public JsonDataConfig withSchemasLocation(String value) {
        return new JsonDataConfig(value, fillMode, validationOnBuild, maxDepth, maxAttempts, defaultArraySize, seed);
    }
    public JsonDataConfig withSeed(long value) {
        return new JsonDataConfig(schemasLocation, fillMode, validationOnBuild, maxDepth, maxAttempts, defaultArraySize, value);
    }
}
