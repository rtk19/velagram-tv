package com.velagram.tv;

import static org.junit.Assert.*;
import android.app.*;
import android.content.*;
import android.net.Uri;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.*;
import java.util.*;
import org.json.*;
import org.junit.*;

/** Actual Media3/VLC playback with controlled segment fixtures, not a mocked video backend. */
@androidx.media3.common.util.UnstableApi
public class PlayerParityTest {
  Instrumentation instrumentation;
  Store store;
  PreferenceSnapshot snapshot;
  String previous;
  File movie;
  Activity activity;
  @Before public void setup() throws Exception {
    instrumentation = InstrumentationRegistry.getInstrumentation();
    Context target = instrumentation.getTargetContext(); store = new Store(target);
    snapshot = new PreferenceSnapshot(store);
    previous = store.profile(); store.put("profile", "Player parity " + UUID.randomUUID());
    movie = new File(target.getFilesDir(), "parity-playback.mp4");
    try (InputStream in = instrumentation.getContext().getAssets().open("playback.mp4"); FileOutputStream out = new FileOutputStream(movie)) {
      byte[] buffer = new byte[16384]; int count; while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
    }
  }
  @After public void cleanup() {
    if (activity != null) instrumentation.runOnMainSync(() -> activity.finish());
    long deadline = android.os.SystemClock.elapsedRealtime() + 10000;
    while (activity != null && !activity.isDestroyed() && android.os.SystemClock.elapsedRealtime() < deadline)
      try { Thread.sleep(100); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
    instrumentation.waitForIdleSync();
    String prefix = "profile." + store.profile() + ".";
    android.content.SharedPreferences.Editor editor = store.prefs.edit();
    for (String key : store.prefs.getAll().keySet()) if (key.startsWith(prefix)) editor.remove(key);
    editor.apply(); snapshot.restore();
    if (movie != null) movie.delete();
  }
  @Test public void media3SkipActionsSeekActualPlayback() throws Exception { verify(PlayerActivity.class, "media3"); }
  @Test public void vlcSkipActionsSeekActualPlayback() throws Exception { verify(VlcActivity.class, "vlc"); }

  long read(java.util.function.LongSupplier value) {
    long[] result = new long[1]; instrumentation.runOnMainSync(() -> result[0] = value.getAsLong()); return result[0];
  }

  void verify(Class<? extends Activity> type, String engine) throws Exception {
    JSONObject media = Models.json("url", Uri.fromFile(movie).toString(), "name", "Big Buck Bunny · playback test", "id", "parity-movie");
    activity = instrumentation.startActivitySync(new Intent(instrumentation.getTargetContext(), type)
        .putExtra("media", media.toString()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    CinemaControls controls = type == PlayerActivity.class ? ((PlayerActivity) activity).controls : ((VlcActivity) activity).controls;
    PlaybackJourney journey = type == PlayerActivity.class ? ((PlayerActivity) activity).journey : ((VlcActivity) activity).journey;
    long deadline = android.os.SystemClock.elapsedRealtime() + 20000;
    while ((read(controls.playback::duration) <= 10000 || read(() -> controls.playback.playing() ? 1 : 0) == 0)
        && android.os.SystemClock.elapsedRealtime() < deadline) Thread.sleep(100);
    assertTrue("Actual video did not become ready", read(controls.playback::duration) > 10000);
    Thread.sleep(1200);
    for (String kind : new String[] {"intro", "recap", "preview", "outro"}) {
      long end = kind.equals("outro") ? 25000 : 8000;
      List<SkipSegments.Segment> segments = new ArrayList<>(SkipSegments.parseIntro(
          new JSONObject("{\"" + kind + "\":{\"start_ms\":0,\"end_ms\":" + end + "}}"), read(controls.playback::duration)).values());
      instrumentation.runOnMainSync(() -> { controls.playback.seek(1000); });
      Thread.sleep(700);
      instrumentation.runOnMainSync(() -> {
        journey.segments = segments; journey.requestedSegments = true; controls.show(); controls.refresh();
        assertEquals(segments.get(0).label(false), controls.episodeButton.getText().toString());
        assertEquals(android.view.View.VISIBLE, controls.episodeButton.getVisibility());
        controls.episodeButton.requestFocus();
      });
      instrumentation.waitForIdleSync();
      Thread.sleep(250); // Let the focused button be drawn before taking the raw screenshot.
      if (kind.equals("intro")) {
        File evidence = new File(instrumentation.getTargetContext().getExternalFilesDir(null), "parity-evidence"); evidence.mkdirs();
        try (FileOutputStream out = new FileOutputStream(new File(evidence, engine + "-skip-intro.png"))) {
          instrumentation.getUiAutomation().takeScreenshot().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out);
        }
      }
      // Exercise the same DPAD click path as a TV remote.
      instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_DPAD_CENTER);
      Thread.sleep(500);
      long seekDeadline = android.os.SystemClock.elapsedRealtime() + 3000;
      while (read(controls.playback::position) < end - 400 && android.os.SystemClock.elapsedRealtime() < seekDeadline) Thread.sleep(100);
      long actual = read(controls.playback::position);
      assertTrue(engine + " " + kind + " action did not seek: " + actual + " expected >= " + (end - 400), actual >= end - 400);
    }
  }
}
