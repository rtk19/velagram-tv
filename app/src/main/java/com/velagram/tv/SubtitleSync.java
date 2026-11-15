package com.velagram.tv;

import android.content.*;
import android.net.Uri;
import java.io.*;
import org.json.*;

public final class SubtitleSync {
  public static String key(String value) {
    // Signed URLs change between sessions. The subtitle release identity must not.
    try {
      java.net.URI uri = java.net.URI.create(value);
      String query = uri.getRawQuery();
      if (query != null) query = java.util.Arrays.stream(query.split("&"))
          .filter(p -> !p.split("=", 2)[0].toLowerCase(java.util.Locale.ROOT)
              .matches("token|access_token|auth|authorization|expires|expiry|expiration|signature|sig|policy|key-pair-id|x-amz-.*"))
          .collect(java.util.stream.Collectors.joining("&"));
      return new java.net.URI(uri.getScheme(), uri.getAuthority(), uri.getPath(), query == null || query.isEmpty() ? null : query, null).toString();
    } catch (Exception ignored) { return value; }
  }

  public static String key(JSONObject subtitle) {
    String id = subtitle.optString("id");
    if (id.contains("://")) id = "";
    String label = subtitle.optString("label", subtitle.optString("name"));
    if (label.isEmpty()) return key(subtitle.optString("url"));
    return (subtitle.optString("provider", "stream") + "|" + id + "|"
        + subtitle.optString("language", "und") + "|" + label).trim().toLowerCase(java.util.Locale.ROOT);
  }
  public static Uri latest(Store store, String mediaKey, String subtitleKey, Uri fallback) {
    if (!store.get("subtitleAutoSync", "true").equals("true")) return fallback;
    for (JSONObject version : store.items("subtitleVersions"))
      if (version.optString("mediaKey").equals(mediaKey)
          && key(version.optString("subtitleKey")).equals(key(subtitleKey))) {
        File file = new File(version.optString("file"));
        if (file.isFile()) return Uri.fromFile(file);
      }
    return fallback;
  }

  public static JSONObject save(
      Context c, Store store, String mediaKey, String subtitleKey, String source, long offset)
      throws Exception {
    File folder = new File(c.getFilesDir(), "subtitle-versions");
    if (!folder.exists() && !folder.mkdirs())
      throw new IOException("Cannot create subtitle storage");
    String id = "sync:" + System.currentTimeMillis();
    boolean vtt = source.trim().startsWith("WEBVTT");
    File file = new File(folder, id.replace(':', '-') + (vtt ? ".vtt" : ".srt"));
    try (FileOutputStream out = new FileOutputStream(file)) {
      out.write(Subtitles.shift(source, offset).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
    JSONObject version =
        Models.json(
            "id",
            id,
            "name",
            "Timing " + String.format(java.util.Locale.US, "%+.1f s", offset / 1000.0),
            "mediaKey",
            mediaKey,
            "subtitleKey",
            key(subtitleKey),
            "offset",
            offset,
            "file",
            file.getAbsolutePath(),
            "updated",
            System.currentTimeMillis());
    store.upsert("subtitleVersions", version);
    return version;
  }
}
