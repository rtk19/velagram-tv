package com.velagram.tv;

import java.util.*;
import java.util.regex.*;
import org.json.*;

/** Portable language/label intent, never a stream-specific numeric track index. */
public final class TrackMemory {
  public static String language(String language, String label) {
    String value = (language == null ? "" : language).toLowerCase(Locale.ROOT);
    if (!value.isBlank() && !value.equals("und")) {
      value = value.split("[-_]")[0];
      return switch (value) { case "eng" -> "en"; case "heb", "iw" -> "he"; case "jpn" -> "ja";
        case "spa" -> "es"; case "fra", "fre" -> "fr"; case "deu", "ger" -> "de"; case "rus" -> "ru"; default -> value; };
    }
    String text = label == null ? "" : label.toLowerCase(Locale.ROOT);
    String[][] aliases = {{"en", "english|eng|en"}, {"he", "hebrew|עברית|heb|he|iw"}, {"ja", "japanese|jpn|ja"},
        {"es", "spanish|spa|es"}, {"fr", "french|fra|fre|fr"}, {"de", "german|deu|ger|de"}, {"ru", "russian|rus|ru"}};
    for (String[] alias : aliases)
      if (Pattern.compile("(?<![\\p{L}])(?:" + alias[1] + ")(?![\\p{L}])").matcher(text).find()) return alias[0];
    return "";
  }
  public static String label(String label) {
    return (label == null ? "" : label).toLowerCase(Locale.ROOT).replaceAll("\\btrack\\s*\\d+\\b", "")
        .replaceAll("[^\\p{L}\\p{N}]+", " ").trim();
  }
  public static JSONObject choice(String language, String label, int roles) {
    return Models.json("language", language(language, label), "label", label(label), "roles", roles);
  }
  public static int score(JSONObject intent, String language, String label, int roles) {
    if (intent == null) return -1;
    String wantedLanguage = intent.optString("language"), wantedLabel = intent.optString("label");
    String candidateLanguage = language(language, label), candidateLabel = label(label);
    // A known conflicting language must never be restored just because labels contain "default".
    if (!wantedLanguage.isEmpty() && !candidateLanguage.isEmpty() && !wantedLanguage.equals(candidateLanguage)) return -1;
    int score = 0;
    if (!wantedLanguage.isEmpty() && wantedLanguage.equals(candidateLanguage)) score += 8;
    if (!wantedLabel.isEmpty() && wantedLabel.equals(candidateLabel)) score += 32;
    if (score == 0) return -1;
    if (intent.optInt("roles") == roles) score += 2;
    return score;
  }
  static String key(JSONObject media, boolean subtitles) { return "trackIntent." + (subtitles ? "text." : "audio.") + PlaybackPrefs.titleKey(media); }
  public static void save(Store store, JSONObject media, boolean subtitles, String language, String label, int roles) {
    store.put(key(media, subtitles), choice(language, label, roles).toString());
  }
  public static JSONObject read(Store store, JSONObject media, boolean subtitles) {
    try { return new JSONObject(store.get(key(media, subtitles), "")); } catch (Exception e) { return null; }
  }
  public static void forget(Store store, JSONObject media, boolean subtitles) { store.prefs.edit().remove(key(media, subtitles)).apply(); }
}
