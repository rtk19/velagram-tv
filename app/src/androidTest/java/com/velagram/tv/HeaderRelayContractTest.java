package com.velagram.tv;

import static org.junit.Assert.*;
import java.util.*;
import java.util.concurrent.*;
import okhttp3.*;
import okhttp3.mockwebserver.*;
import org.junit.Test;

public class HeaderRelayContractTest {
  OkHttpClient client = new OkHttpClient();
  @Test public void authHeadersRangeAndHeadReachTheServerAndTokenIsRequired() throws Exception {
    try (MockWebServer server = new MockWebServer(); HeaderRelay relay = new HeaderRelay(server.url("/video.mkv?source=1").toString(), Map.of("X-Emby-Token", "private", "Authorization", "Bearer private", "Cookie", "session=private"))) {
      server.enqueue(new MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes 2-4/10").setHeader("Accept-Ranges", "bytes").setBody("234"));
      try (Response r = client.newCall(new Request.Builder().url(relay.url()).header("Range", "bytes=2-4").build()).execute()) {
        assertEquals(206, r.code()); assertEquals("234", r.body().string()); assertEquals("bytes 2-4/10", r.header("Content-Range"));
      }
      RecordedRequest request = server.takeRequest(2, TimeUnit.SECONDS);
      assertEquals("private", request.getHeader("X-Emby-Token")); assertEquals("Bearer private", request.getHeader("Authorization"));
      assertEquals("session=private", request.getHeader("Cookie")); assertEquals("bytes=2-4", request.getHeader("Range"));
      assertEquals("/video.mkv?source=1", request.getPath());
      server.enqueue(new MockResponse().setHeader("Content-Length", "10"));
      try (Response r = client.newCall(new Request.Builder().url(relay.url()).head().build()).execute()) { assertEquals(200, r.code()); }
      assertEquals("HEAD", server.takeRequest().getMethod());
      try (Response r = client.newCall(new Request.Builder().url(relay.url().replace(relay.token, "invalid")).build()).execute()) { assertEquals(403, r.code()); }
      assertEquals(2, server.getRequestCount());
    }
  }
  @Test public void hlsRewritesChildrenAndKeysWithoutLeakingCredentialsToOtherOrigins() throws Exception {
    try (MockWebServer server = new MockWebServer(); MockWebServer other = new MockWebServer();
        HeaderRelay relay = new HeaderRelay(server.url("/folder/master.m3u8").toString(), Map.of("Authorization", "Bearer private", "User-Agent", "Velagram test"))) {
      server.enqueue(new MockResponse().setHeader("Content-Type", "application/vnd.apple.mpegurl").setBody("#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"key.bin\"\n" + other.url("/stream.m3u8") + "\n"));
      String manifest;
      try (Response r = client.newCall(new Request.Builder().url(relay.url()).build()).execute()) { manifest = r.body().string(); }
      assertEquals("Bearer private", server.takeRequest().getHeader("Authorization"));
      assertFalse(manifest.contains(other.url("/stream.m3u8").toString()));
      java.util.regex.Matcher key = java.util.regex.Pattern.compile("URI=\"([^\"]+)\"").matcher(manifest); assertTrue(key.find());
      server.enqueue(new MockResponse().setBody("key"));
      try (Response r = client.newCall(new Request.Builder().url(key.group(1)).build()).execute()) { assertEquals("key", r.body().string()); }
      assertEquals("/folder/key.bin", server.takeRequest().getPath());
      String child = Arrays.stream(manifest.split("\n")).filter(v -> v.startsWith("http")).findFirst().get();
      other.enqueue(new MockResponse().setHeader("Content-Type", "application/vnd.apple.mpegurl").setBody("#EXTM3U\nsegment.ts\n"));
      try (Response r = client.newCall(new Request.Builder().url(child).build()).execute()) { assertTrue(r.body().string().contains("127.0.0.1")); }
      RecordedRequest redirected = other.takeRequest(); assertNull(redirected.getHeader("Authorization")); assertEquals("Velagram test", redirected.getHeader("User-Agent"));
    }
  }
  @Test public void dashKeepsNamespacesAndRelativePathsButRewritesAbsoluteResources() throws Exception {
    try (HeaderRelay relay = new HeaderRelay("https://example.com/video/manifest.mpd", Collections.emptyMap())) {
      String xml = "<MPD xmlns=\"urn:mpeg:dash:schema:mpd:2011\"><BaseURL>https://cdn.example.com/video/</BaseURL><SegmentTemplate media=\"chunk-$Number$.m4s\" initialization=\"https://cdn.example.com/init.mp4?x=1&amp;y=2\"/></MPD>";
      String result = relay.rewriteDash(xml, HttpUrl.get("https://example.com/video/manifest.mpd"));
      assertTrue(result.contains("xmlns=\"urn:mpeg:dash:schema:mpd:2011\"")); assertTrue(result.contains("media=\"chunk-$Number$.m4s\""));
      assertFalse(result.contains("https://cdn.example.com")); assertTrue(result.contains("x=1&amp;y=2"));
    }
  }
}
