package com.velagram.tv;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import java.util.regex.*;
import javax.crypto.*;
import javax.crypto.spec.*;
import okhttp3.*;
import org.json.*;
import org.jsoup.*;
import org.jsoup.nodes.*;

/**
 * Read-only provider protocols ported from the iOS native anime clients. No page-script execution.
 */
public final class AnimeSources {
  static final String UA =
      "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 Chrome/131.0.0.0"
          + " Safari/537.36";

  static String capture(String regex, String text) {
    Matcher m = Pattern.compile(regex, Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(text);
    return m.find() ? m.group(1) : "";
  }

  static String get(String url, String referer, Map<String, String> form) throws Exception {
    Request.Builder b =
        new Request.Builder()
            .url(url)
            .header("User-Agent", UA)
            .header("Referer", referer)
            .header("Origin", origin(url))
            .header("X-Requested-With", "XMLHttpRequest")
            .header("Cookie", "country_code=US");
    if (form != null) {
      FormBody.Builder body = new FormBody.Builder();
      for (Map.Entry<String, String> f : form.entrySet()) body.add(f.getKey(), f.getValue());
      b.post(body.build());
    }
    try (Response r = Api.HTTP.newCall(b.build()).execute()) {
      if (!r.isSuccessful() || r.body() == null)
        throw new IOException("Anime provider unavailable");
      return r.body().string();
    }
  }

  static String origin(String u) throws Exception {
    URI x = new URI(u);
    return x.getScheme() + "://" + x.getHost();
  }

  static Document ajax(String url, String referer, Map<String, String> form) throws Exception {
    JSONObject j = new JSONObject(get(url, referer, form));
    return Jsoup.parse(j.optString("result", j.optString("html")), url);
  }

  static String attr(Element e, String name) {
    return e == null ? "" : e.attr(name);
  }

  static boolean same(String name, Set<String> aliases, int season) {
    String normalized = ReleaseGrouping.words(name);
    for (String alias : aliases) {
      String wanted = ReleaseGrouping.words(alias);
      if (normalized.equals(wanted)
          || season > 1
              && (normalized.equals(wanted + " season " + season)
                  || normalized.equals(wanted + " " + season + "nd season")
                  || normalized.equals(wanted + " " + season + "rd season")
                  || normalized.equals(wanted + " " + season + "th season"))) return true;
    }
    return false;
  }

  public static List<JSONObject> search(
      Api api, String site, JSONObject title, int season, int episode) throws Exception {
    boolean hi = site.equals("HiAnime");
    String base = hi ? "https://hianime.cv" : "https://anikoto.net";
    Set<String> aliases = new LinkedHashSet<>();
    aliases.add(Models.title(title));
    aliases.add(title.optString("enTitle"));
    aliases.add(title.optString("original_title", title.optString("original_name")));
    JSONArray a = title.optJSONArray("aliases");
    if (a != null) for (int i = 0; i < a.length(); i++) aliases.add(a.optString(i));
    aliases.remove("");
    List<String> queries = new ArrayList<>();
    if (season > 1) queries.add(Models.title(title) + " Season " + season);
    queries.add(Models.title(title));
    if (!title.optString("original_name").isEmpty()) queries.add(title.optString("original_name"));
    Set<String> visited = new HashSet<>();
    List<List<JSONObject>> matches = new ArrayList<>();
    JSONObject seasonData =
        season > 0 ? api.tmdb("tv/" + title.optInt("id") + "/season/" + season) : new JSONObject();
    String episodeTitle = "";
    for (JSONObject e : Models.list(seasonData.optJSONArray("episodes")))
      if (e.optInt("episode_number") == episode) episodeTitle = e.optString("name");
    for (String query : queries) {
      for (int page = 1; page <= 2; page++) {
        String url = base + (hi ? "/?s=" : "/filter?keyword=") + Api.enc(query) + "&page=" + page;
        Document search = Jsoup.parse(get(url, base, null), base);
        for (Element link : search.select(hi ? "a[href*=/anime/]" : "a[href*=/watch/]")) {
          String detailUrl = link.absUrl("href").split("/ep-")[0];
          if (!detailUrl.startsWith(base + "/") || !visited.add(detailUrl)) continue;
          List<String> names =
              new ArrayList<>(
                  Arrays.asList(
                      link.text(),
                      link.attr("title"),
                      link.attr("data-jname"),
                      link.attr("data-jp"),
                      link.attr("data-en")));
          if (names.stream().noneMatch(n -> same(n, aliases, season))) continue;
          String detailText = get(detailUrl, base, null);
          Document detail = Jsoup.parse(detailText, detailUrl);
          Element h = detail.selectFirst("h1,h2.film-name");
          if (h != null) {
            names.add(h.text());
            names.add(h.attr("data-jname"));
          }
          Element info = detail.selectFirst("#w-info,.anisc-info");
          if (info == null) info = detail;
          String type = "", aired = "";
          for (Element div : info.select("div")) {
            if (div.text().startsWith("Type:")) type = div.text();
            if (div.text().startsWith("Aired:")) aired = div.text();
          }
          if (type.isEmpty()) {
            Element breadcrumb = detail.selectFirst(".breadcrumb a[href*=/type/]");
            if (breadcrumb != null) type = breadcrumb.text();
          }
          if (type.isEmpty()) continue;
          boolean movie = type.toLowerCase(Locale.ROOT).contains("movie");
          if (movie != title.optString("kind", "movie").equals("movie")) continue;
          boolean separate =
              season > 1
                  && names.stream()
                      .anyMatch(
                          n ->
                              same(n, aliases, season)
                                  && ReleaseGrouping.words(n).contains("season"));
          String expected = separate ? seasonData.optString("air_date") : Models.year(title);
          String year = capture("\\b((?:19|20)\\d{2})\\b", aired);
          if (!year.isEmpty() && !expected.isEmpty() && !expected.startsWith(year)) continue;
          long aliasMatches =
              names.stream()
                  .filter(n -> same(n, aliases, 1))
                  .map(ReleaseGrouping::words)
                  .distinct()
                  .count();
          if (movie && year.isEmpty() && aliasMatches < 2) continue;
          List<JSONObject> servers =
              servers(
                  api,
                  site,
                  base,
                  detailUrl,
                  detail,
                  title,
                  season,
                  episode,
                  separate,
                  episodeTitle,
                  year.isEmpty());
          if (!servers.isEmpty()) matches.add(servers);
        }
        if (!matches.isEmpty()) break;
      }
      if (!matches.isEmpty()) break;
    }
    return matches.size() == 1 ? matches.get(0) : new ArrayList<>();
  }

  static List<JSONObject> servers(
      Api api,
      String site,
      String base,
      String detailUrl,
      Document detail,
      JSONObject title,
      int season,
      int episode,
      boolean separate,
      String episodeTitle,
      boolean missingYear)
      throws Exception {
    boolean hi = site.equals("HiAnime");
    Document episodes;
    String nonce = "", watch = detailUrl;
    if (!hi) {
      String id = attr(detail.selectFirst("#watch-main"), "data-id");
      if (id.isEmpty()) return new ArrayList<>();
      episodes = ajax(base + "/ajax/episode/list/" + id, detailUrl, null);
    } else {
      Element play = detail.selectFirst("a.btn-play");
      if (play == null) return new ArrayList<>();
      watch = play.absUrl("href");
      String text =
          get(
              watch
                  + (watch.contains("?") ? "&" : "?")
                  + "_refresh="
                  + (System.currentTimeMillis() / 60000),
              detailUrl,
              null);
      Document page = Jsoup.parse(text, watch);
      String id = attr(page.selectFirst("#ani_detail"), "data-anime-id");
      nonce = capture("\"episode_nonce\"\\s*:\\s*\"([^\"]+)\"", text);
      if (id.isEmpty() || nonce.isEmpty()) return new ArrayList<>();
      episodes =
          ajax(
              base + "/wp-admin/admin-ajax.php",
              watch,
              Map.of("action", "hianime_episode_list", "anime_id", id, "nonce", nonce));
    }
    int number = episode;
    boolean needEvidence = missingYear && season > 0;
    if (season > 1 && !separate) {
      JSONObject info = api.tmdb("tv/" + title.optInt("id"));
      int prior = 0;
      for (JSONObject s : Models.list(info.optJSONArray("seasons")))
        if (s.optInt("season_number") > 0 && s.optInt("season_number") < season)
          prior += s.optInt("episode_count");
      number += prior;
      needEvidence = true;
    }
    List<Element> choices = new ArrayList<>();
    for (Element e : episodes.select("a[data-ids],a.ep-item")) {
      String n = e.attr(hi ? "data-number" : "data-num");
      try {
        if (season <= 0 || Integer.parseInt(n) == number) choices.add(e);
      } catch (Exception ignored) {
      }
    }
    if (choices.size() != 1) return new ArrayList<>();
    Element chosen = choices.get(0);
    String sourceTitle = chosen.attr("title");
    Element et = chosen.selectFirst(".d-title,.ep-name");
    if (et != null) sourceTitle = et.text();
    if (needEvidence && !agree(episodeTitle, sourceTitle)) return new ArrayList<>();
    String id = chosen.attr(hi ? "data-id" : "data-ids");
    Document serverList =
        hi
            ? ajax(
                base + "/wp-admin/admin-ajax.php",
                watch,
                Map.of("action", "hianime_episode_servers", "episode_id", id, "nonce", nonce))
            : ajax(base + "/ajax/server/list?servers=" + Api.enc(id), watch, null);
    List<JSONObject> result = new ArrayList<>();
    for (Element e : serverList.select("[data-link-id],[data-hash]")) {
      String embed = "", linkId = e.attr("data-link-id"), name = e.attr("data-server-name");
      if (name.isEmpty()) name = e.text();
      String hash = e.attr("data-hash");
      if (!hash.isEmpty())
        try {
          embed = new String(Base64.getDecoder().decode(hash), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
      if (embed.isEmpty() && linkId.isEmpty()) continue;
      String type = e.attr("data-type");
      Element ancestor = e.parent();
      while (type.isEmpty() && ancestor != null) {
        type = ancestor.attr("data-type");
        ancestor = ancestor.parent();
      }
      String language = (type + name).toLowerCase(Locale.ROOT).contains("dub") ? "en" : "ja";
      result.add(
          Models.json(
              "id",
              site + ":" + linkId + ":" + embed,
              "name",
              name + " · " + (language.equals("en") ? "English dub" : "Japanese"),
              "animeSite",
              site,
              "base",
              base,
              "embed",
              embed,
              "linkId",
              linkId,
              "referer",
              watch,
              "audioLanguage",
              language));
    }
    return result;
  }

  static boolean agree(String a, String b) {
    if (a.isBlank() || b.isBlank()) return false;
    if (ReleaseGrouping.words(a).equals(ReleaseGrouping.words(b))) return true;
    Set<String> x = new HashSet<>(Arrays.asList(ReleaseGrouping.words(a).split(" "))),
        y = new HashSet<>(Arrays.asList(ReleaseGrouping.words(b).split(" ")));
    x.removeAll(Arrays.asList("the", "a", "an", "of", "is", "to", "and", "episode"));
    y.removeAll(Arrays.asList("the", "a", "an", "of", "is", "to", "and", "episode"));
    Set<String> common = new HashSet<>(x);
    common.retainAll(y);
    return common.size() >= 3
        && common.size() / (double) Math.max(1, Math.min(x.size(), y.size())) >= .75;
  }

  public static JSONObject resolve(JSONObject candidate) throws Exception {
    String embed = candidate.optString("embed");
    if (embed.isEmpty()) {
      JSONObject response =
          new JSONObject(
              get(
                  candidate.getString("base")
                      + "/ajax/server?get="
                      + Api.enc(candidate.getString("linkId")),
                  candidate.getString("referer"),
                  null));
      embed = response.getJSONObject("result").getString("url");
    }
    JSONObject resolved = resolveEmbed(embed, candidate.getString("referer"), 0);
    resolved.put("name", candidate.optString("name"));
    return resolved;
  }

  static JSONObject resolveEmbed(String embed, String referer, int depth) throws Exception {
    if (depth >= 3) throw new IOException("Embedded stream nesting exceeds limit");
    String origin = origin(embed);
    JSONObject headers = Models.json("Referer", origin + "/", "Origin", origin, "User-Agent", UA);
    if (new URI(embed).getPath().matches("(?i).*\\.(m3u8|mp4)$"))
      return Models.json(
          "url",
          embed,
          "headers",
          Models.json("Referer", referer, "Origin", origin(referer), "User-Agent", UA));
    String page = get(embed, referer, null);
    Document tree = Jsoup.parse(page, embed);
    String id = attr(tree.selectFirst("#megaplay-player,.form-area[data-id]"), "data-id");
    if (!id.isEmpty()) {
      String type = capture("type:\\s*['\"]([^'\"]+)['\"]", page);
      for (String endpoint : new String[] {"getSourcesNew", "getSources"})
        try {
          JSONObject object =
              new JSONObject(
                  get(
                      origin
                          + "/stream/"
                          + endpoint
                          + "?id="
                          + Api.enc(id)
                          + (type.isEmpty() ? "" : "&type=" + Api.enc(type)),
                      embed,
                      null));
          if (object.has("enc"))
            object.put(
                "sources", decodeNeko(object.getString("enc"), System.currentTimeMillis() / 1000));
          return source(object, embed, headers);
        } catch (Exception ignored) {
        }
      throw new IOException("Embedded stream unavailable");
    }
    if (embed.contains("/embed-") || embed.contains("/e-")) {
      String token = token(page);
      String endpoint =
          embed.substring(0, embed.lastIndexOf('/') + 1)
              + "getSources?id="
              + Api.enc(embed.substring(embed.lastIndexOf('/') + 1))
              + (token.isEmpty() ? "" : "&_k=" + Api.enc(token));
      JSONObject object = new JSONObject(get(endpoint, embed, null));
      if (object.opt("sources") instanceof String) {
        String script = get(origin + "/js/player/a/prod/e1-player.min.js", embed, null);
        object.put("sources", new JSONArray(decryptSources(object.getString("sources"), script)));
      }
      return source(object, embed, headers);
    }
    Element direct = tree.selectFirst("source[src],video[src]");
    if (direct != null) return Models.json("url", direct.absUrl("src"), "headers", headers);
    Element child = tree.selectFirst("iframe[src]");
    if (child != null) return resolveEmbed(child.absUrl("src"), embed, depth + 1);
    throw new IOException("No playable source found");
  }

  static JSONObject source(JSONObject object, String base, JSONObject headers) throws Exception {
    List<JSONObject> sources = new ArrayList<>();
    Object value = object.opt("sources");
    if (value instanceof JSONArray) sources = Models.list((JSONArray) value);
    if (value instanceof JSONObject) {
      sources.add((JSONObject) value);
      sources.addAll(Models.list(((JSONObject) value).optJSONArray("list")));
    }
    for (JSONObject source : sources) {
      String file = source.optString("file");
      if (!file.isEmpty()) {
        String url = new URL(new URL(base), file).toString();
        if (!url.startsWith("http")) continue;
        JSONArray tracks = new JSONArray();
        for (JSONObject track : Models.list(object.optJSONArray("tracks"))) {
          String kind = track.optString("kind");
          if (!kind.isEmpty() && !kind.equals("captions") && !kind.equals("subtitles")) continue;
          String path = track.optString("file");
          if (!path.isEmpty())
            tracks.put(
                Models.json(
                    "url",
                    new URL(new URL(base), path).toString(),
                    "label",
                    track.optString("label", "Subtitle"),
                    "language",
                    track.optString("srclang", "en")));
        }
        return Models.json("url", url, "headers", headers, "subtitles", tracks);
      }
    }
    throw new IOException("No playable source found");
  }

  static byte[] decrypt(byte[] encrypted, byte[] key, byte[] iv) throws Exception {
    Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
    cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
    return cipher.doFinal(encrypted);
  }

  public static JSONObject decodeNeko(String encoded, long now) throws Exception {
    byte[] key = Arrays.copyOf("i?LMTAx0Q6,:}50U".getBytes(StandardCharsets.UTF_8), 32);
    byte[] decoded =
        decrypt(
            Base64.getUrlDecoder().decode(encoded),
            key,
            "W0;27ToaUpl_P%'c".getBytes(StandardCharsets.UTF_8));
    JSONObject payload = new JSONObject(new String(decoded, StandardCharsets.UTF_8));
    String file = payload.getString("file");
    okhttp3.HttpUrl.Builder url = okhttp3.HttpUrl.get(file).newBuilder();
    String path = capture("/([a-f0-9]{32}/[a-f0-9]{32})/", file);
    if (okhttp3.HttpUrl.get(file).queryParameter("token") == null && !path.isEmpty()) {
      String message = (now + 90) + "|" + path.toLowerCase(Locale.ROOT);
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(
          new SecretKeySpec(
              "MpCdnT0k3n!9f2K#xQ7vL5mR8wN1pY4s".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      String token =
          Base64.getUrlEncoder()
                  .withoutPadding()
                  .encodeToString(message.getBytes(StandardCharsets.UTF_8))
              + "."
              + Base64.getUrlEncoder()
                  .withoutPadding()
                  .encodeToString(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
      url.addQueryParameter("token", token);
    }
    return Models.json("file", url.build().toString());
  }

  static String token(String html) {
    Matcher m =
        Pattern.compile(
                "\\w+\\s*=\\s*\\{[^}]*?\\w+:\\s*\"([^\"]+)\",\\s*\\w+:\\s*\"([^\"]+)\"(?:,\\s*\\w+:\\s*\"([^\"]+)\")?")
            .matcher(html);
    if (m.find()) return m.group(1) + m.group(2) + (m.group(3) == null ? "" : m.group(3));
    Matcher t = Pattern.compile("[A-Za-z0-9+/=]{30,}").matcher(html);
    String result = "";
    while (t.find()) if (t.group().length() > result.length()) result = t.group();
    return result;
  }

  public static String decryptSources(String encoded, String script) throws Exception {
    Matcher matches =
        Pattern.compile(
                "case\\s*0x[0-9a-f]+:(?![^;]*=partKey)\\s*\\w+\\s*=\\s*(\\w+)\\s*,\\s*\\w+\\s*=\\s*(\\w+);",
                Pattern.CASE_INSENSITIVE)
            .matcher(script);
    char[] chars = encoded.toCharArray();
    StringBuilder secret = new StringBuilder();
    int offset = 0, count = 0;
    while (matches.find()) {
      int start =
          Integer.parseInt(
                  capture("," + Pattern.quote(matches.group(1)) + "=((?:0x)?[0-9a-fA-F]+)", script)
                      .replace("0x", ""),
                  16)
              + offset;
      int length =
          Integer.parseInt(
              capture("," + Pattern.quote(matches.group(2)) + "=((?:0x)?[0-9a-fA-F]+)", script)
                  .replace("0x", ""),
              16);
      if (start < 0 || length <= 0 || start + length > chars.length)
        throw new IOException("Invalid stream key layout");
      for (int i = start; i < start + length; i++) {
        secret.append(chars[i]);
        chars[i] = ' ';
      }
      offset += length;
      count++;
    }
    if (count == 0) throw new IOException("Stream key not found");
    byte[] cipher = Base64.getDecoder().decode(new String(chars).replace(" ", ""));
    if (cipher.length <= 16
        || !new String(cipher, 0, 8, StandardCharsets.US_ASCII).equals("Salted__"))
      throw new IOException("Invalid encrypted stream");
    byte[] salt = Arrays.copyOfRange(cipher, 8, 16), previous = new byte[0];
    ByteArrayOutputStream material = new ByteArrayOutputStream();
    while (material.size() < 48) {
      ByteArrayOutputStream input = new ByteArrayOutputStream();
      input.write(previous);
      input.write(secret.toString().getBytes(StandardCharsets.UTF_8));
      input.write(salt);
      previous = MessageDigest.getInstance("MD5").digest(input.toByteArray());
      material.write(previous);
    }
    byte[] all = material.toByteArray();
    return new String(
        decrypt(
            Arrays.copyOfRange(cipher, 16, cipher.length),
            Arrays.copyOfRange(all, 0, 32),
            Arrays.copyOfRange(all, 32, 48)),
        StandardCharsets.UTF_8);
  }
}
