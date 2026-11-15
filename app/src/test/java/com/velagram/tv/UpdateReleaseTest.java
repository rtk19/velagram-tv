package com.velagram.tv;
import org.junit.Test;
import org.json.*;
import static org.junit.Assert.*;
public class UpdateReleaseTest {
  private JSONObject release() throws Exception {
    JSONObject asset = new JSONObject().put("name", "velagram-tv-universal.apk")
        .put("browser_download_url", UpdateRelease.PREFIX + "v1.4.0/velagram-tv-universal.apk")
        .put("digest", "sha256:" + new String(new char[64]).replace('\0', 'a')).put("size", 100);
    return new JSONObject().put("tag_name", "v1.4.0").put("assets", new JSONArray().put(asset));
  }
  @Test public void comparesNumericStableVersions() {
    assertTrue(UpdateRelease.newer("v1.10.0", "1.3.0-tv"));
    assertTrue(UpdateRelease.newer("v2.0.0", "1.99.99-tv"));
    assertFalse(UpdateRelease.newer("v1.3.0", "1.3.0-tv"));
    assertFalse(UpdateRelease.newer("v1.2.9", "1.3.0-tv"));
    assertFalse(UpdateRelease.newer("v1.4.0-beta", "1.3.0-tv"));
    assertFalse(UpdateRelease.newer("invalid", "1.3.0-tv"));
  }
  @Test public void acceptsUniversalChecksummedAsset() throws Exception { assertNotNull(UpdateRelease.parse(release())); }
  @Test public void rejectsUnpublishedAndPrerelease() throws Exception {
    assertNull(UpdateRelease.parse(release().put("draft", true)));
    assertNull(UpdateRelease.parse(release().put("prerelease", true)));
  }
  @Test public void rejectsMissingDigestAndForeignDownloads() throws Exception {
    JSONObject r = release(); r.getJSONArray("assets").getJSONObject(0).remove("digest");
    assertNull(UpdateRelease.parse(r));
    r = release(); r.getJSONArray("assets").getJSONObject(0).put("browser_download_url", "https://example.com/update.apk");
    assertNull(UpdateRelease.parse(r));
  }
  @Test public void rejectsWrongAssetAndEmptyDownloads() throws Exception {
    JSONObject r = release(); r.getJSONArray("assets").getJSONObject(0).put("name", "arm64.apk");
    assertNull(UpdateRelease.parse(r));
    r = release(); r.getJSONArray("assets").getJSONObject(0).put("size", 0);
    assertNull(UpdateRelease.parse(r));
  }
}
