package org.speckitty.commandcli.adapter.out.mission;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.speckitty.commandcli.application.write.port.MissionCatalog;
import org.speckitty.commandcli.application.write.port.MissionRef;
import org.speckitty.commandcli.domain.authority.MissionFacts;
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
        MissionRef match = readMission(directory, missionId);
        if (match != null) matches.add(match);
      }
    } catch (IOException | RuntimeException exception) {
      return new NotFound();
    }
    return matches.size() == 1 ? new Found(matches.getFirst()) : new NotFound();
  }

  @Override
  public boolean locationMatches(MissionRef mission, String requestedCheckout, String requestedDirectory, String destinationRef) {
    if (!".".equals(requestedCheckout) || destinationRef != null || checkout.root().isEmpty()) return false;
    try {
      Path actual = checkout.root().orElseThrow().resolve(requestedDirectory).toRealPath();
      Path expected = checkout.root().orElseThrow().resolve(mission.directory()).toRealPath();
      return actual.equals(expected);
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
    JsonNode root = json.readTree(Files.readString(metadata));
    if (root == null || !missionId.equals(root.path("mission_id").asString(null))) return null;
    Path real = directory.toRealPath();
    return new MissionRef(missionId, checkout.root().orElseThrow().relativize(real).toString().replace(pathSeparator(), "/"));
  }

  private java.util.Optional<Path> path(MissionRef mission) {
    return checkout.root().map(root -> root.resolve(mission.directory()));
  }

  private static String pathSeparator() {
    return java.io.File.separator;
  }
}
