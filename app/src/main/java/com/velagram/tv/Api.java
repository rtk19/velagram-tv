package com.velagram.tv;

import java.util.*;
import java.util.concurrent.*;
import okhttp3.*;
import org.json.*;

public final class Api {
  public static final ExecutorService IO = Executors.newFixedThreadPool(6);
  static final OkHttpClient HTTP =
      new OkHttpClient.Builder()
          .connectTimeout(20, TimeUnit.SECONDS)
          .readTimeout(40, TimeUnit.SECONDS)
          .followRedirects(true)
          .build();
  final Store store;

  public Api(Store s) {
    store = s;
  }

  public static String enc(String s) {
    try {
      return java.net.URLEncoder.encode(s, "UTF-8");
    } catch (Exception e) {
      return s;
    }
  }

  public static Map<String, String> headers(JSONObject o) {
    Map<String, String> h = new HashMap<>();
    if (o != null)
      for (Iterator<String> i = o.keys(); i.hasNext(); ) {
        String k = i.next();
        h.put(k, o.optString(k));
      }
    return h;
  }

  public static String request(String url, Map<String, String> headers, JSONObject body)
      throws Exception {
    Request.Builder b = new Request.Builder().url(url).header("User-Agent", "Velagram-TV/1.0");
    for (Map.Entry<String, String> h : headers.entrySet()) b.header(h.getKey(), h.getValue());
    if (body != null)
      b.post(RequestBody.create(body.toString(), MediaType.get("application/json")));
    try (Response r = HTTP.newCall(b.build()).execute()) {
      if (!r.isSuccessful()) throw new Exception("Service returned HTTP " + r.code());
      if (r.body() == null) throw new Exception("Empty response");
      return r.body().string();
    }
  }

  public static JSONObject get(String url) throws Exception {
    return new JSONObject(request(url, Collections.emptyMap(), null));
  }

  public JSONObject tmdb(String path) throws Exception {
    String token = store.get("secret.tmdb", BuildConfig.TMDB_TOKEN);
    if (token.isBlank()) throw new Exception("Set your TMDB access token in Settings");
    return new JSONObject(
        request(
            "https://api.themoviedb.org/3/"
                + path
                + (path.contains("?") ? "&" : "?")
                + "language="
                + enc(store.get("language", "en-US")),
            Map.of("Authorization", "Bearer " + token),
            null));
  }

  public List<JSONObject> catalog(String path, String kind) throws Exception {
    List<JSONObject> r = Models.list(tmdb(path).optJSONArray("results"));
    for (JSONObject o : r) o.put("kind", o.optString("media_type", kind));
    r.removeIf(o -> o.optString("kind").equals("person"));
    return r;
  }

  public List<JSONObject> sources(JSONObject title, int season, int episode) throws Exception {
    String kind = title.optString("kind", "movie");
    JSONObject ids = tmdb(kind + "/" + title.getInt("id") + "/external_ids");
    String imdb = ids.optString("imdb_id");
    if (imdb.isEmpty() || imdb.equals("null")) return new ArrayList<>();
    String manifest = store.get("addon", "");
    List<JSONObject> r = new ArrayList<>();
    if (!manifest.isBlank()) {
      String base = manifest.replaceAll("/manifest\\.json.*$", "").replaceAll("/$", "");
      String id = imdb + (kind.equals("tv") ? ":" + season + ":" + episode : "");
      for (JSONObject s :
          Models.list(
              get(base + "/stream/" + (kind.equals("tv") ? "series" : "movie") + "/" + id + ".json")
                  .optJSONArray("streams"))) {
        if (s.has("url")) {
          s.put(
              "title",
              s.optString("name", "Stream")
                  + " · "
                  + s.optString("description", s.optString("title", "")));
          JSONObject hints = s.optJSONObject("behaviorHints");
          if (hints != null && hints.optJSONObject("proxyHeaders") != null)
            s.put("headers", hints.getJSONObject("proxyHeaders").optJSONObject("request"));
          r.add(s);
        }
      }
    }
    return r;
  }

  public JSONObject resolve(JSONObject channel) throws Exception {
    JSONObject c = new JSONObject(channel.toString());
    String u = c.getString("url"), resolver = c.optString("resolver");
    JSONObject h = c.optJSONObject("headers");
    if (h == null) h = new JSONObject();
    if (resolver.equals("kan") || u.contains("r.il.cdn-redge.media")) {
      u = u.replace("/livedash/", "/livehls/");
      u = u.replace(".livx?", ".livx/playlist.m3u8?");
      if (u.endsWith(".livx")) u += "/playlist.m3u8";
      h.put("Referer", "https://www.kan.org.il/");
    }
    if (resolver.equals("keshet")) {
      java.net.URI uri = new java.net.URI(u);
      String path = uri.getRawPath() + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
      JSONObject ent =
          new JSONObject(
              request(
                  "https://mass.mako.co.il/ClicksStatistics/entitlementsServicesV2.jsp?et=ngt&lp="
                      + enc(path)
                      + "&rv=AKAMAI",
                  Map.of("Referer", "https://www.mako.co.il/", "Origin", "https://www.mako.co.il"),
                  null));
      String ticket = ent.getJSONArray("tickets").getJSONObject(0).getString("ticket");
      u += (u.contains("?") ? "&" : "?") + java.net.URLDecoder.decode(ticket, "UTF-8");
    }
    JSONObject d = c.optJSONObject("resolverData");
    String pattern = d == null ? "" : d.optString("regex", "");
    if (resolver.equals("hidabroot")) pattern = "<source\\s+src=[\"']([^\"']+)[\"']";
    if (!pattern.isEmpty()) {
      String html = request(u, headers(h), null);
      java.util.regex.Matcher m =
          java.util.regex.Pattern.compile(pattern, java.util.regex.Pattern.CASE_INSENSITIVE)
              .matcher(html);
      if (!m.find()) throw new Exception("No playable stream found on channel page");
      u = new java.net.URL(new java.net.URL(u), m.group(1).replace("&amp;", "&")).toString();
    }
    c.put("url", u);
    c.put("headers", h);
    return c;
  }

  public List<JSONObject> bundled(android.content.Context ctx) throws Exception {
    String text =
        new String(
            Streams.read(ctx.getAssets().open("Idan/channels.json"), 4 * 1024 * 1024),
            java.nio.charset.StandardCharsets.UTF_8);
    JSONObject root = new JSONObject(text);
    List<JSONObject> r = new ArrayList<>();
    for (Iterator<String> i = root.keys(); i.hasNext(); ) {
      String id = i.next();
      JSONObject o = root.getJSONObject(id), d = o.optJSONObject("linkDetails");
      if (d == null) continue;
      String u = d.optString("link", d.optString("live"));
      if (u.startsWith("/")) u = d.optString("host", "https://mako-streaming.akamaized.net") + u;
      if (!u.startsWith("http")) continue;
      r.add(
          Models.json(
              "id",
              id,
              "name",
              o.optString("name"),
              "url",
              u,
              "group",
              o.optString("type").equals("radio") ? "Radio" : "Israel TV",
              "logo",
              "file:///android_asset/Idan/images/" + o.optString("image"),
              "resolver",
              o.optString("module"),
              "headers",
              Models.json("Referer", d.optString("referer", "")),
              "resolverData",
              d,
              "tvgID",
              o.optString("tvgID"),
              "index",
              o.optInt("index")));
    }
    r.sort(Comparator.comparingInt(o -> o.optInt("index")));
    return r;
  }

  public JSONObject serverSignIn(String url, String user, String password, String kind)
      throws Exception {
    url = url.replaceAll("/+$", "");
    JSONObject auth =
        new JSONObject(
            request(
                url + "/Users/AuthenticateByName",
                Map.of("Authorization", serverAuth("")),
                Models.json("Username", user, "Pw", password)));
    JSONObject s =
        Models.json(
            "id",
            UUID.randomUUID().toString(),
            "name",
            kind + " · " + user,
            "url",
            url,
            "user",
            auth.getJSONObject("User").getString("Id"),
            "kind",
            kind);
    store.put("serverToken." + s.getString("id"), auth.getString("AccessToken"));
    store.upsert("servers", s);
    return s;
  }

  public static String serverAuth(String token) {
    return "MediaBrowser Client=\"Velagram TV\", Device=\"Android TV\", DeviceId=\"velagram-tv\","
        + " Version=\"1.0.0\", Token=\""
        + token
        + "\"";
  }

  public Map<String, String> serverHeaders(JSONObject s) {
    String t = store.get("serverToken." + s.optString("id"), "");
    return Map.of("X-Emby-Token", t, "Authorization", serverAuth(t));
  }

  public List<JSONObject> serverItems(JSONObject server, String parent, String search)
      throws Exception {
    List<JSONObject> result = new ArrayList<>();
    int start = 0;
    while (true) {
      String url =
          server.getString("url")
              + "/Users/"
              + server.getString("user")
              + "/Items?Fields=Overview,MediaSources,ProviderIds,SeriesName&Limit=200&StartIndex="
              + start
              + "&SortBy=SortName"
              + (parent.isEmpty() ? "" : "&ParentId=" + enc(parent))
              + (search.isEmpty() ? "" : "&Recursive=true&SearchTerm=" + enc(search));
      JSONObject response = new JSONObject(request(url, serverHeaders(server), null));
      List<JSONObject> page = Models.list(response.optJSONArray("Items"));
      result.addAll(page);
      start += page.size();
      if (page.isEmpty()
          || start >= response.optInt("TotalRecordCount", Integer.MAX_VALUE)
          || !response.has("TotalRecordCount") && page.size() < 200) break;
    }
    return result;
  }

  public JSONObject serverStream(JSONObject s, JSONObject item) throws Exception {
    JSONObject info =
        new JSONObject(
            request(
                s.getString("url") + "/Items/" + item.getString("Id") + "/PlaybackInfo",
                serverHeaders(s),
                Models.json(
                    "UserId",
                    s.getString("user"),
                    "DeviceId",
                    "velagram-tv",
                    "IsPlayback",
                    true,
                    "EnableDirectPlay",
                    true,
                    "EnableDirectStream",
                    true,
                    "EnableTranscoding",
                    true,
                    "MaxStreamingBitrate",
                    40000000)));
    JSONObject source = info.getJSONArray("MediaSources").getJSONObject(0);
    String u = source.optString("TranscodingUrl", source.optString("DirectStreamUrl", ""));
    if (u.isEmpty())
      u =
          "/Videos/"
              + item.getString("Id")
              + "/stream?static=true&MediaSourceId="
              + enc(source.getString("Id"))
              + "&api_key="
              + enc(store.get("serverToken." + s.getString("id"), ""));
    if (u.startsWith("/")) u = s.getString("url") + u;
    return Models.json(
        "id",
        "server:" + item.getString("Id"),
        "name",
        Models.title(item),
        "url",
        u,
        "headers",
        new JSONObject(serverHeaders(s)));
  }

  public List<JSONObject> searchServer(JSONObject server, JSONObject title, int season, int episode)
      throws Exception {
    String name = Models.title(title);
    List<JSONObject> out = new ArrayList<>();
    Set<String> seen = new HashSet<>();
    for (String q :
        new LinkedHashSet<>(
            Arrays.asList(
                name, title.optString("original_title", title.optString("original_name", name))))) {
      if (q.isBlank()) continue;
      for (JSONObject candidate : serverItems(server, "", q)) {
        if (season >= 0 && episode > 0
            && candidate.optString("Type").equals("Series")
            && CatalogMatch.sameName(candidate.optString("Name"), title)) {
          String url =
              server.getString("url")
                  + "/Shows/"
                  + candidate.getString("Id")
                  + "/Episodes?UserId="
                  + enc(server.getString("user"))
                  + "&Season="
                  + season
                  + "&Fields=ProviderIds,Overview,MediaSources&Limit=1000";
          for (JSONObject e :
              Models.list(
                  new JSONObject(request(url, serverHeaders(server), null)).optJSONArray("Items")))
            if (e.optInt("ParentIndexNumber") == season
                && e.optInt("IndexNumber") == episode
                && seen.add(e.optString("Id"))) out.add(e);
        } else if (!candidate.optBoolean("IsFolder")
            && CatalogMatch.matches(candidate, title, season, episode)
            && seen.add(candidate.optString("Id"))) out.add(candidate);
      }
    }
    return out;
  }

  public JSONObject details(JSONObject title) throws Exception {
    JSONObject d =
        tmdb(
            title.optString("kind", "movie")
                + "/"
                + title.optInt("id")
                + "?append_to_response=credits,external_ids,images,translations&include_image_language=en,null");
    d.put("kind", title.optString("kind", "movie"));
    JSONObject translations = d.optJSONObject("translations");
    JSONArray aliases = new JSONArray();
    if (translations != null)
      for (JSONObject translation : Models.list(translations.optJSONArray("translations"))) {
        JSONObject data = translation.optJSONObject("data");
        if (data == null) continue;
        String value = data.optString("title", data.optString("name", ""));
        if (!value.isBlank()) {
          aliases.put(value);
          if (translation.optString("iso_639_1").equals("he")) d.put("heTitle", value);
          if (translation.optString("iso_639_1").equals("en")) d.put("enTitle", value);
        }
      }
    d.put("aliases", aliases);
    JSONObject ids = d.optJSONObject("external_ids");
    if (ids != null) d.put("imdb_id", ids.optString("imdb_id"));
    return d;
  }

  public List<JSONObject> filmography(int person) throws Exception {
    JSONObject credits = tmdb("person/" + person + "/combined_credits");
    List<JSONObject> r = Models.list(credits.optJSONArray("cast"));
    r.addAll(Models.list(credits.optJSONArray("crew")));
    for (JSONObject item : r) item.put("kind", item.optString("media_type", "movie"));
    r.removeIf(item -> item.optString("poster_path").isEmpty());
    r.sort(
        (a, b) -> {
          int appearance = Boolean.compare(selfCredit(a), selfCredit(b));
          return appearance != 0
              ? appearance
              : Double.compare(b.optDouble("popularity"), a.optDouble("popularity"));
        });
    Set<String> seen = new HashSet<>();
    r.removeIf(item -> !seen.add(Models.key(item)));
    return r;
  }

  static boolean selfCredit(JSONObject item) {
    String character = item.optString("character").toLowerCase(Locale.ROOT);
    return character.startsWith("self")
        || character.startsWith("himself")
        || character.startsWith("herself");
  }
}
