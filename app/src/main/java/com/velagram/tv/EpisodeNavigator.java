package com.velagram.tv;

import android.os.*;
import java.util.*;
import java.util.function.*;
import org.json.*;

public final class EpisodeNavigator {
  public interface Catalog {
    JSONObject get(String path) throws Exception;
  }

  public static void next(Api api, JSONObject media, BiConsumer<JSONObject, String> done) {
    next(api::tmdb, media, done);
  }

  public static void nextUnwatched(Api api, Store store, JSONObject media, BiConsumer<JSONObject, String> done) {
    nextUnwatched(api::tmdb, store, media, 0, done);
  }

  static void nextUnwatched(Catalog api, Store store, JSONObject media, int depth, BiConsumer<JSONObject, String> done) {
    if (depth > 500) { done.accept(null, "No unwatched episode is available."); return; }
    next(api, media, (next, error) -> {
      if (error != null || next == null) { done.accept(null, error); return; }
      if (store.isWatched(next)) nextUnwatched(api, store, next, depth + 1, done);
      else done.accept(next, null);
    });
  }

  public static void next(Catalog api, JSONObject media, BiConsumer<JSONObject, String> done) {
    Api.IO.execute(
        () -> {
          try {
            JSONObject title = media.getJSONObject("catalog"),
                details = api.get("tv/" + title.getInt("id"));
            int season = media.optInt("season"), episode = media.optInt("episode");
            JSONObject seasonData = api.get("tv/" + title.getInt("id") + "/season/" + season);
            List<JSONObject> episodes = Models.list(seasonData.optJSONArray("episodes"));
            JSONObject next = null;
            episodes.sort(Comparator.comparingInt(o -> o.optInt("episode_number")));
            for (JSONObject candidate : episodes)
              if (candidate.optInt("episode_number") > episode) {
                next = candidate;
                break;
              }
            if (next == null) {
              int nextSeason = Integer.MAX_VALUE;
              for (JSONObject s : Models.list(details.optJSONArray("seasons")))
                if (s.optInt("season_number") > season)
                  nextSeason = Math.min(nextSeason, s.optInt("season_number"));
              if (nextSeason == Integer.MAX_VALUE) throw new Exception("You’re all caught up.");
              season = nextSeason;
              List<JSONObject> list =
                  Models.list(
                      api.get("tv/" + title.getInt("id") + "/season/" + season)
                          .optJSONArray("episodes"));
              if (list.isEmpty()) throw new Exception("The next season is not available yet.");
              next = list.get(0);
            }
            String airDate = next.optString("air_date");
            if (!airDate.isEmpty() && airDate.compareTo(java.time.LocalDate.now().toString()) > 0)
              throw new Exception("The next episode has not aired yet.");
            JSONObject result =
                Models.json(
                    "catalog",
                    title,
                    "season",
                    season,
                    "episode",
                    next.optInt("episode_number"),
                    "episodeTitle",
                    next.optString("name"),
                    "episodeStill", next.optString("still_path"),
                    "air_date", airDate);
            new Handler(Looper.getMainLooper()).post(() -> done.accept(result, null));
          } catch (Exception e) {
            new Handler(Looper.getMainLooper()).post(() -> done.accept(null, e.getMessage()));
          }
        });
  }
}
