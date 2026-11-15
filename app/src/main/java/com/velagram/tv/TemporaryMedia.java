package com.velagram.tv;

import java.util.*;
import java.util.concurrent.TimeUnit;
import org.drinkless.tdlib.TdApi;
import org.json.*;

/** Only files observed through our streaming adapter are eligible. Never traverses TDLib directories. */
public final class TemporaryMedia {
  private static final Object LOCK = new Object();
  private static int leases;
  static void acquire() { synchronized (LOCK) { leases++; } }
  static void release() { synchronized (LOCK) { leases = Math.max(0, leases - 1); } }
  static void touch(Telegram tg, int id) {
    tg.context.getSharedPreferences("temporary-media", 0).edit().putLong("file." + id, System.currentTimeMillis()).apply();
  }
  public static long budget(long configured, long free) {
    // Leave at least 256 MB free; no "unlimited" streaming media on constrained TV storage.
    return Math.max(0, Math.min(configured > 0 ? configured : 500L * 1048576,
        Math.max(0, free - 256L * 1048576) / 2));
  }
  @androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
  public static void trim(Telegram tg, boolean clear, java.util.function.Consumer<String> done) {
    Api.IO.execute(() -> {
      String result;
      synchronized (LOCK) {
        if (leases > 0 || PlaybackService.activeSession != null) {
          tg.main.post(() -> done.accept("Temporary media is in use. Try again after playback or downloads."));
          return;
        }
      }
      try {
          android.content.SharedPreferences tracked = tg.context.getSharedPreferences("temporary-media", 0);
          List<Map.Entry<String, ?>> entries = new ArrayList<>(tracked.getAll().entrySet());
          entries.sort(Comparator.comparingLong(e -> ((Number) e.getValue()).longValue()));
          Set<Integer> saved = new HashSet<>();
          // Include every profile, not just the currently selected one. Explicit offline saves are permanent exclusions.
          for (Map.Entry<String, ?> e : tg.store.prefs.getAll().entrySet())
            if (e.getKey().endsWith("downloads") || e.getKey().endsWith("downloadTasks")) {
              try { for (JSONObject item : Models.list(new JSONArray(String.valueOf(e.getValue()))))
                if (item.has("file")) saved.add(item.optInt("file")); } catch (Exception ignored) { }
            }
          long limit = clear ? 0 : budget(Long.parseLong(tg.store.get("cacheMB", "500")) * 1048576, tg.context.getFilesDir().getUsableSpace());
          long total = 0, removed = 0;
          Map<Integer, TdApi.File> files = new LinkedHashMap<>();
          for (Map.Entry<String, ?> e : entries) {
            int id = Integer.parseInt(e.getKey().substring(5));
            if (saved.contains(id) || tg.store.get("temporary.keep." + id, "false").equals("true")) continue;
            TdApi.File file = tg.file(id);
            files.put(id, file); total += file.local.downloadedSize;
          }
          long cutoff = System.currentTimeMillis() - 24L * 60 * 60 * 1000;
          for (Map.Entry<String, ?> e : entries) {
            int id = Integer.parseInt(e.getKey().substring(5));
            TdApi.File file = files.get(id);
            if (file == null || file.local.isDownloadingActive) continue;
            if (total <= limit && ((Number) e.getValue()).longValue() >= cutoff) continue;
            synchronized (LOCK) {
              if (leases > 0 || PlaybackService.activeSession != null) break;
              if (tg.store.get("temporary.keep." + id, "false").equals("true")) continue;
              java.util.concurrent.CompletableFuture<TdApi.Object> answer = new java.util.concurrent.CompletableFuture<>();
              tg.client.send(new TdApi.DeleteFile(id), answer::complete);
              TdApi.Object response = answer.get(1, TimeUnit.SECONDS);
              if (response instanceof TdApi.Ok) {
                total -= file.local.downloadedSize; removed += file.local.downloadedSize;
                tracked.edit().remove(e.getKey()).apply();
              }
            }
          }
          result = "Freed " + removed / 1048576 + " MB of streamed media. Saved downloads and login data kept.";
        } catch (Exception e) { result = "Temporary media cleanup unavailable. Existing files kept."; }
      final String message = result;
      tg.main.post(() -> done.accept(message));
    });
  }
}
