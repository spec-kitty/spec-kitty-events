package org.speckitty.missionstatus.domain.model;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Optional;

/**
 * Python 3.11's {@code datetime.fromisoformat}, ported grammar-piece by grammar-piece from CPython's pure-Python reference. The stored
 * micros are naive-local or UTC-epoch micros, matching the generator's two vector fields. No system clock or default time zone is used.
 */
public final class PyIsoDateTime {

  private static final long MICROS_PER_SECOND = 1_000_000L;
  private static final long MICROS_PER_DAY = 86_400L * MICROS_PER_SECOND;
  private static final int[] FRACTION_CORRECTION = {100_000, 10_000, 1_000, 100, 10};

  private final long micros;
  private final boolean aware;

  private PyIsoDateTime(long micros, boolean aware) {
    this.micros = micros;
    this.aware = aware;
  }

  /** Parses the same grammar as core's pinned interpreter, returning empty for Python's {@code ValueError}. */
  public static Optional<PyIsoDateTime> parse(String text) {
    if (text == null) {
      return Optional.empty();
    }
    try {
      return Optional.of(parseDateTime(text.codePoints().toArray()));
    } catch (DateTimeException | IllegalArgumentException | ArithmeticException e) {
      return Optional.empty();
    }
  }

  public boolean aware() {
    return aware;
  }

  /** The vector's {@code utc_epoch_micros} when aware, otherwise its {@code naive_micros}. */
  long micros() {
    return micros;
  }

  /** {@code int((end - start).total_seconds())}, computed without losing microseconds to a double. */
  public static long truncatedSecondsBetween(PyIsoDateTime start, PyIsoDateTime end) {
    if (start.aware != end.aware) {
      throw new IllegalArgumentException("cannot subtract naive and aware datetimes");
    }
    return (end.micros - start.micros) / MICROS_PER_SECOND;
  }

  private static PyIsoDateTime parseDateTime(int[] text) {
    if (text.length < 7) {
      throw new IllegalArgumentException("Invalid isoformat string");
    }
    int separator = findDateTimeSeparator(text);
    LocalDate date = parseDate(Arrays.copyOfRange(text, 0, separator));
    if (separator == text.length) {
      return new PyIsoDateTime(epochMicros(date.atStartOfDay(), 0), false);
    }
    TimeParts time = parseTime(Arrays.copyOfRange(text, separator + 1, text.length));
    LocalDateTime local = LocalDateTime.of(date, LocalTime.of(time.clock[0], time.clock[1], time.clock[2], time.clock[3] * 1_000));
    long offsetMicros = time.offsetMicros == null ? 0 : time.offsetMicros;
    return new PyIsoDateTime(epochMicros(local, offsetMicros), time.offsetMicros != null);
  }

  /** CPython's _find_isoformat_datetime_separator, using code points so an astral separator is one character. */
  private static int findDateTimeSeparator(int[] text) {
    if (text.length == 7) {
      return 7;
    }
    if (text[4] == '-') {
      return text[5] == 'W' ? findExtendedWeekSeparator(text) : 10;
    }
    return text[4] == 'W' ? findCompactWeekSeparator(text) : 8;
  }

  private static int findExtendedWeekSeparator(int[] text) {
    if (text.length < 8) {
      throw new IllegalArgumentException("Invalid ISO string");
    }
    if (text.length <= 8 || text[8] != '-') {
      return 8;
    }
    if (text.length == 9) {
      throw new IllegalArgumentException("Invalid ISO string");
    }
    if (text.length > 10 && isAsciiDigit(text[10])) {
      return 8;
    }
    return 10;
  }

  private static int findCompactWeekSeparator(int[] text) {
    int index = 7;
    while (index < text.length && isAsciiDigit(text[index])) {
      index++;
    }
    if (index < 9) {
      return index;
    }
    return index % 2 == 0 ? 7 : 8;
  }

  private static LocalDate parseDate(int[] text) {
    if (text.length != 7 && text.length != 8 && text.length != 10) {
      throw new IllegalArgumentException("Invalid date length");
    }
    int year = digits(text, 0, 4);
    if (year < 1) {
      throw new IllegalArgumentException("Year is out of range");
    }
    boolean dashed = text[4] == '-';
    int position = dashed ? 5 : 4;
    if (position < text.length && text[position] == 'W') {
      return parseWeekDate(text, year, dashed, position + 1);
    }
    int month = digits(text, position, 2);
    position += 2;
    requireDashConsistency(text, position, dashed);
    position += dashed ? 1 : 0;
    int day = digits(text, position, 2);
    return LocalDate.of(year, month, day);
  }

  private static LocalDate parseWeekDate(int[] text, int year, boolean dashed, int position) {
    int week = digits(text, position, 2);
    position += 2;
    int day = 1;
    if (text.length > position) {
      requireDashConsistency(text, position, dashed);
      position += dashed ? 1 : 0;
      day = digits(text, position, 1);
    }
    validateWeek(year, week, day);
    LocalDate januaryFirst = LocalDate.of(year, 1, 1);
    LocalDate firstMonday = januaryFirst.minusDays(januaryFirst.getDayOfWeek().getValue() - 1L);
    return firstMonday.plusDays((week - 1L) * 7 + day - 1L);
  }

  private static void requireDashConsistency(int[] text, int position, boolean dashed) {
    boolean found = position < text.length && text[position] == '-';
    if (found != dashed) {
      throw new IllegalArgumentException("Inconsistent use of dash separator");
    }
  }

  private static void validateWeek(int year, int week, int day) {
    if (week < 1 || week > 53 || (week == 53 && !has53Weeks(year))) {
      throw new IllegalArgumentException("Invalid week");
    }
    if (day < 1 || day > 7) {
      throw new IllegalArgumentException("Invalid weekday");
    }
  }

  private static boolean has53Weeks(int year) {
    LocalDate januaryFirst = LocalDate.of(year, 1, 1);
    int weekday = januaryFirst.getDayOfWeek().getValue();
    return weekday == 4 || (weekday == 3 && januaryFirst.isLeapYear());
  }

  private static TimeParts parseTime(int[] text) {
    if (text.length < 2) {
      throw new IllegalArgumentException("Isoformat time too short");
    }
    int timezonePosition = timezonePosition(text);
    int[] clockText = timezonePosition > 0 ? Arrays.copyOfRange(text, 0, timezonePosition - 1) : text;
    int[] clock = parseHoursMinutesSecondsFraction(clockText);
    Long offset = parseTimezone(text, timezonePosition);
    return new TimeParts(clock, offset);
  }

  private static int timezonePosition(int[] text) {
    int minus = indexOf(text, '-');
    int plus = indexOf(text, '+');
    int zulu = indexOf(text, 'Z');
    return minus >= 0 ? minus + 1 : plus >= 0 ? plus + 1 : zulu >= 0 ? zulu + 1 : 0;
  }

  private static Long parseTimezone(int[] text, int position) {
    if (position == 0) {
      return null;
    }
    if (position == text.length && text[position - 1] == 'Z') {
      return 0L;
    }
    int[] zone = Arrays.copyOfRange(text, position, text.length);
    if (zone.length == 0 || zone.length == 1 || zone.length == 3) {
      throw new IllegalArgumentException("Malformed time zone string");
    }
    int[] components = parseHoursMinutesSecondsFraction(zone);
    long magnitude = components[0] * 3_600_000_000L + components[1] * 60_000_000L + components[2] * 1_000_000L + components[3];
    if (magnitude >= MICROS_PER_DAY) {
      throw new IllegalArgumentException("Offset must be strictly between -24 hours and 24 hours");
    }
    return text[position - 1] == '-' ? -magnitude : magnitude;
  }

  /** CPython's _parse_hh_mm_ss_ff; digits beyond microsecond precision are truncated, not rounded. */
  private static int[] parseHoursMinutesSecondsFraction(int[] text) {
    int[] components = new int[4];
    int position = 0;
    boolean colon = false;
    for (int component = 0; component < 3; component++) {
      if (text.length - position < 2) {
        throw new IllegalArgumentException("Incomplete time component");
      }
      components[component] = digits(text, position, 2);
      position += 2;
      int next = position < text.length ? text[position] : -1;
      if (component == 0) {
        colon = next == ':';
      }
      if (next < 0 || component >= 2) {
        break;
      }
      if (colon && next != ':') {
        throw new IllegalArgumentException("Invalid time separator");
      }
      position += colon ? 1 : 0;
    }
    parseFractionIfPresent(text, position, components);
    return components;
  }

  private static void parseFractionIfPresent(int[] text, int position, int[] components) {
    if (position >= text.length) {
      return;
    }
    if (text[position] != '.' && text[position] != ',') {
      throw new IllegalArgumentException("Invalid microsecond component");
    }
    int start = position + 1;
    int remainder = text.length - start;
    int count = Math.min(6, remainder);
    components[3] = digits(text, start, count);
    if (count < 6) {
      components[3] *= FRACTION_CORRECTION[count - 1];
    }
    for (int index = start + count; index < text.length; index++) {
      if (!isAsciiDigit(text[index])) {
        throw new IllegalArgumentException("Non-digit values in unparsed fraction");
      }
    }
  }

  private static int digits(int[] text, int start, int count) {
    if (count <= 0 || start < 0 || start + count > text.length) {
      throw new IllegalArgumentException("Missing digits");
    }
    int value = 0;
    for (int index = start; index < start + count; index++) {
      if (!isAsciiDigit(text[index])) {
        throw new IllegalArgumentException("Non-ASCII or non-digit value");
      }
      value = value * 10 + text[index] - '0';
    }
    return value;
  }

  private static boolean isAsciiDigit(int codePoint) {
    return codePoint >= '0' && codePoint <= '9';
  }

  private static int indexOf(int[] text, int sought) {
    for (int index = 0; index < text.length; index++) {
      if (text[index] == sought) {
        return index;
      }
    }
    return -1;
  }

  private static long epochMicros(LocalDateTime local, long offsetMicros) {
    long localMicros = Math.addExact(Math.multiplyExact(local.toEpochSecond(ZoneOffset.UTC), MICROS_PER_SECOND), local.getNano() / 1_000L);
    return localMicros - offsetMicros;
  }

  private record TimeParts(int[] clock, Long offsetMicros) {}
}
