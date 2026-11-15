package com.velagram.tv;

import android.app.*;
import android.os.*;
import java.util.*;
import java.util.function.*;
import org.json.*;

/** Reuses the preferred provider first, with a bounded fallback to the source picker. */
public final class NextPlayback {
  final Activity activity;
  final Store store;
  SourceDiscovery discovery;
  boolean completed;
  JSONObject fallback;
  final Handler main = new Handler(Looper.getMainLooper());

  public NextPlayback(Activity a, Store s) {
    activity = a;
    store = s;
  }

  public void find(
      JSONObject next, String preferred, BooleanSupplier active, Consumer<JSONObject> done) {
    discovery = new SourceDiscovery(activity, store);
    Runnable finish = () -> finish(next, active, done);
    main.postDelayed(finish, 18000);
    discovery.search(
        next.optJSONObject("catalog"),
        next.optInt("season"),
        next.optInt("episode"),
        (provider, items, status) -> {
          if (completed || !active.getAsBoolean()) return;
          if (!items.isEmpty()) {
            if (fallback == null) fallback = items.get(0);
            if (preferred.isEmpty() || preferred.equals(provider)) {
              fallback = items.get(0);
              main.removeCallbacks(finish);
              finish.run();
            }
          }
        });
  }

  void finish(JSONObject next, BooleanSupplier active, Consumer<JSONObject> done) {
    if (completed) return;
    completed = true;
    discovery.cancel();
    if (!active.getAsBoolean()) return;
    JSONObject chosen = fallback;
    if (chosen == null) {
      done.accept(null);
      return;
    }
    Api.IO.execute(
        () -> {
          try {
            JSONObject media = discovery.resolve(chosen);
            JSONObject title = next.getJSONObject("catalog");
            media.put("catalog", title);
            media.put("provider", chosen.optString("provider"));
            media.put("season", next.optInt("season"));
            media.put("episode", next.optInt("episode"));
            media.put(
                "name",
                Models.title(title)
                    + " · S"
                    + next.optInt("season")
                    + " E"
                    + next.optInt("episode"));
            media.put(
                "progressKey",
                Models.key(title) + ":" + next.optInt("season") + ":" + next.optInt("episode"));
            main.post(
                () -> {
                  if (active.getAsBoolean()) done.accept(media);
                });
          } catch (Exception e) {
            main.post(
                () -> {
                  if (active.getAsBoolean()) done.accept(null);
                });
          }
        });
  }
}
