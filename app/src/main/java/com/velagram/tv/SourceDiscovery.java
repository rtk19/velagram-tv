package com.velagram.tv;

import android.content.Context;
import android.os.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import org.json.*;

/** Streams incremental results from every enabled/connected library into one catalog flow. */
public final class SourceDiscovery {
  static final ExecutorService WORKERS = Executors.newFixedThreadPool(4);
  final Context context;
  final Store store;
  final Api api;
  final Handler main = new Handler(Looper.getMainLooper());
  volatile boolean cancelled;

  public interface Listener {
    void result(String provider, List<JSONObject> sources, String status);
  }

  interface Query {
    List<JSONObject> run() throws Exception;
  }

  public SourceDiscovery(Context c, Store s) {
    context = c.getApplicationContext();
    store = s;
    api = new Api(s);
  }

  public void cancel() {
    cancelled = true;
  }

  void emit(String provider, List<JSONObject> items, String status, Listener listener) {
    if (cancelled) return;
    for (JSONObject item : items)
      try {
        item.put("provider", provider);
        item.put("quality", CatalogMatch.quality(Models.title(item)));
      } catch (Exception ignored) {
      }
    main.post(
        () -> {
          if (!cancelled) listener.result(provider, items, status);
        });
  }

  void query(String name, Query query, Listener listener) {
    emit(name, new ArrayList<>(), "Searching", listener);
    WORKERS.execute(
        () -> {
          try {
            List<JSONObject> items = query.run();
            emit(
                name,
                items,
                items.isEmpty() ? "No matching releases" : items.size() + " releases",
                listener);
          } catch (Exception e) {
            emit(name, new ArrayList<>(), "Unavailable · retry", listener);
          }
        });
  }

  public void search(JSONObject title, int season, int episode, Listener listener) {
    query(
        "IPTV playlists",
        () -> {
          List<JSONObject> r = new ArrayList<>();
          for (JSONObject c : store.items("channels"))
            if (c.optBoolean("vod") && CatalogMatch.matches(c, title, season, episode))
              r.add(new JSONObject(c.toString()));
          return r;
        },
        listener);
    for (JSONObject s : store.items("servers")) {
      String name = s.optString("name", "Media server");
      query(
          name,
          () -> {
            List<JSONObject> r = new ArrayList<>();
            for (JSONObject item : api.searchServer(s, title, season, episode))
              r.add(
                  Models.json(
                      "id",
                      "server:" + s.optString("id") + ":" + item.optString("Id"),
                      "name",
                      Models.title(item),
                      "server",
                      s,
                      "serverItem",
                      item,
                      "description",
                      item.optString("Overview")));
            return r;
          },
          listener);
    }
    for (JSONObject a : store.items("xtream"))
      query(
          "IPTV · " + a.optString("name"),
          () -> new Xtream(a).search(title, season, episode),
          listener);
    if (store.get("source.addon", "true").equals("true") && !store.get("addon", "").isBlank())
      query("HTTP add-on", () -> api.sources(title, season, episode), listener);
    if (store.get("source.community", "true").equals("true"))
      query(
          "StreamingCommunity",
          () -> List.of(WebSources.community(title, season, episode)),
          listener);
    boolean animation = isAnimation(title);
    if (animation && store.get("source.animeil", "true").equals("true"))
      query(
          "AnimeIL",
          () -> {
            String imdb = title.optString("imdb_id");
            if (imdb.isEmpty())
              imdb =
                  api.tmdb(
                          title.optString("kind", "movie")
                              + "/"
                              + title.optInt("id")
                              + "/external_ids")
                      .optString("imdb_id");
            return WebSources.anime(imdb, season, episode);
          },
          listener);
    if (animation)
      for (String site : new String[] {"HiAnime", "Anikoto"})
        if (store.get("source." + site.toLowerCase(java.util.Locale.ROOT), "true").equals("true"))
          query(site, () -> AnimeSources.search(api, site, title, season, episode), listener);
    if (store.get("source.telegram", "true").equals("true")) {
      try {
        Telegram tg = Telegram.get(context);
        if (tg.ready()) {
          emit("Telegram", new ArrayList<>(), "Searching your chats", listener);
          Set<String> seen = new HashSet<>();
          tg.catalog(
              title,
              season,
              episode,
              () -> !cancelled,
              batch -> {
                List<JSONObject> fresh = new ArrayList<>();
                for (JSONObject item : batch) if (seen.add(item.optString("id"))) fresh.add(item);
                if (!fresh.isEmpty())
                  emit("Telegram", fresh, "Searching · " + seen.size() + " releases", listener);
              },
              count ->
                  emit(
                      "Telegram",
                      new ArrayList<>(),
                      seen.isEmpty() ? "No matching releases" : seen.size() + " releases",
                      listener));
        } else emit("Telegram", new ArrayList<>(), "Connect in Settings", listener);
      } catch (Throwable e) {
        emit("Telegram", new ArrayList<>(), "Connection unavailable", listener);
      }
    }
  }

  public static boolean isAnimation(JSONObject title) {
    for (JSONObject genre : Models.list(title.optJSONArray("genres")))
      if (genre.optInt("id") == 16) return true;
    JSONArray ids = title.optJSONArray("genre_ids");
    if (ids != null) for (int i = 0; i < ids.length(); i++) if (ids.optInt(i) == 16) return true;
    return false;
  }

  public JSONObject resolve(JSONObject source) throws Exception {
    if (source.has("animeSite")) return AnimeSources.resolve(source);
    return source.has("server")
        ? api.serverStream(source.getJSONObject("server"), source.getJSONObject("serverItem"))
        : api.resolve(source);
  }
}
