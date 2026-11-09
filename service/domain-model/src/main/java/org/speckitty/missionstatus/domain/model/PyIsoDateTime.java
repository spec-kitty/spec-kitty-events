package org.speckitty.missionstatus.domain.model;

import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Optional;

/**
 * Python 3.11's {@code datetime.fromisoformat}, ported grammar-piece by grammar-piece from CPython's C implementation. The stored
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
    try {
      return Optional.of(parseOrThrow(text));
    } catch (DateTimeException | IllegalArgumentException | ArithmeticException e) {
      return Optional.empty();
    }
  }

  /** Exposes the rejection branch to the package's vector test without making exceptions part of the public API. */
  static PyIsoDateTime parseOrThrow(String text) {
    if (text == null) {
      throw invalid("null");
    }
    return parseDateTime(text.codePoints().toArray());
  }

  public boolean aware() {
    return aware;
  }

  /** The vector's {@code utc_epoch_micros} when aware, otherwise its {@code naive_micros}. */
  long micros() {
    return micros;
  }

  /**
   * {@code int((end - start).total_seconds())} exactly as CPython computes it: {@code total_seconds()} rounds the microsecond
   * count to the nearest double (correctly rounded), and {@code int()} then truncates toward zero. Truncating the exact
   * microseconds instead differs by one second once a span passes about 2^34 seconds with a fraction such as .999999.
   */
  public static long truncatedSecondsBetween(PyIsoDateTime start, PyIsoDateTime end) {
    if (start.aware != end.aware) {
      throw new IllegalArgumentException("cannot subtract naive and aware datetimes");
    }
    return (long) new BigDecimal(end.micros - start.micros).movePointLeft(6).doubleValue();
  }

  private static PyIsoDateTime parseDateTime(int[] text) {
    if (text.length < 7) {
      throw invalid("date_length");
    }
    int separator = findDateTimeSeparator(text);
    LocalDate date = parseDate(Arrays.copyOfRange(text, 0, separator));
    if (separator == text.length) {
      return new PyIsoDateTime(epochMicros(date.atStartOfDay(), 0), false);
    }
    TimeParts time = parseTime(toUtf8(Arrays.copyOfRange(text, separator + 1, text.length)));
    validateClock(time.clock);
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
      throw invalid("separator");
    }
    if (text.length <= 8 || text[8] != '-') {
      return 8;
    }
    if (text.length == 9) {
      throw invalid("separator");
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
      throw invalid("date_length");
    }
    int year = yearDigits(text);
    if (year < 1) {
      throw invalid("year");
    }
    boolean dashed = text[4] == '-';
    int position = dashed ? 5 : 4;
    if (position < text.length && text[position] == 'W') {
      return parseWeekDate(text, year, dashed, position + 1);
    }
    int month = digits(text, position, 2, "date_digits", "date_length");
    position += 2;
    requireDashConsistency(text, position, dashed);
    position += dashed ? 1 : 0;
    int day = digits(text, position, 2, "date_digits", "date_length");
    return calendarDate(year, month, day);
  }

  private static int yearDigits(int[] text) {
    for (int index = 0; index < 4; index++) {
      if (at(text, index) > 127) {
        throw invalid("non_ascii_digits");
      }
    }
    return digits(text, 0, 4, "date_digits", "date_length");
  }

  private static LocalDate calendarDate(int year, int month, int day) {
    if (month < 1 || month > 12) {
      throw invalid("month");
    }
    try {
      return LocalDate.of(year, month, day);
    } catch (DateTimeException e) {
      throw invalid("day");
    }
  }

  private static LocalDate parseWeekDate(int[] text, int year, boolean dashed, int position) {
    int week = digits(text, position, 2, "week", "week");
    position += 2;
    int day = 1;
    if (text.length > position) {
      requireDashConsistency(text, position, dashed);
      position += dashed ? 1 : 0;
      day = digits(text, position, 1, "weekday", "weekday");
    }
    validateWeek(year, week, day);
    LocalDate januaryFirst = LocalDate.of(year, 1, 1);
    int januaryWeekday = januaryFirst.getDayOfWeek().getValue();
    LocalDate firstMonday = januaryFirst.minusDays(januaryWeekday - 1L).plusDays(januaryWeekday > 4 ? 7 : 0);
    LocalDate result = firstMonday.plusDays((week - 1L) * 7 + day - 1L);
    if (result.getYear() < 1 || result.getYear() > 9999) {
      throw invalid("year");
    }
    return result;
  }

  private static void requireDashConsistency(int[] text, int position, boolean dashed) {
    boolean found = position < text.length && text[position] == '-';
    if (found != dashed) {
      throw invalid("date_separator");
    }
  }

  private static void validateWeek(int year, int week, int day) {
    if (week < 1 || week > 53 || (week == 53 && !has53Weeks(year))) {
      throw invalid("week");
    }
    if (day < 1 || day > 7) {
      throw invalid("weekday");
    }
  }

  private static boolean has53Weeks(int year) {
    LocalDate januaryFirst = LocalDate.of(year, 1, 1);
    int weekday = januaryFirst.getDayOfWeek().getValue();
    return weekday == 4 || (weekday == 3 && januaryFirst.isLeapYear());
  }

  private static TimeParts parseTime(int[] text) {
    if (text.length < 2) {
      throw invalid("time_short");
    }
    int timezonePosition = timezonePosition(text);
    int clockEnd = timezonePosition < 0 ? text.length : timezonePosition;
    ParsedClock parsed = parseHoursMinutesSecondsFraction(text, 0, clockEnd, false);
    if (timezonePosition < 0) {
      if (parsed.trailing) {
        throw invalid(parsed.fraction ? "fraction_digits" : "trailing_text");
      }
      return new TimeParts(parsed.components, null);
    }
    if (text[timezonePosition] == 'Z') {
      if (at(text, timezonePosition + 1) != 0) {
        throw invalid("offset_z_position");
      }
      return new TimeParts(parsed.components, 0L);
    }
    long offset = parseTimezone(text, timezonePosition);
    return new TimeParts(parsed.components, offset);
  }

  private static int timezonePosition(int[] text) {
    for (int index = 0; index < text.length; index++) {
      if (text[index] == '-' || text[index] == '+' || text[index] == 'Z') {
        return index;
      }
    }
    return -1;
  }

  private static long parseTimezone(int[] text, int position) {
    int start = position + 1;
    int length = text.length - start;
    if (length == 0 || length == 1 || length == 3) {
      throw invalid("offset");
    }
    ParsedClock zone = parseHoursMinutesSecondsFraction(text, start, text.length, true);
    if (zone.trailing) {
      throw invalid("offset_trailing_text");
    }
    int[] parts = zone.components;
    long seconds = parts[0] * 3_600L + parts[1] * 60L + parts[2];
    if (seconds == 0) {
      return 0L;
    }
    long sign = text[position] == '-' ? -1 : 1;
    long magnitude = seconds * MICROS_PER_SECOND + parts[3];
    if (magnitude >= MICROS_PER_DAY) {
      throw invalid("offset_range");
    }
    return sign * magnitude;
  }

  /** CPython's parse_hh_mm_ss_ff; digits beyond microsecond precision are truncated, not rounded. */
  private static ParsedClock parseHoursMinutesSecondsFraction(int[] text, int start, int end, boolean offset) {
    int[] components = new int[4];
    int position = start;
    boolean colon = false;
    for (int component = 0; component < 3; component++) {
      components[component] = clockDigits(text, position, offset);
      position += 2;
      int next = at(text, position++);
      if (component == 0) {
        colon = next == ':';
      }
      if (position >= end) {
        return new ParsedClock(components, next != 0, false);
      }
      if (colon && next == ':') {
        continue;
      }
      if (next == '.' || next == ',') {
        break;
      }
      if (!colon) {
        position--;
      } else {
        throw invalid(offset ? "offset_trailing_text" : "time_separator");
      }
    }
    boolean trailing = parseFraction(text, position, end, components, offset);
    return new ParsedClock(components, trailing, true);
  }

  private static int clockDigits(int[] text, int position, boolean offset) {
    String missing = offset ? "offset" : "time_incomplete";
    String nondigit = offset ? "offset_digits" : "time_digits";
    return digits(text, position, 2, nondigit, missing);
  }

  private static boolean parseFraction(int[] text, int position, int end, int[] components, boolean offset) {
    String invalidFraction = offset ? "offset_fraction" : "fraction";
    int count = Math.min(6, end - position);
    if (count <= 0) {
      throw invalid(invalidFraction);
    }
    components[3] = digits(text, position, count, invalidFraction, invalidFraction);
    if (count < 6) {
      components[3] *= FRACTION_CORRECTION[count - 1];
    }
    position += count;
    while (isAsciiDigit(at(text, position))) {
      position++;
    }
    return at(text, position) != 0;
  }

  private static void validateClock(int[] clock) {
    if (clock[0] > 23) {
      throw invalid("hour");
    }
    if (clock[1] > 59) {
      throw invalid("minute");
    }
    if (clock[2] > 59) {
      throw invalid("second");
    }
  }

  private static int digits(int[] text, int start, int count, String nondigitBranch, String missingBranch) {
    int value = 0;
    for (int index = start; index < start + count; index++) {
      int digit = at(text, index);
      if (digit == 0) {
        throw invalid(missingBranch);
      }
      if (!isAsciiDigit(digit)) {
        throw invalid(nondigitBranch);
      }
      value = value * 10 + digit - '0';
    }
    return value;
  }

  private static int at(int[] text, int index) {
    return index >= 0 && index < text.length ? text[index] : 0;
  }

  private static boolean isAsciiDigit(int codePoint) {
    return codePoint >= '0' && codePoint <= '9';
  }

  private static IllegalArgumentException invalid(String branch) {
    return new IllegalArgumentException(branch);
  }

  /** CPython's time parser advances and bounds pointers in the UTF-8 encoding, not in Unicode code points. */
  private static int[] toUtf8(int[] text) {
    int[] bytes = new int[text.length * 4];
    int position = 0;
    for (int codePoint : text) {
      if (codePoint < 0x80) {
        bytes[position++] = codePoint;
      } else {
        position = appendMultibyteUtf8(bytes, position, codePoint);
      }
    }
    return Arrays.copyOf(bytes, position);
  }

  private static int appendMultibyteUtf8(int[] bytes, int position, int codePoint) {
    int length = codePoint < 0x800 ? 2 : codePoint < 0x10_000 ? 3 : 4;
    int[] prefixes = {0, 0, 0xC0, 0xE0, 0xF0};
    for (int index = length - 1; index > 0; index--) {
      bytes[position + index] = 0x80 | (codePoint & 0x3F);
      codePoint >>>= 6;
    }
    bytes[position] = prefixes[length] | codePoint;
    return position + length;
  }

  private static long epochMicros(LocalDateTime local, long offsetMicros) {
    long localMicros = Math.addExact(Math.multiplyExact(local.toEpochSecond(ZoneOffset.UTC), MICROS_PER_SECOND), local.getNano() / 1_000L);
    return localMicros - offsetMicros;
  }

  private record ParsedClock(int[] components, boolean trailing, boolean fraction) {}

  private record TimeParts(int[] clock, Long offsetMicros) {}
}
