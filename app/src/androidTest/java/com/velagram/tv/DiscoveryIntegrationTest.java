package com.velagram.tv;

import static org.junit.Assert.*;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.*;
import okhttp3.mockwebserver.*;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class DiscoveryIntegrationTest {
  Store store;
  PreferenceSnapshot snapshot;
  MockWebServer server;

  @Before
  public void setup() throws Exception {
    store = new Store(InstrumentationRegistry.getInstrumentation().getTargetContext());
    snapshot = new PreferenceSnapshot(store);
    store.put("profile", "Integration test " + UUID.randomUUID());
    server = new MockWebServer();
    server.start();
  }

  @After
  public void cleanup() throws Exception {
    server.shutdown();
    snapshot.restore();
  }

  void response(String json) {
    server.enqueue(new MockResponse().setHeader("Content-Type", "application/json").setBody(json));
  }

  @Test
  public void jellyfinSearchFindsCorrectEpisodeAndNegotiatesPlayback() throws Exception {
    response(
        "{\"Items\":[{\"Id\":\"show\",\"Name\":\"The"
            + " Example\",\"Type\":\"Series\",\"IsFolder\":true}],\"TotalRecordCount\":1}");
    response(
        "{\"Items\":[{\"Id\":\"wrong\",\"Name\":\"Wrong\",\"ParentIndexNumber\":2,\"IndexNumber\":4},{\"Id\":\"right\",\"Name\":\"Right"
            + " episode\",\"ParentIndexNumber\":2,\"IndexNumber\":3}]}");
    JSONObject connection =
        Models.json(
            "id", "fixture", "url", server.url("/").toString().replaceAll("/$", ""), "user", "me");
    store.put("serverToken.fixture", "fixture-token");
    Api api = new Api(store);
    List<JSONObject> matches =
        api.searchServer(
            connection, Models.json("id", 123, "kind", "tv", "name", "The Example"), 2, 3);
    assertEquals(1, matches.size());
    assertEquals("right", matches.get(0).getString("Id"));
    assertTrue(server.takeRequest().getPath().contains("SearchTerm=The+Example"));
    assertTrue(server.takeRequest().getPath().contains("/Shows/show/Episodes"));
    response(
        "{\"MediaSources\":[{\"Id\":\"source\",\"DirectStreamUrl\":\"/Videos/right/stream.mp4\"}]}");
    JSONObject stream = api.serverStream(connection, matches.get(0));
    assertTrue(stream.getString("url").endsWith("/Videos/right/stream.mp4"));
    RecordedRequest request = server.takeRequest();
    assertEquals("fixture-token", request.getHeader("X-Emby-Token"));
    assertTrue(request.getPath().contains("PlaybackInfo"));
    store.put("serverToken.fixture", "");
  }

  @Test
  public void xtreamCatalogMatchesOnlyTheRequestedEpisode() throws Exception {
    response(
        "[{\"series_id\":42,\"name\":\"The Example\"},{\"series_id\":99,\"name\":\"Other Show\"}]");
    response(
        "{\"episodes\":{\"2\":[{\"id\":\"100\",\"episode_num\":2,\"title\":\"Wrong\",\"container_extension\":\"mkv\"},{\"id\":\"101\",\"episode_num\":3,\"title\":\"Right\",\"container_extension\":\"mkv\"}]}}");
    JSONObject account =
        Models.json(
            "id",
            "fixture",
            "url",
            server.url("/").toString(),
            "username",
            "demo",
            "password",
            "demo");
    List<JSONObject> sources =
        new Xtream(account).search(Models.json("name", "The Example", "kind", "tv"), 2, 3);
    assertEquals(1, sources.size());
    assertEquals(2, sources.get(0).getInt("season"));
    assertEquals(3, sources.get(0).getInt("episode"));
    assertTrue(sources.get(0).getString("url").endsWith("/101.mkv"));
  }

  @Test
  public void speedPersistsAcrossEpisodesAndEngines() {
    JSONObject title = Models.json("id", 101, "kind", "tv"),
        one = Models.json("catalog", title, "season", 1, "episode", 1),
        two = Models.json("catalog", title, "season", 2, "episode", 4);
    store.put("speed." + PlaybackPrefs.titleKey(one), "1.25");
    assertEquals(1.25f, PlaybackPrefs.speed(store, two), 0f);
    store.put("speed." + PlaybackPrefs.titleKey(one), "1.0");
  }

  @Test
  public void secretValuesAreNotExported() throws Exception {
    store.put("secret.integration", "private-value");
    String raw = store.prefs.getString("secret.integration", "");
    assertFalse(raw.contains("private-value"));
    assertEquals("private-value", store.get("secret.integration", ""));
    for (String collection : new String[] {"history", "recent", "downloads"})
      store.upsert(
          collection,
          Models.json(
              "id",
              "server:private",
              "name",
              "Example",
              "url",
              "https://example.com/stream?api_key=private-value",
              "headers",
              Models.json(
                  "X-Emby-Token", "private-value", "Authorization", "Bearer private-value")));
    assertFalse(store.backup().toString().contains("private-value"));
    for (String collection : new String[] {"history", "recent", "downloads"})
      store.array(collection, new JSONArray());
    store.put("secret.integration", "");
  }

  @Test
  public void playlistSearchStreamsIncrementallyAndCancels() throws Exception {
    store.put("source.community", "false");
    store.put("source.animeil", "false");
    store.put("source.telegram", "false");
    store.put("source.addon", "false");
    store.mergeChannels(
        Models.m3u(
            "#EXTM3U\n"
                + "#EXTINF:-1 group-title=\"Movies\",The Example 2024\n"
                + "https://provider/movie/1.mkv\n"
                + "#EXTINF:-1 group-title=\"Movies\",The Example 2020\n"
                + "https://provider/movie/2.mkv\n",
            "https://provider/list.m3u"));
    java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
    List<JSONObject> matches = new ArrayList<>();
    SourceDiscovery discovery =
        new SourceDiscovery(InstrumentationRegistry.getInstrumentation().getTargetContext(), store);
    discovery.search(
        Models.json("title", "The Example", "kind", "movie", "release_date", "2024-01-01"),
        0,
        0,
        (provider, items, status) -> {
          if (provider.equals("IPTV playlists") && !status.equals("Searching")) {
            matches.addAll(items);
            latch.countDown();
          }
        });
    assertTrue(latch.await(5, java.util.concurrent.TimeUnit.SECONDS));
    assertEquals(1, matches.size());
    discovery.cancel();
    for (String key : new String[] {"community", "animeil", "telegram", "addon"})
      store.put("source." + key, "true");
  }

  @Test
  public void nextEpisodeRollsOverToNextSeason() throws Exception {
    java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
    JSONObject[] result = new JSONObject[1];
    String[] error = new String[1];
    EpisodeNavigator.Catalog api =
        path -> {
          if (path.equals("tv/101"))
            return Models.json(
                "seasons",
                new JSONArray()
                    .put(Models.json("season_number", 1))
                    .put(Models.json("season_number", 2)));
          if (path.endsWith("season/1"))
            return Models.json(
                "episodes",
                new JSONArray()
                    .put(Models.json("episode_number", 1))
                    .put(Models.json("episode_number", 2)));
          return Models.json(
              "episodes",
              new JSONArray().put(Models.json("episode_number", 1, "name", "New season")));
        };
    EpisodeNavigator.next(
        api,
        Models.json("catalog", Models.json("id", 101, "kind", "tv"), "season", 1, "episode", 2),
        (next, problem) -> {
          result[0] = next;
          error[0] = problem;
          latch.countDown();
        });
    assertTrue(latch.await(5, java.util.concurrent.TimeUnit.SECONDS));
    assertNull(error[0]);
    assertEquals(2, result[0].getInt("season"));
    assertEquals(1, result[0].getInt("episode"));
  }

  @Test
  public void latestSubtitleSyncCanBeRestoredAndDisabled() throws Exception {
    android.content.Context context =
        InstrumentationRegistry.getInstrumentation().getTargetContext();
    JSONObject version =
        SubtitleSync.save(
            context,
            store,
            "episode-test",
            "subtitle-test",
            "1\n00:00:01,000 --> 00:00:03,000\nHello",
            500);
    android.net.Uri fallback = android.net.Uri.parse("file:///original.srt");
    store.put("subtitleAutoSync", "true");
    assertNotEquals(
        fallback, SubtitleSync.latest(store, "episode-test", "subtitle-test", fallback));
    store.put("subtitleAutoSync", "false");
    assertEquals(fallback, SubtitleSync.latest(store, "episode-test", "subtitle-test", fallback));
    store.put("subtitleAutoSync", "true");
    store.remove("subtitleVersions", version);
  }
}
