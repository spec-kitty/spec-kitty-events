package org.speckitty.commandcli.adapter.in.http.operations;

import com.networknt.schema.Error;
import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.yaml.snakeyaml.Yaml;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The schemas of contracts/mission-write, read from the split contract (the build passes its root as {@code commandCli.contractRoot}) and
 * inlined: every {@code $ref} to a file is replaced by that file's schema, and {@code examples} and {@code x-*} annotations are dropped. A
 * response body is then validated with the networknt validator (JSON Schema 2020-12), as the read service's contract tests do.
 */
public final class ContractSchemas {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final SchemaRegistry REGISTRY = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);
  private static final AtomicInteger SEQUENCE = new AtomicInteger();

  private ContractSchemas() {}

  public static Path root() {
    return Path.of(System.getProperty("commandCli.contractRoot", "../../contracts")).resolve("mission-write");
  }

  /** The inlined schema of {@code schemas/<name>.yaml}. */
  public static JsonNode schema(String name) {
    return inline(root().resolve("schemas").resolve(name + ".yaml"));
  }

  /** The violations of {@code json} against the schema {@code name}; empty when it validates. */
  public static List<String> violations(String name, String json) {
    Schema compiled = REGISTRY.getSchema(SchemaLocation.of("urn:command-cli:schema-" + SEQUENCE.incrementAndGet()), schema(name));
    return compiled.validate(json, InputFormat.JSON).stream().map(Error::toString).toList();
  }

  /** The values of an enum-of-consts schema such as WriteRefusalCode. */
  public static List<String> constants(String name) {
    JsonNode schema = schema(name);
    java.util.ArrayList<String> values = new java.util.ArrayList<>();
    schema.path("oneOf").forEach(option -> values.add(option.path("const").asString()));
    schema.path("enum").forEach(value -> values.add(value.asString()));
    return values;
  }

  public static JsonNode yaml(Path file) {
    try {
      Object loaded = new Yaml().load(Files.readString(file));
      return JSON.valueToTree(loaded);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static JsonNode inline(Path file) {
    return resolve(yaml(file), file.getParent());
  }

  private static JsonNode resolve(JsonNode node, Path directory) {
    if (node.isArray()) {
      tools.jackson.databind.node.ArrayNode array = JSON.createArrayNode();
      node.forEach(item -> array.add(resolve(item, directory)));
      return array;
    }
    if (!node.isObject()) {
      return node;
    }
    JsonNode ref = node.get("$ref");
    if (ref != null && ref.isString() && !ref.asString().startsWith("#")) {
      Path target = directory.resolve(ref.asString()).normalize();
      return inline(target);
    }
    ObjectNode out = JSON.createObjectNode();
    Iterator<Map.Entry<String, JsonNode>> fields = node.properties().iterator();
    while (fields.hasNext()) {
      Map.Entry<String, JsonNode> field = fields.next();
      String key = field.getKey();
      if (key.equals("examples") || key.startsWith("x-")) {
        continue;
      }
      // a "properties" map holds property names, not keywords: resolve each value but keep every name
      out.set(key, resolve(field.getValue(), directory));
    }
    return out;
  }
}
