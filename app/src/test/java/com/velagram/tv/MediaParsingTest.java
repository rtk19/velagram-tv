package com.velagram.tv;

import static org.junit.Assert.*;

import java.util.*;
import org.json.*;
import org.junit.Test;

public class MediaParsingTest {
  @Test
  public void playlistPreservesMetadataHeadersAndVod() throws Exception {
    String m =
        "#EXTM3U\n"
            + "#EXTINF:-1 tvg-id=\"kan11\" group-title=\"Israel\""
            + " tvg-logo=\"https://img/logo.png\",כאן 11\n"
            + "#EXTVLCOPT:http-referrer=https://kan.org.il/\n"
            + "/live/a.m3u8|User-Agent=TV%20Client\n"
            + "#EXTINF:-1 group-title=\"Films\",Movie\n"
            + "https://provider/movie/u/p/1.mkv\n";
    List<JSONObject> r = Models.m3u(m, "https://provider/list.m3u");
    assertEquals(2, r.size());
    assertEquals("כאן 11", r.get(0).getString("name"));
    assertEquals("https://provider/live/a.m3u8", r.get(0).getString("url"));
    assertEquals("TV Client", r.get(0).getJSONObject("headers").getString("User-Agent"));
    assertEquals("https://kan.org.il/", r.get(0).getJSONObject("headers").getString("Referer"));
    assertTrue(r.get(1).getBoolean("vod"));
  }

  @Test
  public void subtitleShiftClampsNegativeAndPreservesContent() {
    String s = "1\n00:00:01,000 --> 00:00:03,250\nHello\n";
    assertEquals("1\n00:00:00,000 --> 00:00:01,750\nHello\n", Subtitles.shift(s, -1500));
  }

  @Test
  public void xmltvUsesExplicitTimeZone() throws Exception {
    String xml =
        "<tv><programme channel='11' start='20261112190000 +0200' stop='20261112200000"
            + " +0200'><title>חדשות</title></programme></tv>";
    List<Guide.Program> r = Guide.parse(xml);
    assertEquals(1, r.size());
    assertEquals(3600000, r.get(0).end - r.get(0).start);
    assertEquals("חדשות", r.get(0).title);
    assertEquals(Guide.date("20261112170000 +0000"), r.get(0).start);
  }

  @Test
  public void xmltvRejectsExternalEntities() throws Exception {
    try {
      Guide.parse("<!DOCTYPE tv [<!ENTITY x SYSTEM 'file:///etc/passwd'>]><tv>&x;</tv>");
      fail("DOCTYPE must be rejected");
    } catch (Exception expected) {
    }
  }

  @Test
  public void titleMatchingRejectsWrongEpisodeAndYear() throws Exception {
    JSONObject title =
        Models.json("title", "The Example", "kind", "tv", "first_air_date", "2024-01-01");
    assertTrue(
        ReleaseGrouping.matches(Models.json("name", "The.Example.S02E03.1080p.mkv"), title, 2, 3));
    assertFalse(
        ReleaseGrouping.matches(Models.json("name", "The.Example.S02E04.1080p.mkv"), title, 2, 3));
    assertFalse(ReleaseGrouping.matches(Models.json("name", "The Example 2020.mp4"), title, 0, 0));
  }

  @Test
  public void shortTitleDoesNotMatchInsideAnotherWord() {
    JSONObject title = Models.json("title", "It", "kind", "movie");
    assertFalse(
        ReleaseGrouping.matches(Models.json("name", "Little.Monsters.2019.mkv"), title, 0, 0));
    assertTrue(ReleaseGrouping.matches(Models.json("name", "It.2017.1080p.mkv"), title, 0, 0));
  }

  @Test
  public void subtitleShiftPreservesWebVttSeparator() {
    assertEquals(
        "WEBVTT\n\n00:00:02.000 --> 00:00:03.000\nHello",
        Subtitles.shift("WEBVTT\n\n00:00:01.000 --> 00:00:02.000\nHello", 1000));
  }

  @Test
  public void translatedTitleMatches() {
    JSONObject title =
        Models.json("title", "The Example", "aliases", new JSONArray().put("הדוגמה"));
    assertTrue(ReleaseGrouping.matches(Models.json("name", "הדוגמה.2024.mkv"), title, 0, 0));
  }

  @Test
  public void metadataIdentityTakesPriority() {
    JSONObject candidate =
        Models.json("Name", "Localized title", "ProviderIds", Models.json("Tmdb", "42"));
    assertTrue(
        CatalogMatch.matches(
            candidate, Models.json("id", 42, "kind", "movie", "title", "English title"), 0, 0));
  }

  @Test
  public void iosNekostreamFixtureDecryptsAndGetsToken() throws Exception {
    JSONObject result =
        AnimeSources.decodeNeko(
            "wdeBruh3qqn_i5wUNnyaPbKpTPjtcIqcJEMLxz3xCfFGXP4hFKAGHO-Q-yT7pLg5yT9k5Ga1L3sCQOuNwpkEqUXxsgVoBo5VoViEgMu8-Xtpr-QEKO5n9srHbNIzDJKpZfHHAR0vXZSEtxc_1vvIqwh_VVsbwTjbWOTVpHyF0Q0",
            1000);
    String url = result.getString("file");
    assertTrue(url.startsWith("https://example.com/"));
    String token = okhttp3.HttpUrl.get(url).queryParameter("token");
    assertNotNull(token);
    String message =
        new String(
            java.util.Base64.getUrlDecoder().decode(token.split("\\.")[0]),
            java.nio.charset.StandardCharsets.UTF_8);
    assertTrue(message.startsWith("1090|"));
  }

  @Test
  public void iosMegacloudFixtureDecrypts() throws Exception {
    String result =
        AnimeSources.decryptSources(
            "abcU2FsdGVkX18xMjM0NTY3OKBGR9dW9L6KTbpXJU8nDOZUWIN8zmRGi4ewUEoRYPnSTr0E+oM3YWaMqsRBPlLqfQ==",
            "switch(x){case 0x1:a=b,c=d;}var z=0,b=0x0,d=0x3;");
    assertEquals(
        "https://example.com/master.m3u8",
        new JSONArray(result).getJSONObject(0).getString("file"));
  }

  @Test
  public void invalidAnimeKeyRangeIsRejected() throws Exception {
    try {
      AnimeSources.decryptSources("short", "switch(x){case 0x1:a=b,c=d;}var z=0,b=0xffff,d=0x3;");
      fail("Expected invalid key range to fail");
    } catch (java.io.IOException expected) {
    }
  }

  @Test
  public void communityUsesItsActualDateFieldAndMediaType() {
    JSONObject film =
        Models.json("name", "Inception", "type", "movie", "last_air_date", "2010-07-15");
    assertTrue(WebSources.communityMatch(film, "Inception", "2010", 0));
    assertFalse(WebSources.communityMatch(film, "Inception", "2011", 0));
    assertFalse(WebSources.communityMatch(film, "Inception", "2010", 1));
  }
}
