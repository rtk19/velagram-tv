package com.velagram.tv;

import java.net.*;
import java.util.*;
import java.util.regex.*;
import org.json.*;

/** HTTP providers ported from Velagram iOS. Provider availability is external. */
public final class WebSources {
  static String capture(String pattern, String text) throws Exception {
    Matcher m = Pattern.compile(pattern, Pattern.CASE_INSENSITIVE).matcher(text);
    if (!m.find()) throw new Exception("Provider response did not contain a playable source");
    return m.group(1);
  }

  static String html(String url, String referer) throws Exception {
    return Api.request(
        url,
        Map.of(
            "Referer",
            referer,
            "User-Agent",
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 Chrome/131.0.0.0"
                + " Safari/537.36",
            "Accept-Language",
            "en-US,en;q=0.9",
            "Cookie",
            "language=en"),
        null);
  }

  public static List<JSONObject> anime(String imdb, int season, int episode) throws Exception {
    List<JSONObject> r = new ArrayList<>();
    if (!imdb.matches("tt\\d{7,9}")) return r;
    String id = imdb + (season > 0 ? ":" + season + ":" + episode : "");
    JSONObject response =
        Api.get(
            "https://addon.animeil.qzz.io/stream/"
                + (season > 0 ? "series" : "movie")
                + "/"
                + id
                + ".json");
    for (JSONObject s : Models.list(response.optJSONArray("streams")))
      if (s.optString("url").startsWith("http")) {
        s.put("title", "AnimeIL · " + s.optString("name", s.optString("title", "Stream")));
        JSONObject hints = s.optJSONObject("behaviorHints");
        if (hints != null && hints.optJSONObject("proxyHeaders") != null)
          s.put("headers", hints.getJSONObject("proxyHeaders").optJSONObject("request"));
        r.add(s);
      }
    return r;
  }

  public static JSONObject community(JSONObject title, int season, int episode) throws Exception {
    return community(Models.title(title), Models.year(title), season, episode, title.optInt("id"));
  }

  public static JSONObject community(String name, String year, int season, int episode)
      throws Exception {
    return community(name, year, season, episode, 0);
  }

  static boolean communityMatch(JSONObject candidate, String name, String year, int season) {
    String type = candidate.optString("type");
    if (!type.isBlank() && (season > 0 ? type.equals("movie") : !type.equals("movie")))
      return false;
    String date =
        candidate.optString(
            "release_date", candidate.optString("last_air_date", candidate.optString("year")));
    return normalize(candidate.optString("name", candidate.optString("title")))
            .equals(normalize(name))
        && (season > 0 || year.isEmpty() || date.startsWith(year));
  }

  private static JSONObject community(String name, String year, int season, int episode, int tmdbId)
      throws Exception {
    String base = "https://streamingunity.win",
        query = base + "/en/search?q=" + Api.enc(name) + "&page=1&lang=en";
    JSONObject search =
        new JSONObject(
            Api.request(
                query,
                Map.of(
                    "Accept",
                    "application/json",
                    "X-Requested-With",
                    "XMLHttpRequest",
                    "Referer",
                    base + "/en/",
                    "User-Agent",
                    AnimeSources.UA,
                    "Cookie",
                    "language=en"),
                null));
    List<JSONObject> titles = Models.list(search.optJSONArray("data"));
    JSONObject match = null;
    for (JSONObject t : titles) {
      if (communityMatch(t, name, year, season)) {
        if (tmdbId > 0) {
          JSONObject providerTitle =
              page(base + "/en/titles/" + t.optString("id") + "-" + t.optString("slug"), base)
                  .getJSONObject("props")
                  .getJSONObject("title");
          if (providerTitle.has("tmdb_id") && providerTitle.optInt("tmdb_id") != tmdbId) continue;
        }
        match = t;
        break;
      }
    }
    if (match == null) throw new Exception("No exact title match from StreamingCommunity");
    String id = match.optString("id"), episodeId = "";
    if (season > 0) {
      String slug = id + "-" + match.optString("slug");
      JSONObject detail = page(base + "/en/titles/" + slug, base);
      JSONObject title = detail.getJSONObject("props").getJSONObject("title");
      for (JSONObject s : Models.list(title.optJSONArray("seasons")))
        if (s.optInt("number") == season) {
          String path = id + "-" + match.optString("slug") + "/season-" + season;
          String stored = s.optString("slug", "");
          if (!stored.isEmpty()) path = stored;
          JSONObject p = page(base + "/en/titles/" + path, base);
          JSONObject loaded = p.getJSONObject("props").optJSONObject("loadedSeason");
          if (loaded != null)
            for (JSONObject e : Models.list(loaded.optJSONArray("episodes")))
              if (e.optInt("number") == episode) episodeId = e.optString("id");
        }
      if (episodeId.isEmpty()) throw new Exception("Episode not found from StreamingCommunity");
    }
    String
        endpoint =
            base
                + "/en/iframe/"
                + id
                + "?language=en"
                + (episodeId.isEmpty() ? "" : "&episode_id=" + episodeId + "&next_episode=1"),
        frame = html(endpoint, base);
    String iframe = capture("<iframe[^>]+src=[\"']([^\"']+)", frame).replace("&amp;", "&");
    iframe = new URL(new URL(endpoint), iframe).toString();
    String script = html(iframe, endpoint),
        video = capture("window\\.video\\s*=\\s*\\{[\\s\\S]*?\\bid\\s*:\\s*[\"']?(\\d+)", script),
        token = capture("[\"']?token[\"']?\\s*:\\s*[\"']([^\"']+)", script),
        expires = capture("[\"']?expires[\"']?\\s*:\\s*[\"']([^\"']+)", script);
    URI uri = new URI(iframe);
    String host = uri.getScheme() + "://" + uri.getHost(),
        u =
            host
                + "/playlist/"
                + video
                + "?token="
                + Api.enc(token)
                + "&expires="
                + Api.enc(expires)
                + "&language=en"
                + (Pattern.compile("\\bb\\s*=\\s*1").matcher(script).find() ? "&b=1" : "")
                + (iframe.contains("canPlayFHD") ? "&h=1" : "");
    return Models.json(
        "title",
        "StreamingCommunity · English",
        "url",
        u,
        "headers",
        Models.json("Referer", host + "/", "Cookie", "language=en", "User-Agent", "Mozilla/5.0"));
  }

  static JSONObject page(String url, String base) throws Exception {
    String data = html(url, base);
    String json = capture("data-page=[\"']([\\s\\S]*?)[\"']", data);
    json =
        json.replace("&quot;", "\"")
            .replace("&#039;", "'")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">");
    return new JSONObject(json);
  }

  public static String normalize(String value) {
    return java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFKD)
        .replaceAll("\\p{M}", "")
        .toLowerCase(Locale.ROOT)
        .replaceAll("[^\\p{L}\\p{N}]", "");
  }
}
