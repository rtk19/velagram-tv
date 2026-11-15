package com.velagram.tv;

import static org.junit.Assert.*;
import java.util.*;
import java.util.concurrent.*;
import okhttp3.*;
import okhttp3.mockwebserver.*;
import org.junit.Test;

public class SkipProviderContractTest {
  @Test public void requestsBothServicesWithIMDbEpisodeAndActualDurationAndCaches() throws Exception {
    try (MockWebServer a = new MockWebServer(); MockWebServer b = new MockWebServer()) {
      a.enqueue(new MockResponse().setBody("{\"segments\":{\"intro\":{\"start_ms\":10000,\"end_ms\":30000,\"match\":\"exact\"}}}"));
      b.enqueue(new MockResponse().setBody("{\"recap\":{\"start_sec\":0,\"end_sec\":8}}"));
      SkipSegments resolver = new SkipSegments(new OkHttpClient(), a.url("/api/segments").toString(), b.url("/segments").toString());
      List<SkipSegments.Segment> segments = resolver.resolve(" tt1234567 ", 0, 3, 1800450);
      assertEquals(2, segments.size());
      RecordedRequest skip = a.takeRequest(2, TimeUnit.SECONDS), intro = b.takeRequest(2, TimeUnit.SECONDS);
      assertEquals("tt1234567", skip.getRequestUrl().queryParameter("imdb_id"));
      assertEquals("1800", skip.getRequestUrl().queryParameter("duration"));
      assertEquals("conservative", skip.getRequestUrl().queryParameter("adjust"));
      assertEquals("0", skip.getRequestUrl().queryParameter("season"));
      assertEquals("3", intro.getRequestUrl().queryParameter("episode"));
      assertEquals("application/json", intro.getHeader("Accept"));
      assertEquals(2, resolver.resolve("tt1234567", 0, 3, 1800450).size());
      assertEquals(1, a.getRequestCount()); assertEquals(1, b.getRequestCount());
    }
  }
  @Test public void oneProviderFailureDoesNotSuppressOtherOrBreakPlayback() throws Exception {
    try (MockWebServer a = new MockWebServer(); MockWebServer b = new MockWebServer()) {
      a.enqueue(new MockResponse().setResponseCode(503).setBody("offline"));
      b.enqueue(new MockResponse().setBody("{\"intro\":{\"start_sec\":10,\"end_sec\":30}}"));
      SkipSegments resolver = new SkipSegments(new OkHttpClient(), a.url("/a").toString(), b.url("/b").toString());
      assertEquals(1, resolver.resolve("tt1234567", -1, 0, 900000).size());
      assertNull(a.takeRequest().getRequestUrl().queryParameter("season"));
      assertNull(b.takeRequest().getRequestUrl().queryParameter("episode"));
      assertTrue(resolver.resolve("invalid", 1, 1, 900000).isEmpty());
      assertTrue(resolver.resolve("tt1234567", 1, 1, -1).isEmpty());
      assertEquals(1, a.getRequestCount());
    }
  }
}
