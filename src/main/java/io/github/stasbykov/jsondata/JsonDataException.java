package io.github.stasbykov.jsondata;

/** A schema, generation, mutation, validation or mapping failure. */
public class JsonDataException extends RuntimeException {
    public JsonDataException(String message) { super(message); }
    public JsonDataException(String message, Throwable cause) { super(message, cause); }
}
