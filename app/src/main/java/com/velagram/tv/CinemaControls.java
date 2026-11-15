package com.velagram.tv;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.graphics.drawable.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.util.*;

/** Shared custom remote-first controls for both Media3 and VideoLAN. */
public final class CinemaControls extends FrameLayout {
  public interface Playback {
    boolean playing();

    long position();

    long duration();

    void toggle();

    void seek(long time);
  }

  final Activity activity;
  final Playback playback;
  final Handler handler = new Handler(Looper.getMainLooper());
  final Store store;
  final LinearLayout bottom;
  final SeekBar seek;
  final TextView elapsed, remaining, state;
  final TvUi.Icon playIcon;
  final FrameLayout playButton;
  final TextView episodeButton;
  Runnable episodeClick, onTick;
  boolean visible = true, dragging = false, released = false;
  final Runnable hide = () -> hide();
  final Runnable tick =
      new Runnable() {
        public void run() {
          if (released) return;
          refresh();
          handler.postDelayed(this, 500);
        }
      };

  public CinemaControls(
      Activity a,
      Store s,
      String title,
      String subtitle,
      String engine,
      Playback backend,
      LinkedHashMap<String, Runnable> actions,
      Runnable back) {
    super(a);
    activity = a;
    store = s;
    playback = backend;
    setClipChildren(false);
    setFocusable(false);
    View topShade = new View(a);
    topShade.setBackground(
        new GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM, new int[] {0xed080a10, 0x00080a10}));
    addView(topShade, new FrameLayout.LayoutParams(-1, dp(145), Gravity.TOP));
    View bottomShade = new View(a);
    bottomShade.setBackground(
        new GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            new int[] {0x00080a10, 0xeb080a10, 0xfa080a10}));
    addView(bottomShade, new FrameLayout.LayoutParams(-1, dp(225), Gravity.BOTTOM));
    LinearLayout header = TvUi.row(a);
    header.setPadding(dp(28), dp(22), dp(30), 0);
    FrameLayout.LayoutParams hp = new FrameLayout.LayoutParams(-1, -2, Gravity.TOP);
    addView(header, hp);
    FrameLayout close = iconButton("Back", false, back);
    close.setContentDescription("Back to browsing");
    header.addView(close, new LinearLayout.LayoutParams(dp(42), dp(42)));
    LinearLayout identity = TvUi.col(a);
    identity.setPadding(dp(17), 0, 0, 0);
    TextView name = TvUi.text(a, title, 22, TvUi.TEXT);
    name.setTypeface(null, Typeface.BOLD);
    name.setMaxLines(1);
    name.setEllipsize(android.text.TextUtils.TruncateAt.END);
    identity.addView(name);
    TextView sub = TvUi.text(a, subtitle, 12, 0xffbbc1cd);
    sub.setPadding(0, dp(6), 0, 0);
    identity.addView(sub);
    header.addView(identity, new LinearLayout.LayoutParams(0, -2, 1));
    TextView badge = TvUi.text(a, engine.toUpperCase(Locale.ROOT), 10, 0xffbec5d2);
    badge.setLetterSpacing(.16f);
    header.addView(badge);
    bottom = TvUi.col(a);
    bottom.setPadding(dp(35), 0, dp(35), dp(24));
    FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
    addView(bottom, bp);
    state = TvUi.text(a, "", 12, 0xffc8ced9);
    state.setPadding(dp(2), 0, 0, dp(12));
    bottom.addView(state);
    seek = new SeekBar(a);
    seek.setMax(10000);
    seek.setProgressTintList(android.content.res.ColorStateList.valueOf(TvUi.accent(s)));
    seek.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(0xff555a66));
    seek.setThumbTintList(android.content.res.ColorStateList.valueOf(TvUi.TEXT));
    seek.setPadding(0, 0, 0, 0);
    seek.setKeyProgressIncrement(150);
    seek.setContentDescription("Playback timeline");
    bottom.addView(seek, new LinearLayout.LayoutParams(-1, dp(24)));
    seek.setOnSeekBarChangeListener(
        new SeekBar.OnSeekBarChangeListener() {
          public void onProgressChanged(SeekBar bar, int progress, boolean user) {
            if (user && playback.duration() > 0) {
              elapsed.setText(time(playback.duration() * progress / 10000));
              if (!dragging) playback.seek(playback.duration() * progress / 10000);
              schedule();
            }
          }

          public void onStartTrackingTouch(SeekBar bar) {
            dragging = true;
            handler.removeCallbacks(hide);
          }

          public void onStopTrackingTouch(SeekBar bar) {
            dragging = false;
            if (playback.duration() > 0)
              playback.seek(playback.duration() * bar.getProgress() / 10000);
            schedule();
          }
        });
    LinearLayout times = TvUi.row(a);
    elapsed = TvUi.text(a, "00:00", 11, 0xffb9c1cf);
    remaining = TvUi.text(a, "", 11, 0xffb9c1cf);
    times.addView(elapsed, new LinearLayout.LayoutParams(0, -2, 1));
    times.addView(remaining);
    LinearLayout.LayoutParams timesP = new LinearLayout.LayoutParams(-1, -2);
    timesP.setMargins(0, 0, 0, dp(17));
    bottom.addView(times, timesP);
    LinearLayout transport = TvUi.row(a);
    playButton =
        iconButton(
            "Pause",
            true,
            () -> {
              playback.toggle();
              refresh();
              schedule();
            });
    playIcon = (TvUi.Icon) playButton.getChildAt(0);
    playButton.setContentDescription("Play or pause");
    transport.addView(playButton, new LinearLayout.LayoutParams(dp(48), dp(48)));
    for (int direction : new int[] {-1, 1}) {
      TextView b =
          TvUi.button(
              a,
              direction < 0
                  ? "−" + store.get("seekSeconds", "10") + "s"
                  : "+" + store.get("seekSeconds", "10") + "s",
              false,
              () -> {
                playback.seek(
                    Math.max(
                        0,
                        playback.position()
                            + direction * Long.parseLong(store.get("seekSeconds", "10")) * 1000));
                schedule();
              });
      b.setSingleLine(true);
      b.setTextSize(12);
      b.setPadding(dp(8), dp(8), dp(8), dp(8));
      b.setContentDescription(direction < 0 ? "Rewind 10 seconds" : "Forward 10 seconds");
      LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(dp(66), dp(38));
      p.setMargins(dp(9), 0, 0, 0);
      transport.addView(b, p);
    }
    View gap = new View(a);
    transport.addView(gap, new LinearLayout.LayoutParams(0, 1, 1));
    for (Map.Entry<String, Runnable> e : actions.entrySet()) {
      TextView b =
          TvUi.button(
              a,
              e.getKey(),
              false,
              () -> {
                handler.removeCallbacks(hide);
                e.getValue().run();
              });
      b.setTextSize(12);
      b.setPadding(dp(14), dp(10), dp(14), dp(10));
      LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, dp(38));
      p.setMargins(dp(8), 0, 0, 0);
      transport.addView(b, p);
    }
    bottom.addView(transport);
    episodeButton = TvUi.button(a, "", true, () -> {
      if (episodeClick != null) episodeClick.run();
      schedule();
    });
    episodeButton.setTextSize(15);
    episodeButton.setVisibility(GONE);
    FrameLayout.LayoutParams ep = new FrameLayout.LayoutParams(-2, dp(44), Gravity.RIGHT | Gravity.BOTTOM);
    ep.setMargins(dp(28), 0, dp(35), dp(204));
    addView(episodeButton, ep);
    handler.post(tick);
    schedule();
  }

  int dp(float n) {
    return TvUi.dp(activity, n);
  }

  FrameLayout iconButton(String icon, boolean primary, Runnable action) {
    FrameLayout b = new FrameLayout(activity);
    b.setFocusable(true);
    b.setClickable(true);
    b.setBackground(TvUi.shape(primary ? TvUi.TEXT : 0x55343b48, 30, activity));
    TvUi.Icon image = new TvUi.Icon(activity, icon, primary ? TvUi.BG : TvUi.TEXT);
    FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(dp(23), dp(23), Gravity.CENTER);
    b.addView(image, p);
    b.setOnFocusChangeListener(
        (v, f) -> {
          GradientDrawable d = TvUi.shape(primary || f ? TvUi.TEXT : 0x55343b48, 30, activity);
          if (f) d.setStroke(dp(2), TvUi.accent(store));
          b.setBackground(d);
          image.tint(primary || f ? TvUi.BG : TvUi.TEXT);
          b.animate().scaleX(f ? 1.10f : 1).scaleY(f ? 1.10f : 1).setDuration(130).start();
        });
    b.setOnClickListener(v -> action.run());
    return b;
  }

  public static String time(long ms) {
    long seconds = Math.max(0, ms) / 1000;
    return seconds >= 3600
        ? String.format(Locale.US, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
        : String.format(Locale.US, "%02d:%02d", seconds / 60, seconds % 60);
  }

  public void status(String value) {
    state.setText(value);
  }

  public void refresh() {
    if (released) return;
    long duration = playback.duration(), position = playback.position();
    if (onTick != null) onTick.run();
    if (!dragging) {
      seek.setProgress(duration > 0 ? (int) (10000 * position / duration) : 0);
      elapsed.setText(time(position));
    }
    remaining.setText(duration > 0 ? "−" + time(duration - position) : "LIVE");
    seek.setEnabled(duration > 0);
    playIcon.setTag(playback.playing());
    playIcon.setVisibility(View.VISIBLE); // icon replaced only when state changes
    boolean playing = playback.playing();
    String desired = playing ? "Pause" : "Play";
    if (!desired.equals(playButton.getTag())) {
      playButton.removeAllViews();
      TvUi.Icon icon = new TvUi.Icon(activity, desired, TvUi.BG);
      FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(dp(23), dp(23), Gravity.CENTER);
      playButton.addView(icon, p);
      playButton.setTag(desired);
    }
  }

  public void episodeAction(String label, Runnable action) {
    episodeClick = action;
    if (label == null) {
      if (episodeButton.hasFocus()) playButton.requestFocus();
      episodeButton.setVisibility(GONE);
    } else {
      episodeButton.setText(label);
      episodeButton.setContentDescription(label);
      episodeButton.setVisibility(VISIBLE);
    }
  }

  public void schedule() {
    handler.removeCallbacks(hide);
    if (playback.playing()) handler.postDelayed(hide, 4500);
  }

  public void show() {
    visible = true;
    setVisibility(VISIBLE);
    animate().alpha(1).setDuration(180).start();
    playButton.requestFocus();
    schedule();
  }

  public void hide() {
    if (dragging) return;
    visible = false;
    setAlpha(0);
    setVisibility(INVISIBLE);
    clearFocus();
  }

  public boolean visible() {
    return visible;
  }

  public boolean key(KeyEvent event) {
    if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
    int key = event.getKeyCode();
    if (key == KeyEvent.KEYCODE_BACK) {
      if (visible) {
        hide();
        return true;
      }
      return false;
    }
    if (key == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
        || key == KeyEvent.KEYCODE_MEDIA_PLAY
        || key == KeyEvent.KEYCODE_MEDIA_PAUSE) {
      playback.toggle();
      show();
      return true;
    }
    if (key == KeyEvent.KEYCODE_MEDIA_REWIND || key == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD) {
      playback.seek(
          Math.max(
              0,
              playback.position()
                  + ((key == KeyEvent.KEYCODE_MEDIA_REWIND ? -1 : 1)
                      * Long.parseLong(store.get("seekSeconds", "10"))
                      * 1000)));
      show();
      return true;
    }
    if (!visible && (key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_ENTER)) {
      playback.toggle();
      show();
      return true;
    }
    if (!visible && (key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT)) {
      playback.seek(
          Math.max(
              0,
              playback.position()
                  + ((key == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1)
                      * Long.parseLong(store.get("seekSeconds", "10"))
                      * 1000)));
      show();
      return true;
    }
    if (key >= KeyEvent.KEYCODE_DPAD_UP && key <= KeyEvent.KEYCODE_DPAD_RIGHT) {
      if (!visible) {
        show();
        return true;
      }
      schedule();
    }
    return false;
  }

  public void release() {
    released = true;
    handler.removeCallbacksAndMessages(null);
  }
}
