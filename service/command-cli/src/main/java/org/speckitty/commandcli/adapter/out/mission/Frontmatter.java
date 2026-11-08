package org.speckitty.commandcli.adapter.out.mission;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/** Bounded, tag-free YAML frontmatter parsing; malformed input is absent, never an escaping exception. */
public final class Frontmatter {
  private static final int MAX_BLOCK_BYTES = 64 * 1024;

  private Frontmatter() {}

  @SuppressWarnings("unchecked")
  public static Optional<Map<String, Object>> read(Path file) {
    try (InputStream input = Files.newInputStream(file)) {
      String text = new String(input.readNBytes(MAX_BLOCK_BYTES + 1), StandardCharsets.UTF_8).replace("\r\n", "\n");
      if (!text.startsWith("---\n")) return Optional.empty();
      int close = text.indexOf("\n---", 4);
      if (close < 0 || close > MAX_BLOCK_BYTES || (close + 4 < text.length() && text.charAt(close + 4) != '\n')) {
        return Optional.empty();
      }
      Object loaded = new Yaml(new SafeConstructor(new LoaderOptions())).load(text.substring(4, close));
      return loaded instanceof Map<?, ?> map ? Optional.of((Map<String, Object>) map) : Optional.empty();
    } catch (IOException | RuntimeException exception) {
      return Optional.empty();
    }
  }
}
