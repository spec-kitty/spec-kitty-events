package org.speckitty.commandcli.adapter.out.mission;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.composer.Composer;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.events.CollectionStartEvent;
import org.yaml.snakeyaml.events.DocumentStartEvent;
import org.yaml.snakeyaml.events.Event;
import org.yaml.snakeyaml.events.ScalarEvent;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.NodeId;
import org.yaml.snakeyaml.nodes.ScalarNode;
import org.yaml.snakeyaml.nodes.Tag;
import org.yaml.snakeyaml.parser.Parser;
import org.yaml.snakeyaml.parser.ParserImpl;
import org.yaml.snakeyaml.reader.StreamReader;
import org.yaml.snakeyaml.resolver.Resolver;
import org.yaml.snakeyaml.scanner.Scanner;
import org.yaml.snakeyaml.scanner.ScannerImpl;

/** Bounded, tag-free YAML frontmatter parsing with core's fence, BOM and newline rules. */
public final class Frontmatter {
  private static final int MAX_BLOCK_BYTES = 64 * 1024;
  // A task file is documentation, not a status log. Sixteen MiB is ample for authored WPs while
  // keeping an untrusted whole-file UTF-8 decode bounded; anything larger fails closed.
  private static final int MAX_FILE_BYTES = 16 * 1024 * 1024;

  private Frontmatter() {}

  public static Optional<Map<String, Object>> read(Path file) {
    try (InputStream input = Files.newInputStream(file)) {
      byte[] bytes = input.readNBytes(MAX_FILE_BYTES + 1);
      if (bytes.length > MAX_FILE_BYTES) return Optional.empty();
      return readText(file.getFileName().toString(), decode(bytes));
    } catch (IOException | RuntimeException exception) {
      return Optional.empty();
    }
  }

  /** The same reader over an in-memory document, shared by the corpus adapter. */
  public static Optional<Map<String, Object>> readText(String filename, String document) {
    try {
      String text = universalNewlines(document.startsWith("\uFEFF") ? document.substring(1) : document);
      if (!text.startsWith("---")) return Optional.empty();
      String[] lines = text.split("\n", -1);
      int closing = closingFence(lines);
      if (closing < 0 || frontmatterBytes(lines, closing) > MAX_BLOCK_BYTES) return Optional.empty();
      LoaderOptions options = new LoaderOptions();
      options.setAllowDuplicateKeys(false);
      String block = String.join("\n", java.util.Arrays.copyOfRange(lines, 1, closing));
      Object loaded = load(block, options);
      if (loaded == null) loaded = new LinkedHashMap<String, Object>();
      if (!(loaded instanceof Map<?, ?> map)) return Optional.empty();
      @SuppressWarnings("unchecked")
      Map<String, Object> result = (Map<String, Object>) map;
      if (filename.startsWith("WP") && !result.containsKey("dependencies")) {
        result.put("dependencies", new java.util.ArrayList<>());
      }
      return Optional.of(result);
    } catch (RuntimeException exception) {
      return Optional.empty();
    }
  }

  private static Object load(String block, LoaderOptions options) {
    rejectExplicitTagsAndDirectives(block, options);
    StreamReader reader = new StreamReader(block);
    Parser parser = new ParserImpl(new ScannerImpl(reader, options));
    Composer composer = new Composer(parser, new Yaml12Resolver(), options);
    RoundTripSafeConstructor constructor = new RoundTripSafeConstructor(options);
    constructor.setAllowDuplicateKeys(options.isAllowDuplicateKeys());
    constructor.setComposer(composer);
    return constructor.getSingleData(Object.class);
  }

  /** R19: authored tags and directives make the complete declaration unreadable. */
  private static void rejectExplicitTagsAndDirectives(String block, LoaderOptions options) {
    Parser parser = new ParserImpl(new ScannerImpl(new StreamReader(block), options));
    while (!parser.checkEvent(Event.ID.StreamEnd)) {
      Event event = parser.getEvent();
      if (event instanceof ScalarEvent scalar && scalar.getTag() != null
          || event instanceof CollectionStartEvent collection && collection.getTag() != null
          || event instanceof DocumentStartEvent document
              && (document.getVersion() != null
                  || document.getTags() != null && !document.getTags().isEmpty())) {
        throw new IllegalArgumentException("explicit YAML tags and directives are not accepted");
      }
    }
  }

  /** Preserves ruamel's non-String TaggedScalar distinction for an untagged plain equals sign. */
  private static final class RoundTripSafeConstructor extends SafeConstructor {
    private RoundTripSafeConstructor(LoaderOptions options) {
      super(options);
    }

    @Override
    protected Object constructObject(Node node) {
      if (node instanceof ScalarNode scalar && Tag.STR.equals(scalar.getTag())
          && scalar.isPlain() && scalar.getValue().equals("=")) {
        return new TaggedScalar(scalar.getValue());
      }
      return super.constructObject(node);
    }
  }

  record TaggedScalar(String value) {}

  private static int closingFence(String[] lines) {
    for (int index = 1; index < lines.length; index++) {
      if (rightStrip(lines[index]).equals("---")) return index;
    }
    return -1;
  }

  private static int frontmatterBytes(String[] lines, int closing) {
    return String.join("\n", java.util.Arrays.copyOfRange(lines, 1, closing)).getBytes(StandardCharsets.UTF_8).length;
  }

  private static String rightStrip(String value) {
    int end = value.length();
    while (end > 0 && pythonWhitespace(value.charAt(end - 1))) end--;
    return value.substring(0, end);
  }

  private static boolean pythonWhitespace(char value) {
    return Character.isWhitespace(value) || Character.isSpaceChar(value) || value == 0x85;
  }

  private static String universalNewlines(String value) {
    return value.replace("\r\n", "\n").replace('\r', '\n');
  }

  private static String decode(byte[] bytes) throws CharacterCodingException {
    return StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes))
        .toString();
  }

  /** SnakeYAML defaults to YAML 1.1; core's ruamel reader resolves these words as YAML 1.2 strings. */
  private static final class Yaml12Resolver extends Resolver {
    private static final java.util.regex.Pattern YAML11_ONLY_BOOL =
        java.util.regex.Pattern.compile("^(?:yes|Yes|YES|no|No|NO|on|On|ON|off|Off|OFF)$");

    @Override
    public Tag resolve(NodeId kind, String value, boolean implicit) {
      if (kind == NodeId.scalar && implicit && YAML11_ONLY_BOOL.matcher(value).matches()) return Tag.STR;
      return super.resolve(kind, value, implicit);
    }
  }
}
