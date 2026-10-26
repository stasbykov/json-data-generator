package io.github.stasbykov.jsondata;

import java.io.InputStream;

/** Uses the caller's context loader, falling back when the thread has none. */
final class ResourceLoader {
    private ResourceLoader() {}

    static InputStream open(String name) {
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        if (loader == null) loader = ResourceLoader.class.getClassLoader();
        return loader.getResourceAsStream(name);
    }
}
