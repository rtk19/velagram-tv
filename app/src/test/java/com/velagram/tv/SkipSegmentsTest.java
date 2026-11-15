package com.velagram.tv;

import static org.junit.Assert.*;
import java.util.*;
import org.json.*;
import org.junit.Test;

public class SkipSegmentsTest {
  Map<SkipSegments.Kind, SkipSegments.Segment> skip(String data, long duration) throws Exception {
    return SkipSegments.parseSkip(new JSONObject("{\"segments\":" + data + "}"), duration);
  }
  @Test public void exactAndShiftedDurationMatchesWinOverIntroDB() throws Exception {
    for (String match : new String[] {"exact", "shifted"}) {
      List<SkipSegments.Segment> result = SkipSegments.merge(skip("{\"intro\":{\"start_ms\":10000,\"end_ms\":30000,\"match\":\"" + match + "\"}}", 1800000),
          SkipSegments.parseIntro(new JSONObject("{\"intro\":{\"start_sec\":12,\"end_sec\":34}}"), 1800000));
      assertEquals(SkipSegments.Source.SKIP_DB, result.get(0).source);
      assertEquals(30000, result.get(0).end);
    }
  }
  @Test public void unreliableDurationUsesIntroDBOrConfidenceThreshold() throws Exception {
    Map<SkipSegments.Kind, SkipSegments.Segment> intro = SkipSegments.parseIntro(new JSONObject("{\"intro\":{\"end_sec\":34}}"), 1800000);
    for (String match : new String[] {"agnostic", "out-of-range", "unknown"}) {
      Map<SkipSegments.Kind, SkipSegments.Segment> a = skip("{\"intro\":{\"start_ms\":1000,\"end_ms\":30000,\"match\":\"" + match + "\",\"confidence\":0.99}}", 1800000);
      assertEquals(SkipSegments.Source.INTRO_DB, SkipSegments.merge(a, intro).get(0).source);
      assertEquals(match.equals("out-of-range") ? 0 : 1, SkipSegments.merge(a, Collections.emptyMap()).size());
    }
    assertTrue(SkipSegments.merge(skip("{\"intro\":{\"start_ms\":0,\"end_ms\":30000,\"match\":\"agnostic\",\"confidence\":0.64}}", 1800000), Collections.emptyMap()).isEmpty());
    assertTrue(SkipSegments.merge(skip("{\"intro\":{\"start_ms\":0,\"end_ms\":30000,\"confidence\":0.74}}", 1800000), Collections.emptyMap()).isEmpty());
  }
  @Test public void timestampsAreValidatedAgainstTheActualCut() throws Exception {
    assertTrue(skip("{\"intro\":{\"start_ms\":-1,\"end_ms\":5000}}", 10000).isEmpty());
    assertTrue(skip("{\"intro\":{\"start_ms\":0,\"end_ms\":1000}}", 10000).isEmpty());
    assertTrue(skip("{\"intro\":{\"start_ms\":0,\"end_ms\":25001}}", 10000).isEmpty());
    assertEquals(10000, skip("{\"intro\":{\"start_ms\":0,\"end_ms\":25000}}", 10000).get(SkipSegments.Kind.INTRO).end);
    assertEquals(10000, skip("{\"outro\":{\"start_ms\":8000}}", 10000).get(SkipSegments.Kind.OUTRO).end);
    assertTrue(skip("{\"intro\":{\"start_ms\":8000}}", 10000).isEmpty());
  }
  @Test public void introDBUnderstandsNestedArraysAliasesAndClocks() throws Exception {
    Map<SkipSegments.Kind, SkipSegments.Segment> result = SkipSegments.parseIntro(new JSONObject("{\"segments\":[{\"type\":\"intro\",\"start\":\"00:00:10\",\"end\":\"01:00\",\"confidence\":90},{\"segment_type\":\"credits\",\"start_ms\":1700000},{\"type\":\"post-credit\",\"start_sec\":1750,\"end_sec\":1780}],\"recap\":{\"start_sec\":0,\"end_sec\":8}}"), 1800000);
    assertEquals(4, result.size()); assertEquals(10000, result.get(SkipSegments.Kind.INTRO).start);
    assertEquals(60000, result.get(SkipSegments.Kind.INTRO).end);
    assertEquals(.9, result.get(SkipSegments.Kind.INTRO).confidence, .0001);
    assertEquals(1800000, result.get(SkipSegments.Kind.OUTRO).end);
  }
  @Test public void bestIntroDBConfidenceThenLongestDurationWins() throws Exception {
    Map<SkipSegments.Kind, SkipSegments.Segment> result = SkipSegments.parseIntro(new JSONObject("{\"intro\":[{\"end_sec\":50,\"confidence\":0.8},{\"end_sec\":30,\"confidence\":0.9},{\"end_sec\":40,\"confidence\":0.9}]}"), 1800000);
    assertEquals(40000, result.get(SkipSegments.Kind.INTRO).end);
  }
  @Test public void actionUsesHalfOpenBoundsAndOverlapPriority() throws Exception {
    List<SkipSegments.Segment> values = new ArrayList<>(SkipSegments.parseIntro(new JSONObject("{\"intro\":{\"start_sec\":5,\"end_sec\":20},\"recap\":{\"start_sec\":10,\"end_sec\":15},\"outro\":{\"start_sec\":10,\"end_sec\":30}}"), 30000).values());
    assertNull(SkipSegments.active(values, 4999));
    assertEquals(SkipSegments.Kind.INTRO, SkipSegments.active(values, 5000).kind);
    assertEquals(SkipSegments.Kind.RECAP, SkipSegments.active(values, 10000).kind);
    assertEquals(SkipSegments.Kind.INTRO, SkipSegments.active(values, 15000).kind);
    assertNull(SkipSegments.active(values, 30000));
    assertEquals("Next Episode", SkipSegments.active(values, 20000).label(true));
    assertEquals("Skip Credits", SkipSegments.active(values, 20000).label(false));
  }
  @Test public void completionBoundariesMatchIOSRegressionCases() {
    assertFalse(PlaybackCompletion.finishedEpisode(true, 1739000, 1800000, -1));
    assertTrue(PlaybackCompletion.finishedEpisode(true, 1740000, 1800000, -1));
    assertFalse(PlaybackCompletion.finishedEpisode(true, 1499000, 1800000, 1500000));
    assertTrue(PlaybackCompletion.finishedEpisode(true, 1500000, 1800000, 1500000));
    assertFalse(PlaybackCompletion.finishedEpisode(true, 1740000, 1800000, 1780000));
    assertTrue(PlaybackCompletion.finishedEpisode(true, 1740000, 1800000, 1900000));
    assertFalse(PlaybackCompletion.finishedEpisode(false, 1799000, 1800000, -1));
    assertFalse(PlaybackCompletion.finishedEpisode(true, 0, 30000, -1));
    assertFalse(PlaybackCompletion.finishedEpisode(true, 1000, -1, -1));
    assertFalse(PlaybackCompletion.offerNext(1739000, 1800000));
    assertTrue(PlaybackCompletion.offerNext(1740000, 1800000));
  }
}
