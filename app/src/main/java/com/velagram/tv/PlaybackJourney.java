package com.velagram.tv;

import android.app.Activity;
import java.util.*;
import org.json.*;

/** Shared content lifecycle for both players, including the iOS completion/skip rules. */
public final class PlaybackJourney {
  final Activity activity;
  final Store store;
  final JSONObject media;
  final CinemaControls controls;
  final Runnable nextAction;
  List<SkipSegments.Segment> segments = Collections.emptyList();
  JSONObject next;
  boolean requestedSegments, completed, released;

  public PlaybackJourney(Activity a, Store s, JSONObject item, CinemaControls ui, Runnable playNext) {
    activity = a; store = s; media = item; controls = ui; nextAction = playNext;
    store.started(media);
    controls.onTick = this::tick;
    if (Store.episode(media)) EpisodeNavigator.nextUnwatched(new Api(store), store, media, (result, error) -> {
      if (released) return;
      next = result;
      if (completed && next != null) store.promote(next);
      tick();
    });
  }

  void tick() {
    if (released) return;
    long duration = controls.playback.duration(), position = controls.playback.position();
    if (!requestedSegments && duration > 10000 && media.optJSONObject("catalog") != null) {
      requestedSegments = true;
      Api.IO.execute(() -> {
        JSONObject title = media.optJSONObject("catalog");
        String imdb = title.optString("imdb_id");
        JSONObject external = title.optJSONObject("external_ids");
        if (imdb.isEmpty() && external != null) imdb = external.optString("imdb_id");
        if (!imdb.matches("tt\\d{7,9}")) {
          try { imdb = new Api(store).tmdb(title.optString("kind", "movie") + "/" + title.optInt("id") + "/external_ids").optString("imdb_id"); }
          catch (Exception ignored) { }
        }
        List<SkipSegments.Segment> found = SkipSegments.SHARED.resolve(imdb, Store.episode(media) ? media.optInt("season") : -1, media.optInt("episode"), duration);
        activity.runOnUiThread(() -> { if (!released) { segments = found; tick(); } });
      });
    }
    SkipSegments.Segment active = SkipSegments.active(segments, position);
    boolean nextUnwatched = next != null && !store.isWatched(next);
    if (active != null) {
      controls.episodeAction(active.label(nextUnwatched), () -> {
        if (active.kind == SkipSegments.Kind.OUTRO && nextUnwatched) {
          finish(); nextAction.run();
        } else controls.playback.seek(active.end);
      });
    } else if (nextUnwatched && PlaybackCompletion.offerNext(position, duration)) {
      controls.episodeAction("Next Episode", () -> { finish(); nextAction.run(); });
    } else controls.episodeAction(null, null);
  }

  public long creditsStart() {
    for (SkipSegments.Segment segment : segments) if (segment.kind == SkipSegments.Kind.OUTRO) return segment.start;
    return -1;
  }

  public void persist(long position, long duration) {
    if (completed) return;
    if (PlaybackCompletion.finishedEpisode(Store.episode(media), position, duration, creditsStart())) finish();
    else store.progress(Store.progressKey(media), position, duration, media);
  }

  public void finish() {
    if (completed) return;
    completed = true;
    store.finished(media, next);
  }
  public void release() { released = true; controls.onTick = null; }
}
