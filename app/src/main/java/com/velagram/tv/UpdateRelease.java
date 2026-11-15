package com.velagram.tv;

import org.json.JSONArray;
import org.json.JSONObject;

/** Stable releases use a universal APK; the APK's versionCode is authoritative. */
final class UpdateRelease {
  static final String PREFIX = "https://github.com/rtk19/velagram-tv/releases/download/";
  final String tag, notes, url, digest;
  final long size;
  UpdateRelease(String tag, String notes, String url, String digest, long size) {
    this.tag = tag; this.notes = notes; this.url = url; this.digest = digest; this.size = size;
  }
  static UpdateRelease parse(JSONObject json) {
    if (json.optBoolean("draft") || json.optBoolean("prerelease")) return null;
    JSONArray assets = json.optJSONArray("assets");
    if (assets == null) return null;
    for (int i = 0; i < assets.length(); i++) {
      JSONObject a = assets.optJSONObject(i);
      if (a == null || !a.optString("name").equals("velagram-tv-universal.apk")) continue;
      String url = a.optString("browser_download_url");
      String digest = a.optString("digest");
      long size = a.optLong("size");
      if (!url.startsWith(PREFIX) || !digest.matches("sha256:[0-9a-fA-F]{64}") || size <= 0 || size > 1024L * 1024 * 1024) return null;
      return new UpdateRelease(json.optString("tag_name"), json.optString("body"), url, digest.substring(7), size);
    }
    return null;
  }
  static boolean newer(String tag, String current) {
    try {
      if (!tag.matches("v?[0-9]+\\.[0-9]+\\.[0-9]+(-tv)?") || !current.matches("[0-9]+\\.[0-9]+\\.[0-9]+(-tv)?")) return false;
      String[] a = tag.replaceFirst("^v", "").replaceFirst("-tv$", "").split("\\.");
      String[] b = current.replaceFirst("-tv$", "").split("\\.");
      if (a.length != 3 || b.length != 3) return false;
      for (int i = 0; i < 3; i++) {
        int x = Integer.parseInt(a[i]), y = Integer.parseInt(b[i]);
        if (x != y) return x > y;
      }
    } catch (NumberFormatException ignored) { }
    return false;
  }
}
