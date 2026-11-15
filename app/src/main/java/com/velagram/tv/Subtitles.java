package com.velagram.tv;

import android.content.Context;
import java.io.*;
import java.util.*;
import java.util.zip.*;
import org.json.*;

public final class Subtitles {
  final Api api;
  final Store store;

  public Subtitles(Api a, Store s) {
    api = a;
    store = s;
  }

  public List<JSONObject> search(JSONObject title, int season, int episode) throws Exception {
    List<JSONObject> results =
        searchLanguage(title, season, episode, store.get("subtitleLanguage", "en"));
    String backup = store.get("subtitleBackup", "");
    if (!backup.isBlank()
        && !backup.equals(store.get("subtitleLanguage", "en"))
        && (results.isEmpty() || store.get("subtitleMode", "Fast").equals("Comprehensive")))
      results.addAll(searchLanguage(title, season, episode, backup));
    return results;
  }

  private List<JSONObject> searchLanguage(JSONObject title, int season, int episode, String lang)
      throws Exception {
    List<JSONObject> r = new ArrayList<>();
    String kind = title.optString("kind", "movie"),
        imdb = api.tmdb(kind + "/" + title.optInt("id") + "/external_ids").optString("imdb_id"),
        identifier = imdb + (season > 0 ? ":" + season + ":" + episode : "");
    List<String> bases = new ArrayList<>();
    String addon = store.get("addon", "");
    if (!addon.isEmpty() && store.get("subtitle.provider.addon", "true").equals("true"))
      bases.add(addon.replaceAll("/manifest\\.json.*$", "").replaceAll("/$", ""));
    if (store.get("subtitle.provider.wizdom", "true").equals("true"))
      bases.add("https://4b139a4b7f94-wizdom-stremio-v2.baby-beamup.club");
    if (store.get("subtitle.provider.ktuvit", "true").equals("true"))
      bases.add("https://4b139a4b7f94-ktuvit-stremio.baby-beamup.club");
    for (String base : bases)
      try {
        for (JSONObject sub :
            Models.list(
                Api.get(
                        base
                            + "/subtitles/"
                            + (kind.equals("tv") ? "series" : "movie")
                            + "/"
                            + identifier
                            + ".json")
                    .optJSONArray("subtitles"))) {
          String language = sub.optString("lang");
          if (language.equals(lang)
              || language.startsWith(lang)
              || lang.equals("he") && language.equals("heb")
              || lang.equals("en") && language.equals("eng"))
            r.add(
                Models.json(
                    "name",
                    language + " · " + sub.optString("id", "Subtitle"),
                    "url",
                    sub.getString("url")));
        }
      } catch (Exception ignored) {
      }
    String key = store.get("secret.subdl", BuildConfig.SUBDL_KEY);
    if (!key.isEmpty() && store.get("subtitle.provider.subdl", "true").equals("true"))
      try {
        String url =
            "https://api.subdl.com/api/v2/subtitles/search?imdb_id="
                + imdb
                + "&type="
                + (season > 0 ? "tv" : "movie")
                + "&unpack=1&subs_per_page=30&languages="
                + Api.enc(lang)
                + (season > 0 ? "&season=" + season + "&episode=" + episode : "");
        JSONObject payload =
            new JSONObject(
                Api.request(
                    url,
                    Map.of("Authorization", "Bearer " + key, "Accept", "application/json"),
                    null));
        for (JSONObject sub : Models.list(payload.optJSONArray("subtitles")))
          for (JSONObject file : Models.list(sub.optJSONArray("unpack_files"))) {
            if (season > 0
                && (file.optInt("season", season) != 0 && file.optInt("season", season) != season
                    || file.optInt("episode", episode) != 0
                        && file.optInt("episode", episode) != episode)) continue;
            String u =
                new java.net.URL(new java.net.URL("https://api.subdl.com/"), file.getString("url"))
                    .toString();
            okhttp3.HttpUrl parsed =
                okhttp3.HttpUrl.get(u).newBuilder().removeAllQueryParameters("api_key").build();
            r.add(
                Models.json(
                    "name",
                    "SubDL · "
                        + file.optString(
                            "release_name",
                            sub.optString("release_name", file.optString("name", "Subtitle"))),
                    "url",
                    parsed.toString(),
                    "headers",
                    Models.json("Authorization", "Bearer " + key)));
          }
      } catch (Exception ignored) {
      }
    return r;
  }

  public static File download(Context ctx, JSONObject sub) throws Exception {
    okhttp3.Request.Builder request = new okhttp3.Request.Builder().url(sub.getString("url"));
    for (Map.Entry<String, String> header : Api.headers(sub.optJSONObject("headers")).entrySet())
      request.header(header.getKey(), header.getValue());
    okhttp3.Request req = request.build();
    byte[] data;
    try (okhttp3.Response response = Api.HTTP.newCall(req).execute()) {
      if (!response.isSuccessful() || response.body() == null)
        throw new IOException("Subtitle download failed");
      data = Streams.read(response.body().byteStream(), 20 * 1024 * 1024);
    }
    if (data.length > 20 * 1024 * 1024) throw new IOException("Subtitle is too large");
    String suffix = sub.optString("url").contains(".vtt") ? ".vtt" : ".srt";
    if (data.length > 2 && data[0] == 'P' && data[1] == 'K') {
      try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(data))) {
        ZipEntry entry;
        boolean found = false;
        while ((entry = zip.getNextEntry()) != null) {
          String name = entry.getName().toLowerCase();
          if (!entry.isDirectory() && (name.endsWith(".srt") || name.endsWith(".vtt"))) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = zip.read(buf)) != -1) {
              if (out.size() + n > 10 * 1024 * 1024)
                throw new IOException("Subtitle exceeds size limit");
              out.write(buf, 0, n);
            }
            data = out.toByteArray();
            suffix = name.endsWith(".vtt") ? ".vtt" : ".srt";
            found = true;
            break;
          }
        }
        if (!found) throw new IOException("Archive contains no SRT or VTT subtitle");
      }
    }
    File file = new File(ctx.getCacheDir(), "subtitle-" + UUID.randomUUID() + suffix);
    try (FileOutputStream out = new FileOutputStream(file)) {
      out.write(data);
    }
    return file;
  }

  public static String shift(String text, long offset) {
    java.util.regex.Matcher m =
        java.util.regex.Pattern.compile("(\\d{2,}):(\\d{2}):(\\d{2})([,.])(\\d{3})").matcher(text);
    StringBuffer b = new StringBuffer();
    while (m.find()) {
      long t =
          Math.max(
              0,
              Long.parseLong(m.group(1)) * 3600000
                  + Long.parseLong(m.group(2)) * 60000
                  + Long.parseLong(m.group(3)) * 1000
                  + Long.parseLong(m.group(5))
                  + offset);
      String value =
          String.format(
              Locale.US,
              "%02d:%02d:%02d" + m.group(4) + "%03d",
              t / 3600000,
              t / 60000 % 60,
              t / 1000 % 60,
              t % 1000);
      m.appendReplacement(b, value);
    }
    m.appendTail(b);
    return b.toString();
  }
}
