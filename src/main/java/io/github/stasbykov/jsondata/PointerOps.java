package io.github.stasbykov.jsondata;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.util.*;

final class PointerOps {
    private PointerOps() {}
    static List<String> tokens(String path) {
        if (path == null || (!path.isEmpty() && !path.startsWith("/")))
            throw new JsonDataException("Invalid JSON Pointer: " + path);
        if (path.isEmpty()) return List.of();
        List<String> result = new ArrayList<>();
        for (String token : path.substring(1).split("/", -1)) {
            for (int i = 0; i < token.length(); i++) {
                if (token.charAt(i) == '~') {
                    if (++i == token.length() || (token.charAt(i) != '0' && token.charAt(i) != '1'))
                        throw new JsonDataException("Invalid escape in pointer: " + path);
                }
            }
            result.add(token.replace("~1", "/").replace("~0", "~"));
        }
        return result;
    }
    static int index(String token) {
        if (!token.matches("0|[1-9][0-9]*")) throw new JsonDataException("Invalid array index: " + token);
        try { return Integer.parseInt(token); }
        catch (NumberFormatException e) { throw new JsonDataException("Array index too large: " + token, e); }
    }
    static JsonNode get(JsonNode root, List<String> path) {
        JsonNode node = root;
        for (String token : path) {
            if (node == null) return null;
            node = node.isArray() ? node.get(index(token)) : node.isObject() ? node.get(token) : null;
        }
        return node;
    }
    static JsonNode set(JsonNode node, List<String> path, JsonNode value) { return setAt(node, path, 0, value); }
    private static JsonNode setAt(JsonNode node, List<String> path, int depth, JsonNode value) {
        if (depth == path.size()) return value.deepCopy();
        if (node == null || !node.isContainerNode()) node = JsonNodeFactory.instance.objectNode();
        String token = path.get(depth);
        if (node.isArray()) {
            int i = index(token);
            ArrayNode array = (ArrayNode) node;
            while (array.size() <= i) array.addNull();
            array.set(i, setAt(array.get(i), path, depth + 1, value));
        } else {
            ObjectNode object = (ObjectNode) node;
            object.set(token, setAt(object.get(token), path, depth + 1, value));
        }
        return node;
    }
    static void omit(JsonNode root, List<String> path) {
        if (path.isEmpty()) throw new JsonDataException("omit does not support the root");
        JsonNode parent = get(root, path.subList(0, path.size() - 1));
        if (parent != null && parent.isArray()) throw new JsonDataException("omit does not support array elements");
        if (parent instanceof ObjectNode object) object.remove(path.getLast());
    }
}
