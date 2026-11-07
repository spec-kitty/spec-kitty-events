package org.speckitty.commandcli.domain.authority;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.speckitty.commandcli.domain.authority.WriteFold.Transition;
import org.speckitty.missionstatus.domain.model.CodePoints;
import org.speckitty.missionstatus.domain.model.Lane;

/** First-wins dedupe plus core's code-point ordering and approved-departure lift. */
final class EventOrdering {
  private EventOrdering() {}

  static int compare(Transition left, Transition right) {
    int at = CodePoints.compare(left.at(), right.at());
    return at != 0 ? at : CodePoints.compare(left.id(), right.id());
  }

  static List<Transition> dedup(List<Transition> transitions) {
    Map<String, List<Transition>> groups = new LinkedHashMap<>();
    transitions.forEach(event -> groups.computeIfAbsent(event.id(), ignored -> new ArrayList<>()).add(event));
    List<Transition> unique = new ArrayList<>();
    groups.values().forEach(rows -> unique.add(rows.size() > 1 && rows.stream().allMatch(Transition::seed)
        ? rows.stream().min(Comparator.comparing(EventOrdering::canonical)).orElseThrow() : rows.getFirst()));
    return unique;
  }

  static List<Transition> order(List<Transition> events) {
    Map<String, String> lifted = approvedDepartureSortAt(events);
    Carry carry = carry(events, lifted);
    List<String> approvals = events.stream()
        .filter(event -> event.to() == Lane.APPROVED && event.from() != Lane.APPROVED && !event.seed())
        .map(event -> event.wpId() + "\u0000" + event.at()).toList();
    List<Transition> ordered = new ArrayList<>(events);
    ordered.sort((left, right) -> compareOrdered(left, right, carry, approvals));
    return ordered;
  }

  private static int compareOrdered(Transition left, Transition right, Carry carry, List<String> approvals) {
    int result = CodePoints.compare(carry.effective().getOrDefault(left.id(), left.at()),
        carry.effective().getOrDefault(right.id(), right.at()));
    if (result != 0) return result;
    result = Boolean.compare(afterApproval(left, carry, approvals), afterApproval(right, carry, approvals));
    if (result != 0) return result;
    result = Integer.compare(carry.sequence().getOrDefault(left.id(), 0), carry.sequence().getOrDefault(right.id(), 0));
    return result != 0 ? result : CodePoints.compare(left.id(), right.id());
  }

  private static boolean afterApproval(Transition event, Carry carry, List<String> approvals) {
    return carry.sequence().containsKey(event.id())
        || (event.from() == Lane.APPROVED && !event.seed() && approvals.contains(event.wpId() + "\u0000" + event.at()));
  }

  private static Map<String, String> approvedDepartureSortAt(List<Transition> events) {
    Map<String, List<Transition>> arrivals = new HashMap<>();
    Map<String, List<Transition>> departures = new HashMap<>();
    for (Transition event : events) {
      if (event.seed()) continue;
      if (event.to() == Lane.APPROVED && event.from() != Lane.APPROVED) {
        arrivals.computeIfAbsent(event.wpId(), ignored -> new ArrayList<>()).add(event);
      } else if (event.from() == Lane.APPROVED && event.to() != Lane.APPROVED) {
        departures.computeIfAbsent(event.wpId(), ignored -> new ArrayList<>()).add(event);
      }
    }
    Map<String, String> lifted = new HashMap<>();
    departures.forEach((wpId, wpDepartures) -> pairDepartures(wpDepartures,
        arrivals.getOrDefault(wpId, List.of()).stream().sorted(EventOrdering::compare).collect(java.util.stream.Collectors.toCollection(ArrayList::new)), lifted));
    return lifted;
  }

  private static void pairDepartures(List<Transition> departures, List<Transition> unbound, Map<String, String> lifted) {
    List<Transition> skewed = new ArrayList<>();
    for (Transition departure : departures.stream().sorted(EventOrdering::compare).toList()) {
      List<Transition> earlier = unbound.stream().filter(arrival -> CodePoints.compare(arrival.at(), departure.at()) <= 0).toList();
      if (earlier.isEmpty()) skewed.add(departure);
      else unbound.remove(earlier.getLast());
    }
    for (Transition departure : skewed) {
      List<Transition> later = unbound.stream().filter(arrival -> CodePoints.compare(arrival.at(), departure.at()) > 0).toList();
      if (!later.isEmpty()) {
        unbound.remove(later.getFirst());
        lifted.put(departure.id(), later.getFirst().at());
      }
    }
  }

  private static Carry carry(List<Transition> events, Map<String, String> lifted) {
    if (lifted.isEmpty()) return new Carry(Map.of(), Map.of());
    Map<String, String> effective = new HashMap<>(lifted);
    Map<String, Integer> sequence = new HashMap<>();
    Map<String, String> floors = new HashMap<>();
    int position = 0;
    for (Transition event : events.stream().sorted(EventOrdering::compare).toList()) {
      position++;
      String floor = floors.get(event.wpId());
      if (lifted.containsKey(event.id())) {
        floors.put(event.wpId(), floor == null ? lifted.get(event.id()) : max(floor, lifted.get(event.id())));
        sequence.put(event.id(), position);
      } else if (floor != null && CodePoints.compare(event.at(), floor) < 0) {
        effective.put(event.id(), floor);
        sequence.put(event.id(), position);
      }
    }
    return new Carry(effective, sequence);
  }

  private static String max(String left, String right) {
    return CodePoints.compare(left, right) >= 0 ? left : right;
  }

  private static String canonical(Transition event) {
    return canonicalValue(event.row());
  }

  private static String canonicalValue(Object value) {
    if (value == null) return "null";
    if (value instanceof Map<?, ?> map) {
      List<String> keys = map.keySet().stream().map(String::valueOf).sorted(CodePoints::compare).toList();
      return keys.stream().map(key -> "\"" + escape(key) + "\":" + canonicalValue(map.get(key)))
          .collect(java.util.stream.Collectors.joining(",", "{", "}"));
    }
    if (value instanceof List<?> list) {
      return list.stream().map(EventOrdering::canonicalValue).collect(java.util.stream.Collectors.joining(",", "[", "]"));
    }
    if (value instanceof String text) return "\"" + escape(text) + "\"";
    return String.valueOf(value);
  }

  private static String escape(String value) {
    return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
  }

  private record Carry(Map<String, String> effective, Map<String, Integer> sequence) {}
}
