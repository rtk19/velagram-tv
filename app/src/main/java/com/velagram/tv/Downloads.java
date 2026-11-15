package com.velagram.tv;

import android.app.DownloadManager;
import android.content.*;
import android.database.Cursor;
import android.net.Uri;
import org.json.*;

public final class Downloads {
  public static void start(Context c, JSONObject item) throws Exception {
    String u = item.getString("url");
    if (!u.startsWith("http") || u.contains(".m3u8") || u.contains(".mpd"))
      throw new Exception(
          "Offline HTTP downloads require a direct media file. Telegram files can be saved from"
              + " their channel.");
    DownloadManager dm = (DownloadManager) c.getSystemService(Context.DOWNLOAD_SERVICE);
    String file =
        "media-" + System.currentTimeMillis() + "." + (u.contains(".mkv") ? "mkv" : "mp4");
    DownloadManager.Request r =
        new DownloadManager.Request(Uri.parse(u))
            .setTitle(Models.title(item))
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(c, "downloads", file);
    for (java.util.Map.Entry<String, String> h :
        Api.headers(item.optJSONObject("headers")).entrySet())
      r.addRequestHeader(h.getKey(), h.getValue());
    long id = dm.enqueue(r);
    JSONObject task = new JSONObject(item.toString());
    task.put("id", "http-download:" + id);
    task.put("downloadId", id);
    new Store(c).upsert("downloadTasks", task);
  }

  public static void reconcile(Context c) {
    Store s = new Store(c);
    DownloadManager dm = (DownloadManager) c.getSystemService(Context.DOWNLOAD_SERVICE);
    for (JSONObject task : s.items("downloadTasks")) {
      try (Cursor cursor =
          dm.query(new DownloadManager.Query().setFilterById(task.optLong("downloadId")))) {
        if (cursor != null && cursor.moveToFirst()) {
          int status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
          if (status == DownloadManager.STATUS_SUCCESSFUL) {
            task.put(
                "url",
                cursor.getString(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI)));
            s.upsert("downloads", task);
            s.remove("downloadTasks", task);
          } else if (status == DownloadManager.STATUS_FAILED) {
            s.remove("downloadTasks", task);
          }
        }
      } catch (Exception ignored) {
      }
    }
  }
}
