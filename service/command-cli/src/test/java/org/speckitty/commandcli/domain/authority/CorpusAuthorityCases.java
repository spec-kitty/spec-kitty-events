package org.speckitty.commandcli.domain.authority;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Converts domain corpus documents into the production authority's decoded inputs. */
public final class CorpusAuthorityCases {
  private CorpusAuthorityCases() {}

  public record Case(
      String id,
      String area,
      String oracle,
      List<LogLine> log,
      List<UnitInput> units,
      boolean batch,
      MissionFacts facts,
      Map<String, Object> expected) {
    public Judgement judge() {
      return Authority.standard().judge(log, units, batch, facts);
    }
  }

  public static List<Case> load() {
    return CorpusCases.load().stream().filter(CorpusCases.CorpusCase::domain).map(CorpusAuthorityCases::convert).toList();
  }

  public static List<String> laterLayerCaseIds() {
    return CorpusCases.load().stream().filter(testCase -> !testCase.domain()).map(CorpusCases.CorpusCase::id).sorted().toList();
  }

  private static Case convert(CorpusCases.CorpusCase testCase) {
    List<LogLine> log = new ArrayList<>();
    Object logged = testCase.value().get("log");
    for (Object row : logged instanceof List<?> values ? values : List.of()) log.add(new LogLine.Value(castMap(row)));
    List<UnitInput> units = testCase.units().stream().map(CorpusAuthorityCases::unit).toList();
    return new Case(testCase.id(), testCase.area(), testCase.oracle(), log, units,
        "batch".equals(testCase.request().get("kind")), testCase.facts(), testCase.expected());
  }

  private static UnitInput unit(Map<String, Object> value) {
    List<Map<String, Object>> rows = new ArrayList<>();
    for (Object row : value.get("rows") instanceof List<?> values ? values : List.of()) {
      rows.add(castMap(row));
    }
    return new UnitInput((String) value.get("wpId"), Operation.fromWire((String) value.get("operation")), rows);
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> castMap(Object value) {
    return (Map<String, Object>) value;
  }
}
