package com.velagram.tv;

import org.json.*;

public final class PlaybackPrefs {
  public static String titleKey(JSONObject media) {
    JSONObject catalog = media.optJSONObject("catalog");
    return catalog == null ? media.optString("id", media.optString("url")) : Models.key(catalog);
  }

  public static float speed(Store store, JSONObject media) {
    try {
      return Float.parseFloat(store.get("speed." + titleKey(media), store.get("speed", "1.0")));
    } catch (Exception e) {
      return 1f;
    }
  }

  public static boolean subtitles(Store store, JSONObject media) {
    return store.get("subtitleVisible." + titleKey(media), store.get("subtitlesEnabled", "true")).equals("true");
  }

  public static void subtitles(Store store, JSONObject media, boolean visible) {
    store.put("subtitleVisible." + titleKey(media), Boolean.toString(visible));
  }

  public static String preferredProvider(Store store, JSONObject media) {
    return store.get("source." + titleKey(media), "");
  }

  public static String context(JSONObject media) {
    int season = media.optInt("season"), episode = media.optInt("episode");
    if (Store.episode(media)) return "Season " + season + "  ·  Episode " + episode;
    JSONObject title = media.optJSONObject("catalog");
    if (title != null) {
      java.util.List<String> metadata = new java.util.ArrayList<>();
      if (!Models.year(title).isBlank()) metadata.add(Models.year(title));
      if (title.optInt("runtime") > 0) metadata.add(title.optInt("runtime") + " min");
      if (!metadata.isEmpty()) return String.join("  ·  ", metadata);
    }
    return media.optString("provider", media.optString("group", "Velagram player"));
  }

  public static boolean useVlc(Store store, JSONObject media) {
    String engine = store.get("engine", "Automatic");
    if (engine.equals("VLC")) return true;
    if (engine.equals("Media3")) return false;
    String candidate =
        media.optString("url") + " " + media.optString("filename", media.optString("name"));
    return candidate.toLowerCase(java.util.Locale.ROOT).matches(".*\\.avi(?:[? ].*)?$");
  }
}
