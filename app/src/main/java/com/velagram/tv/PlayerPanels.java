package com.velagram.tv;

import android.app.Activity;
import android.content.Intent;
import android.widget.Toast;
import java.util.*;
import org.json.*;

/** Real episode choices shared by both playback engines; no fabricated queue or release. */
@androidx.media3.common.util.UnstableApi
public final class PlayerPanels {
  public static void episodes(Activity activity, Store store, JSONObject media, Runnable persist) {
    JSONObject title = media.optJSONObject("catalog");
    if (title == null || !Store.episode(media)) return;
    Toast.makeText(activity, "Loading episodes…", Toast.LENGTH_SHORT).show();
    Api.IO.execute(() -> {
      try {
        int season = media.optInt("season");
        List<JSONObject> episodes = Models.list(new Api(store).tmdb("tv/" + title.optInt("id") + "/season/" + season).optJSONArray("episodes"));
        activity.runOnUiThread(() -> {
          if (activity.isFinishing() || activity.isDestroyed()) return;
          String[] labels = new String[episodes.size()]; int current = 0;
          for (int index = 0; index < labels.length; index++) {
            JSONObject episode = episodes.get(index);
            labels[index] = episode.optInt("episode_number") + ". " + episode.optString("name");
            if (episode.optInt("episode_number") == media.optInt("episode")) current = index;
          }
          TvUi.sheet(activity, "Episodes", Models.title(title) + " · Season " + season, labels, current, index -> {
            persist.run();
            activity.startActivity(new Intent(activity, MainActivity.class).putExtra("nextTitle", title.toString())
                .putExtra("season", season).putExtra("episode", episodes.get(index).optInt("episode_number")));
            activity.finish();
          });
        });
      } catch (Exception e) {
        activity.runOnUiThread(() -> { if (!activity.isDestroyed()) Toast.makeText(activity, "Episodes could not be loaded. Please try again.", Toast.LENGTH_LONG).show(); });
      }
    });
  }
}
