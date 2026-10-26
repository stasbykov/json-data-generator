package io.github.stasbykov.jsondata;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Clock;
import java.util.Random;

/** Random and clock belong to this build. Schema is a defensive copy, or null. */
public record GenerationContext(String path, JsonNode schema, Random random, Clock clock,
                                JsonDataConfig settings) {}
