package com.velagram.tv;

import android.content.*;
import java.util.*;
import org.json.*;

public final class Store {
  final Context context;
  final android.content.SharedPreferences prefs;

  public Store(Context c) {
    context = c.getApplicationContext();
    prefs = context.getSharedPreferences("velagram", 0);
  }

  public String get(String k, String fallback) {
    String v = prefs.getString(k, fallback);
    if (k.startsWith("secret.") || k.startsWith("serverToken.")) {
      try {
        return SecretVault.open(v);
      } catch (Exception e) {
        return fallback;
      }
    }
    return v;
  }

  public void put(String k, String v) {
    if (k.startsWith("secret.") || k.startsWith("serverToken.")) {
      try {
        v = SecretVault.seal(v);
      } catch (Exception e) {
        throw new IllegalStateException("Secure storage unavailable", e);
      }
    }
    prefs.edit().putString(k, v).apply();
  }

  public String profile() {
    return get("profile", "Me");
  }

  private String k(String key) {
    return "profile." + profile() + "." + key;
  }

  public JSONArray array(String key) {
    try {
      return new JSONArray(get(k(key), "[]"));
    } catch (Exception e) {
      return new JSONArray();
    }
  }

  public void array(String key, JSONArray a) {
    put(k(key), a.toString());
  }

  public List<JSONObject> items(String key) {
    return Models.list(array(key));
  }

  private String identity(JSONObject o) {
    return o.optString(
        "progressKey",
        o.has("id")
            ? o.optString("kind") + ":" + o.optString("id")
            : o.optString("url", Models.key(o)));
  }

  public synchronized void upsert(String collection, JSONObject item) {
    List<JSONObject> a = items(collection);
    a.removeIf(o -> identity(o).equals(identity(item)));
    a.add(0, item);
    if (collection.equals("history") || collection.equals("recent"))
      while (a.size() > 200) a.remove(a.size() - 1);
    array(collection, new JSONArray(a));
  }

  public synchronized void remove(String collection, JSONObject item) {
    List<JSONObject> a = items(collection);
    a.removeIf(o -> identity(o).equals(identity(item)));
    array(collection, new JSONArray(a));
  }

  public boolean contains(String collection, JSONObject item) {
    return items(collection).stream().anyMatch(o -> identity(o).equals(identity(item)));
  }

  public long position(String key) {
    try {
      return Long.parseLong(get(k("position." + key), "0"));
    } catch (NumberFormatException e) {
      return 0;
    }
  }

  public static String progressKey(JSONObject item) {
    if (item.has("progressKey")) return item.optString("progressKey");
    JSONObject title = item.optJSONObject("catalog");
    return title == null ? item.optString("id", item.optString("url"))
        : Models.key(title) + ":" + item.optInt("season") + ":" + item.optInt("episode");
  }

  public static boolean episode(JSONObject item) {
    JSONObject title = item.optJSONObject("catalog");
    return title != null && title.optString("kind").equals("tv") && item.optInt("episode") > 0;
  }

  private String seriesKey(JSONObject item) {
    return Models.key(item.optJSONObject("catalog"));
  }

  public synchronized List<JSONObject> continueWatching() {
    List<JSONObject> result = new ArrayList<>();
    Set<String> seen = new HashSet<>();
    for (JSONObject item : items("history")) {
      if (!episode(item)) { result.add(item); continue; }
      String series = seriesKey(item), selected = get(k("currentSeries." + series), null);
      if (selected == null && seen.add(series) || progressKey(item).equals(selected)) result.add(item);
    }
    return result;
  }

  public boolean isWatched(JSONObject item) {
    String key = progressKey(item);
    return items("watchedEpisodes").stream().anyMatch(o -> progressKey(o).equals(key));
  }

  public JSONObject progressFor(JSONObject item) {
    String key = progressKey(item);
    for (JSONObject saved : items("history")) if (progressKey(saved).equals(key)) return saved;
    return null;
  }

  public synchronized void markPrevious(List<JSONObject> previous, JSONObject selected) {
    JSONObject current = null;
    for (JSONObject item : continueWatching())
      if (episode(item) && seriesKey(item).equals(seriesKey(selected))) { current = item; break; }
    for (JSONObject item : previous) setWatched(item, true);
    if (current == null || current.optInt("season") < selected.optInt("season")
        || current.optInt("season") == selected.optInt("season") && current.optInt("episode") < selected.optInt("episode")) promote(selected);
  }

  public synchronized void started(JSONObject media) {
    String key = progressKey(media);
    if (isWatched(media)) {
      remove("watchedEpisodes", Models.json("progressKey", key));
      put(k("position." + key), "0");
      try { media.put("position", 0); media.put("duration", 0); } catch (Exception ignored) { }
    }
    promote(media);
  }

  public synchronized void promote(JSONObject media) {
    try {
      JSONObject item = new JSONObject(media.toString());
      String key = progressKey(item);
      for (JSONObject existing : items("history"))
        if (progressKey(existing).equals(key)) {
          if (!item.has("duration")) item.put("duration", existing.optLong("duration"));
          break;
        }
      if (!item.has("position")) item.put("position", position(key));
      if (!item.has("duration")) item.put("duration", 0);
      item.put("progressKey", key);
      upsert("history", item);
      if (episode(item)) {
        put(k("currentSeries." + seriesKey(item)), key);
        remove("completedSeries", Models.json("id", seriesKey(item)));
      }
    } catch (Exception ignored) { }
  }

  public synchronized void setWatched(JSONObject media, boolean watched) {
    String key = progressKey(media);
    if (watched) {
      upsert("watchedEpisodes", Models.json("progressKey", key, "catalog", media.optJSONObject("catalog"),
          "season", media.optInt("season"), "episode", media.optInt("episode")));
      remove("history", Models.json("progressKey", key));
      put(k("position." + key), "0");
      if (episode(media) && key.equals(get(k("currentSeries." + seriesKey(media)), "")))
        put(k("currentSeries." + seriesKey(media)), "");
    } else remove("watchedEpisodes", Models.json("progressKey", key));
  }

  public synchronized void finished(JSONObject media, JSONObject next) {
    if (episode(media)) {
      setWatched(media, true);
      upsert("completedSeries", Models.json("id", seriesKey(media), "media", media));
    } else {
      remove("history", Models.json("progressKey", progressKey(media)));
      put(k("position." + progressKey(media)), "0");
    }
    if (next != null && !isWatched(next)) promote(next);
  }

  public synchronized void removeContinueWatching(JSONObject media) {
    String key = progressKey(media);
    remove("history", Models.json("progressKey", key));
    put(k("position." + key), "0");
    if (episode(media)) put(k("currentSeries." + seriesKey(media)), "");
    String title = PlaybackPrefs.titleKey(media);
    prefs.edit().remove("speed." + title).remove("source." + title)
        .remove("subtitleVisible." + title).remove("subtitleTrack." + key)
        .remove("zoom." + title).remove("trackIntent.text." + title).remove("trackIntent.audio." + title).apply();
  }

  public synchronized void progress(String key, long position, long duration, JSONObject item) {
    if (duration > 0 && position >= 0) {
      try {
        if (!episode(item) && position >= duration * .95) {
          remove("history", Models.json("progressKey", key));
          put(k("position." + key), "0");
          return;
        }
        put(k("position." + key), Long.toString(Math.max(position, 0)));
        item.put("position", position);
        item.put("duration", duration);
        item.put("progressKey", key);
        upsert("history", item);
        if (episode(item)) put(k("currentSeries." + seriesKey(item)), key);
      } catch (Exception ignored) {
      }
    }
  }

  public void mergeChannels(List<JSONObject> incoming) {
    LinkedHashMap<String, JSONObject> map = new LinkedHashMap<>();
    for (JSONObject o : items("channels")) map.put(o.optString("url"), o);
    for (JSONObject o : incoming) map.put(o.optString("url"), o);
    array("channels", new JSONArray(map.values()));
  }

  public JSONObject backup() throws Exception {
    JSONObject data = new JSONObject();
    for (Map.Entry<String, ?> e : prefs.getAll().entrySet())
      if (!e.getKey().startsWith("secret.") && !e.getKey().startsWith("serverToken.")) {
        Object value = e.getValue();
        if (value instanceof String
            && (e.getKey().endsWith(".history")
                || e.getKey().endsWith(".recent")
                || e.getKey().endsWith(".downloads"))) {
          JSONArray history;
          try {
            history = new JSONArray((String) value);
          } catch (Exception ignored) {
            history = new JSONArray();
          }
          for (JSONObject item : Models.list(history)) {
            JSONObject headers = item.optJSONObject("headers");
            boolean server =
                item.optString("id").startsWith("server:")
                    || headers != null && headers.has("X-Emby-Token");
            if (server) {
              item.remove("url");
              item.remove("headers");
              item.remove("server");
            } else if (headers != null) {
              headers.remove("Authorization");
              headers.remove("Cookie");
            }
          }
          value = history.toString();
        }
        data.put(e.getKey(), value);
      }
    return Models.json("format", "velagram-tv", "version", 1, "preferences", data);
  }

  public void restore(String text) throws Exception {
    JSONObject b = new JSONObject(text);
    if (!"velagram-tv".equals(b.optString("format")) || b.optInt("version") != 1)
      throw new Exception("Unsupported backup format");
    JSONObject p = b.getJSONObject("preferences");
    if (p.length() > 20000) throw new Exception("Backup is too large");
    for (Iterator<String> i = p.keys(); i.hasNext(); ) {
      String key = i.next();
      if (!(p.get(key) instanceof String)
          || key.startsWith("secret.")
          || key.startsWith("serverToken.")) throw new Exception("Invalid backup preference");
    }
    android.content.SharedPreferences.Editor e = prefs.edit();
    for (String key : prefs.getAll().keySet())
      if (!key.startsWith("secret.") && !key.startsWith("serverToken.")) e.remove(key);
    for (Iterator<String> i = p.keys(); i.hasNext(); ) {
      String key = i.next();
      e.putString(key, p.getString(key));
    }
    e.apply();
  }
}
