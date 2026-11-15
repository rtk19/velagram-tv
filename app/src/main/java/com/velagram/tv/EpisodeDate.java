package com.velagram.tv;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.Locale;

/** Date-only parsing avoids timezone shifts and rejects invalid TMDB dates. */
public final class EpisodeDate {
  public static String format(String value, Locale locale) {
    if (value == null || !value.matches("\\d{4}-\\d{2}-\\d{2}")) return "";
    try {
      return LocalDate.parse(value).format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale));
    } catch (java.time.DateTimeException invalid) { return ""; }
  }
}
