# JSON Data Generator

[![Java 21+](https://img.shields.io/badge/Java-21%2B-orange?logo=openjdk&logoColor=white)](https://openjdk.org/projects/jdk/21/)
[![Maven](https://img.shields.io/badge/Build-Maven-C71A36?logo=apachemaven&logoColor=white)](https://maven.apache.org/)
[![Jackson 2.20.1](https://img.shields.io/badge/Jackson-2.20.1-2C7FB8)](https://github.com/FasterXML/jackson-databind)
[![RgxGen 3.1](https://img.shields.io/badge/RgxGen-3.1-6A1B9A)](https://github.com/curious-odd-man/RgxGen)
[![SLF4J 2.0.17](https://img.shields.io/badge/SLF4J-2.0.17-4E9A06)](https://www.slf4j.org/)

Generate JSON test data from a JSON Schema, then change any value to build positive or negative test cases. No DTO classes are required.

Requires **Java 21+**. Version: **1.0.0**.

## Install

From the library directory, run:

```sh
mvn install
```

Add the dependency to your test project:

```xml
<dependency>
    <groupId>io.github.stasbykov</groupId>
    <artifactId>json-data-generator</artifactId>
    <version>1.0.0</version>
    <scope>test</scope>
</dependency>
```

## Quick start

Save a schema as `src/test/resources/json-schemas/operation.json` in your test project:

```json
{
  "type": "object",
  "properties": {
    "id": {"type": "string", "format": "uuid"},
    "amount": {"type": "integer", "minimum": 1, "maximum": 100},
    "comment": {"type": "string"}
  },
  "required": ["id", "amount"]
}
```

Build a JSON string:

```java
import io.github.stasbykov.jsondata.JsonData;

String json = JsonData.builder("operation.json")
        .set("/amount", 50)
        .build();
```

The library generates the required fields and sets `amount` to `50`.

## Choose which fields to generate

| Method | Result |
| --- | --- |
| `requiredOnly()` | Required fields in each generated object. This is the default. |
| `allFields()` | All fields listed in the schema. |
| `withoutRequired()` | All fields, with required fields removed from each object. |

An optional object is not created by `requiredOnly()`, even if it has required fields inside it.

## Change the JSON

Manual changes can break schema rules. Use them to create negative test cases:

```java
String json = JsonData.builder("operation.json")
        .set("/amount", "wrong type")
        .set("/metadata/source", "test")
        .setNull("/comment")
        .omit("/id")
        .build();
```

| Method | Action |
| --- | --- |
| `set(path, value)` | Add or replace a value, object, or array. |
| `setNull(path)` | Keep the field with a JSON null value. |
| `omit(path)` | Remove an object field. A missing field is ignored. |
| `arraySize(path, size)` | Set the number of array elements. |
| `generate(path)` | Generate a value from the schema at this path. |

Changes run in the order you add them, after the base JSON is generated. Changing the fill mode does not remove these changes.
`set()` replaces the whole value; it does not merge objects. Input collections are copied when you pass them to `set()`.

Paths use JSON Pointer:

- `/customer/name` selects a nested field. Missing parents are created as objects; a scalar parent is replaced with an object.
- `/items/0` selects the first element when `items` is an array.
- An empty path (`""`) selects the root document.
- Use `~1` for `/` and `~0` for `~` inside a field name.

A number in a path does not create an array. For example, `/new/0` creates an object with a field named `"0"`.
`omit()` removes object fields only. Replace or resize an array to remove its elements.

## Arrays

Automatic generation uses `minItems` when it is present. Otherwise, it uses `default-array-size` (default: `1`), capped by `maxItems`.

| Schema | Number of elements |
| --- | ---: |
| `minItems: 2`, `maxItems: 10` | 2 |
| Only `minItems: 3` | 3 |
| Only `maxItems: 10` | 1 |
| No size rules | 1 |
| `minItems: 0` or `maxItems: 0`, with no conflicting size rules | 0 |

Manual sizes ignore the array's size and uniqueness rules:

```java
.arraySize("/items", 5)
```

Shrinking an array removes elements from the end. Growing it keeps existing elements and generates new ones from the `items` schema. Without an item schema, new elements are null.

Writing past the end of an existing array fills gaps with null. For example, setting index `4` in `["a", "b"]` produces `["a", "b", null, null, "x"]` when the new value is `"x"`.

## Validate the result

Final validation is off by default. Enable it when you want the completed JSON checked against the schema:

```java
String json = JsonData.builder("operation.json")
        .set("/amount", 50)
        .validateOnBuild()
        .build();
```

Use `.validateOnBuild(false)` to turn it off for a builder. Invalid final data causes a `JsonDataException` when validation is enabled.
The schema itself is always checked before generation. Manual changes do not bypass a schema loading or generation error.

## Repeat the same data: seed and Clock

A seed controls random values. Each builder gets a seed automatically unless you set one. **A seed does not guarantee unique data.**

The library logs the seed and time at INFO before each generation:

```text
generation started builderId=17 build=1 schema=operation.json seed=728194 clockInstant=2026-10-26T10:00:00Z clockZone=UTC
```

Use both values from the log to repeat a result:

```java
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

String json = JsonData.builder("operation.json")
        .seed(728194L)
        .clock(Clock.fixed(
                Instant.parse("2026-10-26T10:00:00Z"),
                ZoneId.of("UTC")))
        .build();
```

**The same seed alone is not enough when data depends on time.** Keep the same Clock, schema, settings, operations, and library version as well.
By default, each builder saves the current UTC time when it is created. A supplied Clock is also saved as a fixed time and zone.

You can set a seed with `.seed(42)`, programmatic configuration, `-Djsondata.seed=42`, or `jsondata.seed=42` in `jsondata.properties`.
A shared fixed seed produces the same data for otherwise identical builders. Use different seeds for a repeatable series of requests.
Use `getSeed()` and `getClock()` to read the builder's values.

Repeated builds of an unchanged builder return the same data without running generators or logging again. Changing the builder causes a new generation from its seed and another log entry.
Independent builders can run in parallel. Do not share one mutable builder between threads.

For logs, add an SLF4J-compatible logging backend to your test project and enable INFO for `io.github.stasbykov.jsondata`.
The logger is `io.github.stasbykov.jsondata.JsonDataBuilder`. JSON payloads are not logged.

## Custom generators

```java
String json = JsonData.builder("operation.json")
        .generator("/amount", context -> context.random().nextInt(1, 101))
        .generator("/createdAt", context -> context.clock().instant().toString())
        .build();
```

The context provides the path, a copy of its schema (or null), random source, Clock, and settings.
Custom generators run before manual changes, so `set()` can replace their results.
Use `context.random()` and `context.clock()` for repeatable results. Separate calls to `UUID.randomUUID()`, `Instant.now()`, or external services can change the result.

## Read the result

```java
String json = builder.build();
JsonNode tree = builder.buildTree();
MyRequest dto = builder.buildAs(MyRequest.class);
List<MyRequest> requests = builder.buildAs(new TypeReference<List<MyRequest>>() {});
```

Returned trees and DTOs are independent copies. String and tree results preserve explicit nulls. A DTO may not distinguish a missing field from a null field.

To reuse business-specific steps, wrap `JsonDataBuilder` in your own class and add methods such as `amount(value)` or `customer(id)`.

## Settings

Add `jsondata.properties` to your test resources:

```properties
jsondata.schemas.location=classpath:/json-schemas/
jsondata.fill.mode=REQUIRED_ONLY
jsondata.validation.on-build=false
jsondata.generation.max-depth=20
jsondata.generation.max-attempts=100
jsondata.generation.default-array-size=1
# jsondata.seed=42
```

Settings are applied in this order, highest priority first: builder, programmatic configuration, JVM properties, properties file, defaults.
`max-depth` limits automatic generation depth; `max-attempts` limits attempts to find a valid value.

To load schemas from a directory or use your own ObjectMapper:

```java
var config = JsonDataConfig.defaults()
        .withSchemasLocation("file:./test-data/schemas/")
        .withSeed(42);
var service = new JsonData(config, customObjectMapper);
var builder = service.newBuilder("operation.json");
```

Relative file paths start at the JVM working directory. Classpath resources can also be loaded from JAR files.
Each builder keeps the schema after its first successful load. Create a new builder to read a changed schema.
A custom ObjectMapper controls Java value conversion and DTO mapping; its serializers can affect fields passed to `set()`.

## Supported JSON Schema rules

The library supports the following subset of Draft 2020-12:

| Area | Supported rules |
| --- | --- |
| Types | object, array, string, integer, number, boolean, null, type unions |
| Fixed values | `enum`, `const` |
| Objects | `properties`, `required`, `additionalProperties` |
| Arrays | `items`, `minItems`, `maxItems`, `uniqueItems` |
| Strings | `minLength`, `maxLength`, `pattern`, `format` |
| Numbers | `minimum`, `maximum` |
| References | Internal JSON Pointer `$ref`, `$defs`; a `$ref` must be the only keyword in its schema object |
| Annotations | `title`, `description`, `default`, `examples` are accepted but do not choose generated values |

Built-in formats are `uuid`, `date`, `date-time`, `email`, `ipv4`, and `uri`.
Email uses a basic format check. URI values must be absolute Java URIs. Dates use Java time parsing.
Numbers use BigInteger/BigDecimal, and string length is counted in Unicode code points.
Type unions prefer the first non-null type.

Unsupported schema keywords cause an error. This prevents a schema rule from being silently skipped.

## String patterns

Use `pattern` to generate a string that matches a regular expression:

```json
{
  "type": "string",
  "pattern": "^[A-Z]{2}-[0-9]{4}$"
}
```

For example, this produces a value such as `AB-1234`. Combine `pattern` with
`minLength`, `maxLength`, `format`, `enum`, or `const`; all supplied rules must hold.
Pattern generation uses RgxGen and the builder's random source, so the same seed
and settings reproduce the same result.

A pattern matches anywhere in the string. Use `^` at the beginning and `$` at the
end when the whole value must match. For example, `cat` also matches `a cat here`,
while `^cat$` matches only `cat`.

Supported syntax:

- Literal BMP characters, including Cyrillic letters, and escaped punctuation.
- Character sets and ranges: `[abc]`, `[A-Z]`, `[^0-9]`.
- `.`, `\d`, `\D`, `\w`, `\W`, `\s`, `\S`, `\n`, `\r`, `\t`.
- Hex escapes: `\x41`, `\u0041`.
- Groups and alternatives: `(ab|cd)`, `(?:ab|cd)`.
- Repetitions: `?`, `*`, `+`, `{3}`, `{2,5}`, `{2,}`.
- `^` at the start of the pattern and `$` at the end.

In JSON, double each backslash: write `"pattern": "^\\d{20}$"` for 20 digits.
Matching uses Java regex rules for this subset. `\d` means ASCII digits, `\w`
means ASCII letters, digits and underscore, and `\s` means ASCII whitespace.

Lookaround, backreferences, named groups, inline flags, word boundaries,
Unicode property classes, nested character sets, and lazy or possessive
quantifiers cause an error. They are not silently ignored.

Generation checks candidates up to `jsondata.generation.max-attempts`. If none
satisfy all rules, it throws `JsonDataException` with the pattern and schema
context. Open-ended repeats use a finite per-repeat limit: the smaller of
`maxLength`, `max(32, minLength + 32)`, and 10,000. An explicit repeat minimum
still takes priority. Nested repetition work is also bounded. Patterns are
limited to 4,096 characters and 32 nested groups; generated candidates are
limited to 10,000 characters. Longer candidates are discarded and count toward
the attempt limit. These are generation limits, not extra schema rules.

When both `format` and `pattern` are present, attempts alternate between the format
generator and the pattern generator, starting with the format. Set the attempt
limit to at least two to allow both sources.

Matching uses the Java regex engine. If matching exhausts its stack, the library
throws `JsonDataException` with the pattern and schema path. The generation limits
do not guarantee that every expression can be matched within the available stack.

Manual `set()` values are still accepted without pattern checks. Enable
`validateOnBuild()` to check the final JSON against the pattern as well.

## Run tests

```sh
mvn verify
```

JUnit and Logback are used only by the library's tests. The library itself does not depend on JUnit or Spring.

## Publish a release

Publishing uses Maven Central and signed artifacts. The release workflow runs
when a non-prerelease GitHub Release is published.

Create the GitHub environment `maven-central` in this repository and add:

- `MAVEN_CENTRAL_USERNAME`: the Maven Central user token username.
- `MAVEN_CENTRAL_PASSWORD`: the Maven Central user token password.
- `MAVEN_GPG_PRIVATE_KEY`: the ASCII-armored GPG private signing key.
- `MAVEN_GPG_PASSPHRASE`: the signing key passphrase.

The Central account must have access to `io.github.stasbykov`, and the signing
public key must be available to Maven Central. Existing account credentials and
the signing key can be reused; GitHub secrets must be configured separately.

Set the release version in `pom.xml`, merge to `main`, then publish a GitHub
Release with a matching tag, such as `v1.0.0`. The workflow checks that the tag
belongs to `main` and matches the POM version, runs tests, attaches sources and
Javadoc, signs the artifacts, and publishes them. Published versions cannot be
overwritten.

Use `./mvnw clean verify` (`mvnw.cmd clean verify` on Windows) to check the build
without publishing. Dependabot checks Maven dependencies and GitHub Actions
weekly.
