package com.velagram.tv;

import static org.junit.Assert.*;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.*;
import org.json.*;
import org.junit.*;

/** Regression cases translated from iOS PlaybackProgressTests.swift. */
public class LibraryParityTest {
  Store store;
  PreferenceSnapshot snapshot;
  String previous;
  JSONObject show = Models.json("id", 111, "kind", "tv", "name", "Parity show");
  JSONObject episode(int season, int number) {
    return Models.json("catalog", show, "season", season, "episode", number);
  }
  @Before public void setup() {
    store = new Store(InstrumentationRegistry.getInstrumentation().getTargetContext());
    snapshot = new PreferenceSnapshot(store);
    previous = store.profile(); store.put("profile", "Parity " + UUID.randomUUID());
  }
  @After public void cleanup() {
    String prefix = "profile." + store.profile() + ".";
    android.content.SharedPreferences.Editor editor = store.prefs.edit();
    for (String key : store.prefs.getAll().keySet()) if (key.startsWith(prefix)) editor.remove(key);
    editor.apply(); snapshot.restore();
  }
  void progress(JSONObject item, long position) { store.progress(Store.progressKey(item), position, 1800000, item); }
  @Test public void separateEpisodePositionsOneCurrentSelectionAndReload() {
    JSONObject a = episode(3, 4), b = episode(1, 1);
    progress(a, 792000); progress(b, 75000);
    assertEquals(2, store.items("history").size()); assertEquals(1, store.continueWatching().size());
    assertEquals(1, store.continueWatching().get(0).optInt("episode"));
    store.started(a);
    assertEquals(4, store.continueWatching().get(0).optInt("episode"));
    Store reload = new Store(InstrumentationRegistry.getInstrumentation().getTargetContext());
    assertEquals(792000, reload.position(Store.progressKey(a))); assertEquals(75000, reload.position(Store.progressKey(b)));
    assertEquals(1, reload.continueWatching().size());
  }
  @Test public void finishPromotesNextUpAtZero() {
    JSONObject a = episode(1, 1), b = episode(1, 2); progress(a, 1750000);
    store.finished(a, b);
    JSONObject next = store.continueWatching().get(0);
    assertEquals(2, next.optInt("episode")); assertEquals(0, next.optLong("position")); assertEquals(0, next.optLong("duration"));
    assertTrue(store.isWatched(a)); assertEquals(0, store.position(Store.progressKey(a)));
  }
  @Test public void promotionPreservesExistingNextPositionAndDuration() {
    JSONObject a = episode(1, 1), b = episode(1, 2); progress(b, 792000); progress(a, 100000);
    store.finished(a, episode(1, 2));
    JSONObject next = store.continueWatching().get(0);
    assertEquals(792000, next.optLong("position")); assertEquals(1800000, next.optLong("duration"));
  }
  @Test public void finishingFinalEpisodeDoesNotRevealOlderProgress() {
    JSONObject a = episode(1, 1), b = episode(3, 20); progress(a, 50000); progress(b, 900000);
    store.finished(b, null);
    assertTrue(store.continueWatching().isEmpty()); assertEquals(1, store.items("history").size());
    assertEquals(1, store.items("completedSeries").size());
  }
  @Test public void replayResetsWatchedStateAndResume() {
    JSONObject item = episode(1, 1); progress(item, 1799000); store.finished(item, null);
    store.started(item); assertFalse(store.isWatched(item)); assertEquals(0, store.position(Store.progressKey(item)));
    assertEquals(0, store.continueWatching().get(0).optLong("position"));
  }
  @Test public void moviesDisappearAt95PercentAndResumeResets() {
    JSONObject movie = Models.json("catalog", Models.json("kind", "movie", "id", 10));
    store.progress(Store.progressKey(movie), 949000, 1000000, movie); assertEquals(1, store.continueWatching().size());
    store.progress(Store.progressKey(movie), 950000, 1000000, movie); assertTrue(store.continueWatching().isEmpty());
    assertEquals(0, store.position(Store.progressKey(movie)));
  }
  @Test public void removingContinueWatchingResetsTitlePreferences() {
    JSONObject item = episode(1, 1); String title = PlaybackPrefs.titleKey(item);
    store.put("speed." + title, "1.5"); store.put("source." + title, "VLC"); store.put("zoom." + title, "4");
    store.put("subtitleVisible." + title, "false"); progress(item, 120000); store.removeContinueWatching(item);
    assertEquals(0, store.position(Store.progressKey(item))); assertEquals("missing", store.get("speed." + title, "missing"));
    assertEquals("missing", store.get("source." + title, "missing")); assertEquals("missing", store.get("subtitleVisible." + title, "missing"));
    assertTrue(store.continueWatching().isEmpty());
  }
  @Test public void episodeIdentityIncludesSpecialsAndProfilesAreIsolated() {
    JSONObject special = episode(0, 3); progress(special, 50000); assertTrue(Store.episode(special));
    String profile = store.profile(); store.put("profile", "Other parity profile"); assertTrue(store.continueWatching().isEmpty());
    store.put("profile", profile); assertEquals(1, store.continueWatching().size());
  }
  @Test public void markingPreviousAcrossSeasonsPromotesSelectedWithoutFinishingIt() {
    JSONObject current = episode(1, 2), selected = episode(2, 3);
    progress(current, 50000);
    store.markPrevious(Arrays.asList(episode(1, 1), current, episode(2, 1), episode(2, 2)), selected);
    assertTrue(store.isWatched(current)); assertTrue(store.isWatched(episode(2, 2)));
    assertFalse(store.isWatched(selected));
    assertEquals(Store.progressKey(selected), Store.progressKey(store.continueWatching().get(0)));
  }
  @Test public void subtitleVisibilityAndSourcePreferencePersistAcrossEpisodes() {
    JSONObject one = episode(1, 1), two = episode(2, 5);
    PlaybackPrefs.subtitles(store, one, false); store.put("source." + PlaybackPrefs.titleKey(one), "Jellyfin");
    assertFalse(PlaybackPrefs.subtitles(store, two)); assertEquals("Jellyfin", PlaybackPrefs.preferredProvider(store, two));
    store.promote(one); store.removeContinueWatching(one);
    assertEquals(store.get("subtitlesEnabled", "true").equals("true"), PlaybackPrefs.subtitles(store, two));
    assertEquals("", PlaybackPrefs.preferredProvider(store, two));
  }
  @Test public void subtitleTimingIdentitySurvivesTokensButSeparatesDifferentFiles() {
    assertEquals(SubtitleSync.key("https://example.com/sub?id=1&token=first"), SubtitleSync.key("https://example.com/sub?id=1&token=second"));
    assertNotEquals(SubtitleSync.key("https://example.com/sub?id=1"), SubtitleSync.key("https://example.com/sub?id=2"));
    JSONObject first = Models.json("provider", "Wizdom", "language", "he", "name", "Show.1080p", "url", "https://example.com/one?token=a");
    JSONObject later = Models.json("provider", "Wizdom", "language", "he", "name", "Show.1080p", "url", "https://example.com/two?token=b");
    assertEquals(SubtitleSync.key(first), SubtitleSync.key(later));
  }
}
