package com.velagram.tv;

import android.app.Activity;
import android.net.*;
import org.json.*;

/** Bounded source and initial-range preparation, respecting the iOS-style network policy. */
public final class NextPreloader {
  boolean requested;
  JSONObject prepared;
  long preparedAt;
  final Activity activity;
  final Store store;

  public NextPreloader(Activity a, Store s) {
    activity = a;
    store = s;
  }

  public void consider(JSONObject media, long position, long duration) {
    if (requested
        || !Store.episode(media)
        || duration <= 0
        || position < duration * .65
        || duration - position > 60000) return;
    String policy = store.get("preloadPolicy", "Wi-Fi Only");
    if (policy.equals("Off")) return;
    if (policy.equals("Wi-Fi Only")) {
      ConnectivityManager manager =
          (ConnectivityManager) activity.getSystemService(Activity.CONNECTIVITY_SERVICE);
      NetworkCapabilities c = manager.getNetworkCapabilities(manager.getActiveNetwork());
      if (c == null
          || !c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
              && !c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) return;
    }
    requested = true;
    EpisodeNavigator.nextUnwatched(
        new Api(store),
        store,
        media,
        (next, error) -> {
          if (error != null || activity.isFinishing()) return;
          new NextPlayback(activity, store)
              .find(
                  next,
                  media.optString("provider"),
                  () -> !activity.isFinishing(),
                  result -> {
                    if (result == null) return;
                    prepared = result;
                    preparedAt = android.os.SystemClock.elapsedRealtime();
                    String url = result.optString("url");
                    if (url.startsWith("http"))
                      Api.IO.execute(
                          () -> {
                            try {
                              okhttp3.Request.Builder request =
                                  new okhttp3.Request.Builder()
                                      .url(url)
                                      .header("Range", "bytes=0-262143");
                              for (java.util.Map.Entry<String, String> h :
                                  Api.headers(result.optJSONObject("headers")).entrySet())
                                request.header(h.getKey(), h.getValue());
                              try (okhttp3.Response response =
                                  Api.HTTP.newCall(request.build()).execute()) {
                                if (response.isSuccessful() && response.body() != null) {
                                  java.io.InputStream in = response.body().byteStream();
                                  byte[] b = new byte[8192];
                                  int total = 0, n;
                                  while (total < 262144
                                      && (n = in.read(b, 0, Math.min(b.length, 262144 - total)))
                                          > 0) total += n;
                                }
                              }
                            } catch (Exception ignored) {
                            }
                          });
                  });
        });
  }

  public JSONObject take() {
    JSONObject value =
        android.os.SystemClock.elapsedRealtime() - preparedAt > 55000 ? null : prepared;
    prepared = null;
    return value;
  }
}
