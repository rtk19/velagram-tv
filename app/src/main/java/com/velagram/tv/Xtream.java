package com.velagram.tv;

import java.util.*;
import org.json.*;

public final class Xtream {
  final JSONObject a;
  final String base, query;

  public Xtream(JSONObject account) {
    a = account;
    base = a.optString("url").replaceAll("/+$", "");
    query =
        "username="
            + Api.enc(a.optString("username"))
            + "&password="
            + Api.enc(a.optString("password"));
  }

  String url(String action) {
    return base + "/player_api.php?" + query + "&action=" + action;
  }

  public void verify() throws Exception {
    JSONObject r = Api.get(base + "/player_api.php?" + query);
    JSONObject u = r.optJSONObject("user_info");
    if (u == null || u.optInt("auth") != 1)
      throw new Exception("Xtream account was not authorized");
  }

  List<JSONObject> array(String action) throws Exception {
    return Models.list(new JSONArray(Api.request(url(action), Collections.emptyMap(), null)));
  }

  public List<JSONObject> categories(String kind) throws Exception {
    return array("get_" + kind + "_categories");
  }

  public List<JSONObject> items(String kind, String category) throws Exception {
    return array(
        "get_"
            + (kind.equals("series") ? "series" : "vod_streams")
            + "&category_id="
            + Api.enc(category));
  }

  String stream(String type, String id, String extension) {
    return base
        + "/"
        + type
        + "/"
        + Api.enc(a.optString("username"))
        + "/"
        + Api.enc(a.optString("password"))
        + "/"
        + id
        + "."
        + extension;
  }

  public List<JSONObject> live() throws Exception {
    List<JSONObject> r = new ArrayList<>();
    Map<String, String> groups = new HashMap<>();
    for (JSONObject c : categories("live"))
      groups.put(c.optString("category_id"), c.optString("category_name"));
    for (JSONObject o : array("get_live_streams"))
      r.add(
          Models.json(
              "id",
              "xtream:" + a.optString("id") + ":" + o.optString("stream_id"),
              "name",
              o.optString("name"),
              "group",
              groups.getOrDefault(o.optString("category_id"), "Xtream"),
              "logo",
              o.optString("stream_icon"),
              "tvgID",
              o.optString("epg_channel_id"),
              "url",
              stream("live", o.optString("stream_id"), "m3u8")));
    return r;
  }

  public JSONObject movie(JSONObject o) {
    return Models.json(
        "id",
        "xtream-vod:" + o.optString("stream_id"),
        "name",
        o.optString("name"),
        "logo",
        o.optString("stream_icon"),
        "vod",
        true,
        "url",
        stream("movie", o.optString("stream_id"), o.optString("container_extension", "mp4")));
  }

  public List<JSONObject> episodes(int id) throws Exception {
    JSONObject root = Api.get(url("get_series_info") + "&series_id=" + id),
        seasons = root.getJSONObject("episodes");
    List<JSONObject> r = new ArrayList<>();
    List<String> keys = new ArrayList<>();
    seasons.keys().forEachRemaining(keys::add);
    keys.sort(Comparator.comparingInt(Integer::parseInt));
    for (String season : keys)
      for (JSONObject e : Models.list(seasons.optJSONArray(season)))
        r.add(
            Models.json(
                "id",
                "xtream-episode:" + e.optString("id"),
                "name",
                "S" + season + " E" + e.optInt("episode_num") + " · " + e.optString("title"),
                "season",
                Integer.parseInt(season),
                "episode",
                e.optInt("episode_num"),
                "url",
                stream("series", e.optString("id"), e.optString("container_extension", "mp4"))));
    return r;
  }

  public List<JSONObject> search(JSONObject title, int season, int episode) throws Exception {
    List<JSONObject> r = new ArrayList<>();
    if (season <= 0) {
      for (JSONObject o : items("vod", ""))
        if (CatalogMatch.matches(o, title, 0, 0)) r.add(movie(o));
    } else
      for (JSONObject o : items("series", ""))
        if (CatalogMatch.sameName(o.optString("name"), title))
          for (JSONObject e : episodes(o.optInt("series_id")))
            if (e.optInt("season") == season && e.optInt("episode") == episode) r.add(e);
    return r;
  }
}
