package com.velagram.tv;

import java.util.*;
import org.json.*;

/** Strict metadata-first identity, with filename matching only as a fallback. */
public final class CatalogMatch {
  public static boolean matches(JSONObject candidate, JSONObject title, int season, int episode) {
    JSONObject ids = candidate.optJSONObject("ProviderIds");
    String imdb = title.optString("imdb_id", "");
    boolean exact =
        ids != null
            && ((!imdb.isEmpty() && imdb.equals(ids.optString("Imdb")))
                || title.optString("id").equals(ids.optString("Tmdb", "-1")));
    if (season >= 0 && episode > 0) {
      if (candidate.has("ParentIndexNumber") && candidate.has("IndexNumber"))
        return (exact
                || sameName(candidate.optString("SeriesName", Models.title(candidate)), title))
            && candidate.optInt("ParentIndexNumber") == season
            && candidate.optInt("IndexNumber") == episode;
    }
    if (exact) return true;
    if (candidate.has("ProductionYear")
        && !Models.year(title).isEmpty()
        && candidate.optInt("ProductionYear") != Integer.parseInt(Models.year(title))) return false;
    return ReleaseGrouping.matches(candidate, title, season, episode);
  }

  public static boolean sameName(String name, JSONObject title) {
    String n = WebSources.normalize(name);
    JSONArray aliases = title.optJSONArray("aliases");
    if (aliases != null)
      for (int i = 0; i < aliases.length(); i++)
        if (n.equals(WebSources.normalize(aliases.optString(i)))) return true;
    return n.equals(WebSources.normalize(Models.title(title)))
        || n.equals(
            WebSources.normalize(
                title.optString("original_title", title.optString("original_name", "!"))));
  }

  public static String quality(String name) {
    java.util.regex.Matcher m =
        java.util.regex.Pattern.compile("(?i)(2160p|1080p|720p|480p|4k)").matcher(name);
    return m.find() ? m.group(1).toUpperCase(Locale.ROOT) : "";
  }
}
