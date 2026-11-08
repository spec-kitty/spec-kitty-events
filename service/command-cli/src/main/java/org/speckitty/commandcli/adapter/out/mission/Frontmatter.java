package org.speckitty.commandcli.adapter.out.mission;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.composer.Composer;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.nodes.MappingNode;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.NodeId;
import org.yaml.snakeyaml.nodes.NodeTuple;
import org.yaml.snakeyaml.nodes.ScalarNode;
import org.yaml.snakeyaml.nodes.SequenceNode;
import org.yaml.snakeyaml.nodes.Tag;
import org.yaml.snakeyaml.parser.Parser;
import org.yaml.snakeyaml.parser.ParserImpl;
import org.yaml.snakeyaml.reader.StreamReader;
import org.yaml.snakeyaml.resolver.Resolver;
import org.yaml.snakeyaml.scanner.Scanner;
import org.yaml.snakeyaml.scanner.ScannerImpl;
import org.yaml.snakeyaml.tokens.AliasToken;
import org.yaml.snakeyaml.tokens.AnchorToken;
import org.yaml.snakeyaml.tokens.DirectiveToken;
import org.yaml.snakeyaml.tokens.DocumentEndToken;
import org.yaml.snakeyaml.tokens.DocumentStartToken;
import org.yaml.snakeyaml.tokens.FlowMappingEndToken;
import org.yaml.snakeyaml.tokens.FlowMappingStartToken;
import org.yaml.snakeyaml.tokens.FlowSequenceEndToken;
import org.yaml.snakeyaml.tokens.FlowSequenceStartToken;
import org.yaml.snakeyaml.tokens.ScalarToken;
import org.yaml.snakeyaml.tokens.TagToken;
import org.yaml.snakeyaml.tokens.Token;
import org.yaml.snakeyaml.tokens.ValueToken;

/** Bounded, plain-subset YAML frontmatter parsing with core's fence, BOM and newline rules. */
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
      rejectPythonEqualDuplicateKeys(loaded);
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
    rejectNonPlainYaml(block, options);
    StreamReader reader = new StreamReader(block);
    Parser parser = new ParserImpl(new ScannerImpl(reader, options));
    Composer composer = new Composer(parser, new Yaml12Resolver(), options);
    RoundTripSafeConstructor constructor = new RoundTripSafeConstructor(options);
    constructor.setAllowDuplicateKeys(options.isAllowDuplicateKeys());
    constructor.setComposer(composer);
    Node root = composer.getSingleNode();
    validateNodeTree(root);
    return root == null ? null : constructor.constructSingle(root);
  }

  /** R19/R20/R21: only the plain YAML subset shared by SnakeYAML and ruamel is readable. */
  private static void rejectNonPlainYaml(String block, LoaderOptions options) {
    if (block.codePoints().anyMatch(codePoint ->
        codePoint == '\t' || codePoint == 0x85 || codePoint == 0x2028 || codePoint == 0x2029)) {
      throw new IllegalArgumentException("YAML 1.1 whitespace is not accepted in frontmatter");
    }
    Scanner scanner = new ScannerImpl(new StreamReader(block), options);
    String[] lines = block.split("\n", -1);
    int documentStarts = 0;
    int flowDepth = 0;
    while (!scanner.checkToken(Token.ID.StreamEnd)) {
      Token token = scanner.getToken();
      flowDepth = flowDepthAfter(token, flowDepth);
      if (token instanceof DocumentStartToken) documentStarts++;
      if (token instanceof DirectiveToken || token instanceof AnchorToken || token instanceof AliasToken
          || token instanceof TagToken || token instanceof DocumentEndToken || documentStarts > 1) {
        throw new IllegalArgumentException("frontmatter is outside the plain YAML subset");
      }
      validateFlowValue(block, token, flowDepth);
      validateBlockScalar(lines, token);
    }
  }

  private static int flowDepthAfter(Token token, int depth) {
    if (token instanceof FlowMappingStartToken || token instanceof FlowSequenceStartToken) return depth + 1;
    if (token instanceof FlowMappingEndToken || token instanceof FlowSequenceEndToken) return depth - 1;
    return depth;
  }

  private static void validateFlowValue(String block, Token token, int flowDepth) {
    if (flowDepth > 0 && token instanceof ValueToken value && !hasFlowValueSeparator(block, value)) {
      throw new IllegalArgumentException("R21: a flow value indicator requires following whitespace");
    }
  }

  private static boolean hasFlowValueSeparator(String block, ValueToken token) {
    int offset = block.offsetByCodePoints(0, token.getEndMark().getIndex());
    if (offset >= block.length()) return false;
    char next = block.charAt(offset);
    return next == ' ' || next == '\t' || next == '\n';
  }

  private static void validateBlockScalar(String[] lines, Token token) {
    if (!(token instanceof ScalarToken scalar) || !isBlockScalar(scalar)) return;
    int header = scalar.getStartMark().getLine();
    if (hasIndentationIndicator(lines[header], scalar) || header + 1 >= lines.length) return;
    String first = lines[header + 1];
    if (first.isEmpty() || !spacesOnly(first)) return;
    for (int index = header + 2; index < lines.length; index++) {
      if (lines[index].isEmpty() || spacesOnly(lines[index])) continue;
      if (leadingSpaces(lines[index]) > first.length()) {
        throw new IllegalArgumentException("more indented follow up line than first in a block scalar");
      }
      return;
    }
  }

  private static boolean isBlockScalar(ScalarToken token) {
    return token.getStyle() == DumperOptions.ScalarStyle.LITERAL
        || token.getStyle() == DumperOptions.ScalarStyle.FOLDED;
  }

  private static boolean hasIndentationIndicator(String line, ScalarToken token) {
    int column = line.offsetByCodePoints(0, token.getStartMark().getColumn());
    String header = line.substring(column + 1);
    int comment = header.indexOf('#');
    String indicators = comment < 0 ? header : header.substring(0, comment);
    return indicators.chars().anyMatch(character -> character >= '1' && character <= '9');
  }

  private static boolean spacesOnly(String line) {
    return line.chars().allMatch(character -> character == ' ');
  }

  private static int leadingSpaces(String line) {
    int count = 0;
    while (count < line.length() && line.charAt(count) == ' ') count++;
    return count;
  }

  private static void validateNodeTree(Node node) {
    if (node instanceof MappingNode mapping) {
      validateMergeKeys(mapping);
      for (NodeTuple tuple : mapping.getValue()) {
        validateNodeTree(tuple.getKeyNode());
        validateNodeTree(tuple.getValueNode());
      }
    } else if (node instanceof SequenceNode sequence) {
      sequence.getValue().forEach(Frontmatter::validateNodeTree);
    }
  }

  private static void validateMergeKeys(MappingNode mapping) {
    long mergeKeys = mapping.getValue().stream()
        .map(NodeTuple::getKeyNode)
        .filter(Frontmatter::isPlainMergeKey)
        .count();
    if (mergeKeys > 1) throw new IllegalArgumentException("duplicate YAML merge keys are unresolvable");
  }

  private static boolean isPlainMergeKey(Node node) {
    return node instanceof ScalarNode scalar && scalar.isPlain() && Tag.MERGE.equals(scalar.getTag());
  }

  private static void rejectPythonEqualDuplicateKeys(Object value) {
    if (value instanceof Map<?, ?> mapping) {
      List<Object> keys = new ArrayList<>();
      for (Map.Entry<?, ?> entry : mapping.entrySet()) {
        Object key = pythonKey(entry.getKey());
        if (keys.stream().anyMatch(other -> pythonKeysEqual(other, key))) {
          throw new IllegalArgumentException("duplicate YAML key under Python equality");
        }
        keys.add(key);
        rejectPythonEqualDuplicateKeys(entry.getKey());
        rejectPythonEqualDuplicateKeys(entry.getValue());
      }
    } else if (value instanceof List<?> sequence) {
      sequence.forEach(Frontmatter::rejectPythonEqualDuplicateKeys);
    }
  }

  private static Object pythonKey(Object key) {
    if (key instanceof Boolean bool) return bool ? BigInteger.ONE : BigInteger.ZERO;
    if (key instanceof BigInteger integer) return integer;
    if (key instanceof Integer || key instanceof Long) return BigInteger.valueOf(((Number) key).longValue());
    if (key instanceof Double number && Double.isFinite(number) && number == Math.rint(number)) {
      return new BigDecimal(number).toBigIntegerExact();
    }
    if (key instanceof List<?> sequence) return sequence.stream().map(Frontmatter::pythonKey).toList();
    if (key instanceof TaggedScalar scalar) return scalar.value();
    return key;
  }

  private static boolean pythonKeysEqual(Object left, Object right) {
    if (left instanceof Double leftNumber && leftNumber.isNaN()
        || right instanceof Double rightNumber && rightNumber.isNaN()) {
      return false;
    }
    if (left instanceof List<?> leftList && right instanceof List<?> rightList) {
      if (leftList.size() != rightList.size()) return false;
      for (int index = 0; index < leftList.size(); index++) {
        if (!pythonKeysEqual(leftList.get(index), rightList.get(index))) return false;
      }
      return true;
    }
    if (left instanceof Map<?, ?> leftMap && right instanceof Map<?, ?> rightMap) {
      return pythonMapsEqual(leftMap, rightMap);
    }
    return Objects.equals(left, right);
  }

  private static boolean pythonMapsEqual(Map<?, ?> left, Map<?, ?> right) {
    if (left.size() != right.size()) return false;
    for (Map.Entry<?, ?> leftEntry : left.entrySet()) {
      Object leftKey = pythonKey(leftEntry.getKey());
      Object leftValue = pythonKey(leftEntry.getValue());
      boolean matched = right.entrySet().stream().anyMatch(rightEntry ->
          pythonKeysEqual(leftKey, pythonKey(rightEntry.getKey()))
              && pythonKeysEqual(leftValue, pythonKey(rightEntry.getValue())));
      if (!matched) return false;
    }
    return true;
  }

  /** Preserves ruamel's non-String TaggedScalar distinction for an untagged plain equals sign. */
  private static final class RoundTripSafeConstructor extends SafeConstructor {
    private RoundTripSafeConstructor(LoaderOptions options) {
      super(options);
    }

    private Object constructSingle(Node node) {
      return constructDocument(node);
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
