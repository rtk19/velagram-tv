package com.velagram.tv;

import java.util.*;
import org.json.*;

public final class Models {
  public static JSONObject obj(String text) {
    try {
      return new JSONObject(text);
    } catch (Exception e) {
      return new JSONObject();
    }
  }

  public static JSONObject json(Object... pairs) {
    JSONObject o = new JSONObject();
    try {
      for (int i = 0; i < pairs.length; i += 2) o.put((String) pairs[i], pairs[i + 1]);
    } catch (Exception e) {
      throw new IllegalArgumentException(e);
    }
    return o;
  }

  public static List<JSONObject> list(JSONArray a) {
    List<JSONObject> r = new ArrayList<>();
    if (a != null)
      for (int i = 0; i < a.length(); i++) {
        JSONObject o = a.optJSONObject(i);
        if (o != null) r.add(o);
      }
    return r;
  }

  public static String title(JSONObject o) {
    return o.optString("title", o.optString("name", o.optString("Name", "Untitled")));
  }

  public static String key(JSONObject o) {
    return o.optString("kind", "movie") + ":" + o.optString("id");
  }

  public static String image(JSONObject o) {
    String p = o.optString("poster_path", "");
    return p.isEmpty() ? o.optString("logo", "") : "https://image.tmdb.org/t/p/w342" + p;
  }

  public static String year(JSONObject o) {
    String s = o.optString("release_date", o.optString("first_air_date", ""));
    return s.length() >= 4 ? s.substring(0, 4) : "";
  }

  public static List<JSONObject> m3u(String text, String origin) {
    List<JSONObject> out = new ArrayList<>();
    Set<String> seen = new HashSet<>();
    String name = null, group = "IPTV", logo = "", id = "", type = "";
    JSONObject headers = new JSONObject();
    for (String raw : text.replace("\r", "").replace("\ufeff", "").split("\n")) {
      String line = raw.trim();
      if (line.startsWith("#EXTINF:")) {
        boolean quoted = false;
        int comma = -1;
        for (int i = 0; i < line.length(); i++) {
          char c = line.charAt(i);
          if (c == '"') quoted = !quoted;
          if (c == ',' && !quoted) {
            comma = i;
            break;
          }
        }
        name = comma >= 0 ? line.substring(comma + 1) : attr(line, "tvg-name", "Channel");
        group = attr(line, "group-title", "IPTV");
        logo = attr(line, "tvg-logo", "");
        id = attr(line, "tvg-id", "");
        type = attr(line, "tvg-type", attr(line, "type", ""));
        headers = new JSONObject();
      } else if (line.startsWith("#EXTGRP:")) group = line.substring(8);
      else if (line.startsWith("#EXTVLCOPT:http-user-agent=")) {
        try {
          headers.put("User-Agent", line.substring(line.indexOf('=') + 1));
        } catch (Exception ignored) {
        }
      } else if (line.startsWith("#EXTVLCOPT:http-referrer=")) {
        try {
          headers.put("Referer", line.substring(line.indexOf('=') + 1));
        } catch (Exception ignored) {
        }
      } else if (!line.isEmpty() && !line.startsWith("#") && name != null) {
        try {
          String[] parts = line.split("\\|", 2);
          String url = new java.net.URL(new java.net.URL(origin), parts[0]).toString();
          if (!url.startsWith("http://") && !url.startsWith("https://")) {
            name = null;
            continue;
          }
          if (parts.length > 1)
            for (String h : parts[1].split("&")) {
              String[] kv = h.split("=", 2);
              if (kv.length == 2) headers.put(kv[0], java.net.URLDecoder.decode(kv[1], "UTF-8"));
            }
          String words = (type + " " + group).toLowerCase(Locale.ROOT);
          boolean vod =
              url.contains("/movie/")
                  || url.contains("/series/")
                  || url.matches("(?i).*\\.(mp4|mkv|avi|mov|m4v)(?:\\?.*)?")
                  || words.matches(".*(vod|movies|series|סרטים|סדרות).*");
          if (seen.add(url))
            out.add(
                json(
                    "id",
                    "iptv:" + Integer.toHexString(url.hashCode()),
                    "name",
                    name,
                    "url",
                    url,
                    "group",
                    group,
                    "logo",
                    logo,
                    "tvgID",
                    id,
                    "headers",
                    headers,
                    "origin",
                    origin,
                    "vod",
                    vod));
        } catch (Exception ignored) {
        }
        name = null;
      }
    }
    return out;
  }

  static String attr(String line, String key, String fallback) {
    java.util.regex.Matcher m =
        java.util.regex.Pattern.compile(java.util.regex.Pattern.quote(key) + "=\"([^\"]*)\"")
            .matcher(line);
    return m.find() ? m.group(1) : fallback;
  }
}
