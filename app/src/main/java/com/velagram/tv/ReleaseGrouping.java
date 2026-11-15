package com.velagram.tv;

import java.util.*;
import java.util.regex.*;
import org.json.*;

public final class ReleaseGrouping {
  static final Pattern EPISODE =
      Pattern.compile(
          "(?i)(?:S(\\d{1,2})[ ._-]*E(\\d{1,3})|(?:עונה|season)[ ._-]*(\\d{1,2}).*?(?:פרק|episode)["
              + " ._-]*(\\d{1,3})|(\\d{1,2})x(\\d{1,3}))");

  public static boolean series(String s) {
    return EPISODE.matcher(s).find();
  }

  public static String normalized(String s) {
    return WebSources.normalize(
        s.replaceAll(
            "(?i)\\b(1080p|720p|2160p|4k|web[- .]?dl|bluray|x26[45]|hevc|aac|dts)\\b", ""));
  }

  public static void organize(List<JSONObject> items, int mode) {
    Set<String> seen = new HashSet<>();
    items.removeIf(o -> !seen.add(o.optString("file", o.optString("id"))));
    if (mode == 4) items.removeIf(o -> series(Models.title(o)));
    if (mode == 5) items.removeIf(o -> !series(Models.title(o)));
    if (mode == 1) items.sort(Comparator.comparingLong(o -> o.optLong("message")));
    if (mode == 2) items.sort(Comparator.comparing(Models::title));
    if (mode == 3) items.sort((a, b) -> Long.compare(b.optLong("size"), a.optLong("size")));
  }

  public static String words(String value) {
    return java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFKD)
        .replaceAll("\\p{M}", "")
        .toLowerCase(Locale.ROOT)
        .replaceAll("[^\\p{L}\\p{N}]+", " ")
        .trim()
        .replaceAll(" +", " ");
  }

  public static boolean containsTitle(String release, String title) {
    String haystack = " " + words(release) + " ", needle = words(title);
    return !needle.isEmpty() && haystack.contains(" " + needle + " ");
  }

  public static boolean matches(JSONObject file, JSONObject title, int season, int episode) {
    String name = Models.title(file) + " " + file.optString("description");
    String original = title.optString("original_title", title.optString("original_name", ""));
    boolean match =
        containsTitle(name, Models.title(title))
            || !original.isEmpty() && containsTitle(name, original);
    if (!match) {
      JSONArray aliases = title.optJSONArray("aliases");
      if (aliases != null)
        for (int i = 0; i < aliases.length(); i++) {
          String alias = aliases.optString(i);
          if (!alias.isBlank() && containsTitle(name, alias)) {
            match = true;
            break;
          }
        }
    }
    if (!match) return false;
    if (season >= 0 && episode > 0) {
      Matcher m = EPISODE.matcher(name);
      if (!m.find()) return false;
      int s =
          Integer.parseInt(
              m.group(1) != null ? m.group(1) : m.group(3) != null ? m.group(3) : m.group(5));
      int e =
          Integer.parseInt(
              m.group(2) != null ? m.group(2) : m.group(4) != null ? m.group(4) : m.group(6));
      return s == season && e == episode;
    }
    String year = Models.year(title);
    Matcher y = Pattern.compile("\\b(19\\d{2}|20\\d{2})\\b").matcher(name);
    return year.isEmpty() || !y.find() || year.equals(y.group(1));
  }
}
