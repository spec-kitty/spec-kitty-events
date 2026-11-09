package org.speckitty.commandcli.adapter.out.mission;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.speckitty.commandcli.application.write.port.MissionCatalog;
import org.speckitty.commandcli.application.write.port.MissionRef;
import org.speckitty.commandcli.domain.authority.MissionFacts;
import org.speckitty.missionstatus.domain.model.PyText;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Fresh-per-write Mission discovery on the independently verified root checkout. */
public final class FilesystemMissionCatalog implements MissionCatalog {
  private final RootCheckout checkout;
  private final JsonMapper json;

  public FilesystemMissionCatalog(Path gcd) {
    this(gcd, JsonMapper.builder().build());
  }

  FilesystemMissionCatalog(Path gcd, JsonMapper json) {
    this.checkout = RootCheckout.of(gcd);
    this.json = json;
  }

  public java.util.Optional<Path> root() {
    return checkout.root();
  }

  @Override
  public FindResult find(String missionId) {
    if (checkout.root().isEmpty()) return new RootUnverifiable();
    List<MissionRef> matches = new ArrayList<>();
    Path missions = checkout.root().orElseThrow().resolve("kitty-specs");
    try (Stream<Path> paths = Files.list(missions)) {
      for (Path directory : paths.filter(Files::isDirectory).toList()) {
        try {
          MissionRef match = readMission(directory, missionId);
          if (match != null) matches.add(match);
        } catch (IOException | RuntimeException unreadableSibling) {
          // Core skips an unreadable sibling rather than hiding every healthy Mission.
        }
      }
    } catch (IOException | RuntimeException exception) {
      return new NotFound();
    }
    return matches.size() == 1 ? new Found(matches.getFirst()) : new NotFound();
  }

  @Override
  public boolean locationMatches(MissionRef mission, String requestedCheckout, String requestedDirectory, String destinationRef) {
    if (mission.coordinationRouted() || !".".equals(requestedCheckout) || destinationRef != null || checkout.root().isEmpty()) {
      return false;
    }
    try {
      Path root = checkout.root().orElseThrow();
      Path actual = root.resolve(requestedDirectory).toRealPath();
      Path expected = root.resolve(mission.directory()).toRealPath();
      return actual.startsWith(root) && expected.startsWith(root) && actual.equals(expected);
    } catch (IOException | RuntimeException exception) {
      return false;
    }
  }

  @Override
  public boolean wpExists(MissionRef mission, String wpId) {
    return path(mission).map(path -> TasksFiles.exists(path, wpId)).orElse(false);
  }

  @Override
  public MissionFacts facts(MissionRef mission) {
    return new DiskMissionFacts(checkout.root().orElseThrow().resolve(mission.directory()));
  }

  private MissionRef readMission(Path directory, String missionId) throws IOException {
    Path metadata = directory.resolve("meta.json");
    if (!Files.isRegularFile(metadata)) return null;
    String text = Files.readString(metadata, StandardCharsets.UTF_8);
    JsonNode root = json.readTree(text);
    if (root == null || !missionId.equals(root.path("mission_id").asString(null))) return null;
    JsonNode branch = root.path("coordination_branch");
    boolean coordinationRouted = branch.isString() && !PyText.isBlank(branch.asString());
    Path real = directory.toRealPath();
    String relative = checkout.root().orElseThrow().relativize(real).toString().replace(pathSeparator(), "/");
    return new MissionRef(missionId, relative, coordinationRouted);
  }

  private java.util.Optional<Path> path(MissionRef mission) {
    return checkout.root().map(root -> root.resolve(mission.directory()));
  }

  private static String pathSeparator() {
    return java.io.File.separator;
  }
}
