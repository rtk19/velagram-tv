package com.velagram.tv;

import java.util.*;
import java.util.concurrent.*;
import okhttp3.*;
import org.json.*;

/** Port of iOS Core/SkipSegments.swift. All timestamps use milliseconds. */
public final class SkipSegments {
  public enum Kind { INTRO, RECAP, OUTRO, PREVIEW }
  public enum Source { SKIP_DB, INTRO_DB }

  public static final class Segment {
    public final Kind kind;
    public final long start, end;
    public final Source source;
    public final Double confidence;
    final String match;

    Segment(Kind k, long s, long e, Source provider, Double c, String m) {
      kind = k; start = s; end = e; source = provider; confidence = c; match = m;
    }

    public boolean contains(long position) { return position >= start && position < end; }
    public String label(boolean nextUnwatched) {
      switch (kind) {
        case INTRO: return "Skip Intro";
        case RECAP: return "Skip Recap";
        case PREVIEW: return "Skip Preview";
        default: return nextUnwatched ? "Next Episode" : "Skip Credits";
      }
    }
  }

  static final ExecutorService PROVIDERS = Executors.newFixedThreadPool(2);
  private final OkHttpClient http;
  private final String skipEndpoint, introEndpoint;
  private final Map<String, List<Segment>> cache = new LinkedHashMap<>();
  public static final SkipSegments SHARED = new SkipSegments(
      new OkHttpClient.Builder().callTimeout(8, TimeUnit.SECONDS).build(),
      "https://api.skipdb.tv/api/segments", "https://api.introdb.app/segments");

  public SkipSegments(OkHttpClient client, String skip, String intro) {
    http = client.newBuilder().callTimeout(8, TimeUnit.SECONDS).build();
    skipEndpoint = skip; introEndpoint = intro;
  }

  public List<Segment> resolve(String imdb, int season, int episode, long duration) {
    imdb = imdb == null ? "" : imdb.trim();
    if (!imdb.matches("tt\\d{7,9}") || duration <= 0) return Collections.emptyList();
    long seconds = Math.round(duration / 1000.0);
    String key = imdb + ":" + season + ":" + episode + ":" + seconds;
    synchronized (cache) { if (cache.containsKey(key)) return cache.get(key); }
    HttpUrl.Builder skip = HttpUrl.get(skipEndpoint).newBuilder()
        .addQueryParameter("imdb_id", imdb).addQueryParameter("duration", Long.toString(seconds))
        .addQueryParameter("adjust", "conservative");
    HttpUrl.Builder intro = HttpUrl.get(introEndpoint).newBuilder().addQueryParameter("imdb_id", imdb);
    if (season >= 0 && episode > 0) {
      for (HttpUrl.Builder url : new HttpUrl.Builder[] {skip, intro})
        url.addQueryParameter("season", Integer.toString(season)).addQueryParameter("episode", Integer.toString(episode));
    }
    Future<Object> a = PROVIDERS.submit(() -> fetch(skip.build()));
    Future<Object> b = PROVIDERS.submit(() -> fetch(intro.build()));
    List<Segment> result;
    try { result = merge(parseSkip(a.get(), duration), parseIntro(b.get(), duration)); }
    catch (Exception ignored) { a.cancel(true); b.cancel(true); return Collections.emptyList(); }
    synchronized (cache) {
      cache.put(key, result);
      if (cache.size() > 200) cache.remove(cache.keySet().iterator().next());
    }
    return result;
  }

  private Object fetch(HttpUrl url) {
    try (Response response = http.newCall(new Request.Builder().url(url)
        .header("Accept", "application/json").header("User-Agent", "Velagram-TV").build()).execute()) {
      if (!response.isSuccessful() || response.body() == null) return new JSONObject();
      String body = response.body().string();
      if (body.length() > 1048576) return new JSONObject();
      return new JSONTokener(body).nextValue();
    } catch (Exception ignored) { return new JSONObject(); }
  }

  static Segment validated(Kind kind, double start, double end, long duration, Source source, Double confidence, String match) {
    if (!Double.isFinite(start) || !Double.isFinite(end) || start < 0 || end <= start + 1000
        || start >= duration || end > duration + 15000 || Math.min(end, duration) <= start + 1000) return null;
    return new Segment(kind, Math.round(start), Math.min(Math.round(end), duration), source, confidence, match);
  }

  public static Map<Kind, Segment> parseSkip(Object root, long duration) {
    Map<Kind, Segment> result = new EnumMap<>(Kind.class);
    if (!(root instanceof JSONObject)) return result;
    JSONObject values = ((JSONObject) root).optJSONObject("segments");
    if (values == null) return result;
    for (Kind kind : Kind.values()) {
      JSONObject v = values.optJSONObject(kind.name().toLowerCase(Locale.ROOT));
      if (v == null) continue;
      Double start = number(v.opt("start_ms")), end = number(v.opt("end_ms"));
      if (start == null) continue;
      Segment s = validated(kind, start, end == null ? (kind == Kind.OUTRO ? duration : 0) : end,
          duration, Source.SKIP_DB, number(v.opt("confidence")), v.optString("match").toLowerCase(Locale.ROOT));
      if (s != null) result.put(kind, s);
    }
    return result;
  }

  public static Map<Kind, Segment> parseIntro(Object root, long duration) {
    Map<Kind, Segment> result = new EnumMap<>(Kind.class);
    collect(root, null, duration, result);
    return result;
  }

  private static void collect(Object value, Kind fallback, long duration, Map<Kind, Segment> result) {
    if (value instanceof JSONArray) {
      for (int i = 0; i < ((JSONArray) value).length(); i++) collect(((JSONArray) value).opt(i), fallback, duration, result);
      return;
    }
    if (!(value instanceof JSONObject)) return;
    JSONObject o = (JSONObject) value;
    collect(o.opt("segments"), fallback, duration, result);
    Kind kind = kind(o.optString("segment_type", o.optString("type", fallback == null ? "" : fallback.name())));
    if (kind != null) {
      Double start = time(o, "start"), end = time(o, "end"), confidence = number(o.opt("confidence"));
      if (confidence != null && confidence > 1) confidence /= 100;
      Segment candidate = validated(kind, start == null ? 0 : start,
          end == null ? (kind == Kind.OUTRO ? duration : 0) : end, duration, Source.INTRO_DB, confidence, "");
      Segment previous = result.get(kind);
      if (candidate != null && (previous == null || score(candidate) > score(previous)
          || score(candidate) == score(previous) && candidate.end - candidate.start > previous.end - previous.start)) result.put(kind, candidate);
    }
    for (Iterator<String> it = o.keys(); it.hasNext();) {
      String key = it.next(); Kind nested = kind(key);
      if (nested != null) collect(o.opt(key), nested, duration, result);
    }
  }

  private static double score(Segment s) { return s.confidence == null ? .5 : s.confidence; }
  static Kind kind(String value) {
    switch (value.toLowerCase(Locale.ROOT)) {
      case "intro": return Kind.INTRO;
      case "recap": return Kind.RECAP;
      case "outro": case "credit": case "credits": return Kind.OUTRO;
      case "preview": case "post-credit": case "post_credits": case "postcredits": return Kind.PREVIEW;
      default: return null;
    }
  }
  private static Double number(Object value) {
    try { double n = Double.parseDouble(String.valueOf(value)); return Double.isFinite(n) ? n : null; }
    catch (Exception ignored) { return null; }
  }
  private static Double time(JSONObject o, String key) {
    Double ms = number(o.opt(key + "_ms")); if (ms != null) return ms;
    Object seconds = o.has(key + "_sec") ? o.opt(key + "_sec") : o.opt(key);
    Double numeric = number(seconds); if (numeric != null) return numeric * 1000;
    if (!(seconds instanceof String)) return null;
    String[] parts = ((String) seconds).split(":", -1);
    if (parts.length > 3 || parts.length < 1) return null;
    double total = 0;
    for (String part : parts) { Double n = number(part); if (n == null) return null; total = total * 60 + n; }
    return total * 1000;
  }

  public static List<Segment> merge(Map<Kind, Segment> skip, Map<Kind, Segment> intro) {
    List<Segment> result = new ArrayList<>();
    for (Kind kind : Kind.values()) {
      Segment a = skip.get(kind), b = intro.get(kind), chosen = b;
      if (a != null) {
        switch (a.match) {
          case "exact": case "shifted": chosen = a; break;
          case "agnostic": if (b == null && scoreOrZero(a) >= .65) chosen = a; break;
          case "out-of-range": break;
          default: if (b == null && scoreOrZero(a) >= .75) chosen = a;
        }
      }
      if (chosen != null) result.add(chosen);
    }
    result.sort(Comparator.comparingLong((Segment s) -> s.start).thenComparingLong(s -> s.end));
    return Collections.unmodifiableList(result);
  }
  private static double scoreOrZero(Segment s) { return s.confidence == null ? 0 : s.confidence; }
  public static Segment active(List<Segment> segments, long position) {
    for (Kind kind : new Kind[] {Kind.RECAP, Kind.INTRO, Kind.OUTRO, Kind.PREVIEW})
      for (Segment s : segments) if (s.kind == kind && s.contains(position)) return s;
    return null;
  }
}
