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
    default float speed() { return 1f; }
    default String track(boolean subtitles) { return ""; }
  }

  final Activity activity;
  final Playback playback;
  final Handler handler = new Handler(Looper.getMainLooper());
  final Store store;
  final Map<String, TextView> actionLabels = new LinkedHashMap<>();
  final SeekBar seek;
  final TextView elapsed, remaining, state;
  final TvUi.Icon playIcon;
  final FrameLayout playButton;
  final TextView episodeButton;
  Runnable episodeClick, onTick;
  boolean visible = true, dragging = false, released = false;
  long lastInteraction, sleepDeadline;
  boolean controlsLocked;
  final Runnable sleep = () -> finishSleepTimer();
  final Runnable hide = this::hideWhenIdle;
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
    FrameLayout close = iconButton("Back", false, back);
    close.setContentDescription("Back to browsing");
    close.setBackgroundColor(Color.TRANSPARENT);
    state = TvUi.text(a, "", 11, 0xffd2d7e1); state.setGravity(Gravity.CENTER);
    seek = new SeekBar(a);
    seek.setMax(10000); seek.setKeyProgressIncrement(150);
    seek.setContentDescription("Playback timeline");
    seek.setPadding(dp(7), 0, dp(7), 0);
    GradientDrawable track = TvUi.shape(0x665b6170, 2, a);
    GradientDrawable fill = TvUi.shape(TvUi.accent(s), 2, a);
    ClipDrawable clip = new ClipDrawable(fill, Gravity.START, ClipDrawable.HORIZONTAL);
    LayerDrawable line = new LayerDrawable(new android.graphics.drawable.Drawable[] {track, clip});
    line.setId(0, android.R.id.background); line.setId(1, android.R.id.progress);
    line.setLayerHeight(0, dp(3)); line.setLayerHeight(1, dp(3));
    line.setLayerGravity(0, Gravity.CENTER_VERTICAL); line.setLayerGravity(1, Gravity.CENTER_VERTICAL);
    seek.setProgressDrawable(line);
    GradientDrawable thumb = TvUi.shape(TvUi.accent(s), 20, a); thumb.setSize(dp(13), dp(13)); thumb.setStroke(dp(2), TvUi.TEXT);
    seek.setThumb(thumb); seek.setThumbOffset(dp(6));
    elapsed = TvUi.text(a, "00:00", 11, 0xffdce0e9);
    remaining = TvUi.text(a, "", 11, 0xffdce0e9); remaining.setGravity(Gravity.END);
    seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
      public void onProgressChanged(SeekBar bar, int progress, boolean user) {
        if (user && playback.duration() > 0) {
          elapsed.setText(time(playback.duration() * progress / 10000));
          if (!dragging) seekTo(playback.duration() * progress / 10000);
          schedule();
        }
      }
      public void onStartTrackingTouch(SeekBar bar) { dragging = true; handler.removeCallbacks(hide); }
      public void onStopTrackingTouch(SeekBar bar) { dragging = false; if (playback.duration() > 0) seekTo(playback.duration() * bar.getProgress() / 10000); schedule(); }
    });
    playButton = iconButton("Pause", true, () -> { playback.toggle(); refresh(); schedule(); });
    playIcon = (TvUi.Icon) playButton.getChildAt(0);
    playButton.setContentDescription("Play or pause");
    FrameLayout rewind = iconButton("SeekBack:" + store.get("seekSeconds", "10"), false,
        () -> { seekTo(playback.position() - Long.parseLong(store.get("seekSeconds", "10")) * 1000); schedule(); });
    rewind.setContentDescription("Rewind " + store.get("seekSeconds", "10") + " seconds");
    FrameLayout forward = iconButton("SeekForward:" + store.get("seekSeconds", "10"), false,
        () -> { seekTo(playback.position() + Long.parseLong(store.get("seekSeconds", "10")) * 1000); schedule(); });
    forward.setContentDescription("Forward " + store.get("seekSeconds", "10") + " seconds");
    LinkedHashMap<String, View> pills = new LinkedHashMap<>();
    for (String label : new String[] {"Episodes", "Subtitles", "Audio", "Speed", "Options"}) {
      Runnable action = actions.get(label); if (action == null) continue;
      LinearLayout pill = actionPill(label, () -> { handler.removeCallbacks(hide); action.run(); });
      pills.put(label, pill);
    }
    View source = actions.containsKey("Sources") ? actionPill("Sources", () -> { handler.removeCallbacks(hide); actions.get("Sources").run(); }) : null;
    episodeButton = TvUi.button(a, "", true, () -> { if (episodeClick != null) episodeClick.run(); schedule(); });
    episodeButton.setTextSize(13); episodeButton.setVisibility(GONE);
    addView(PlayerOverlay.create(a, title, subtitle, close, source, playButton, rewind, forward,
        seek, elapsed, remaining, state, episodeButton, pills), new FrameLayout.LayoutParams(-1, -1));

    playButton.post(playButton::requestFocus);
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
    b.setBackground(TvUi.shape(primary ? 0xb3222736 : 0x8c242730, primary ? 18 : 28, activity));
    TvUi.Icon image = new TvUi.Icon(activity, icon, TvUi.TEXT);
    FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(dp(primary ? 29 : 24), dp(primary ? 29 : 24), Gravity.CENTER);
    b.addView(image, p);
    b.setOnFocusChangeListener((v, f) -> {
      GradientDrawable d = TvUi.shape(f ? 0xdf242d43 : primary ? 0xb3222736 : 0x8c242730, primary ? 18 : 28, activity);
      if (f) d.setStroke(dp(2), TvUi.accent(store));
      b.setBackground(d); image.tint(TvUi.TEXT);
      b.animate().scaleX(f ? 1.04f : 1).scaleY(f ? 1.04f : 1).setDuration(175).start();
    });
    b.setOnClickListener(v -> action.run());
    return b;
  }

  LinearLayout actionPill(String label, Runnable action) {
    LinearLayout pill = TvUi.row(activity);
    pill.setGravity(Gravity.CENTER); pill.setFocusable(true); pill.setClickable(true);
    pill.setDescendantFocusability(FOCUS_BLOCK_DESCENDANTS); pill.setContentDescription(label.equals("Options") ? "More playback options" : label);
    pill.setPadding(dp(12), dp(4), dp(12), dp(4));
    pill.setBackground(TvUi.shape(0xc51a1c24, 16, activity));
    TvUi.Icon icon = new TvUi.Icon(activity, label.equals("Options") ? "More" : label, TvUi.TEXT);
    pill.addView(icon, new LinearLayout.LayoutParams(dp(19), dp(19)));
    if (!label.equals("Options")) {
      TextView name = TvUi.text(activity, label, 12, TvUi.TEXT); name.setMaxLines(2); name.setEllipsize(android.text.TextUtils.TruncateAt.END);
      name.setPadding(dp(9), 0, 0, 0); pill.addView(name, new LinearLayout.LayoutParams(-2, -2)); actionLabels.put(label, name);
    }
    pill.setOnFocusChangeListener((v, focused) -> {
      GradientDrawable background = TvUi.shape(focused ? 0xee283247 : 0xc51a1c24, 16, activity);
      if (focused) background.setStroke(dp(1.5f), TvUi.accent(store)); pill.setBackground(background); schedule();
    });
    pill.setOnClickListener(v -> action.run());
    return pill;
  }

  void actionDetail(String action, String detail) {
    TextView label = actionLabels.get(action); if (label == null) return;
    String value = action + (detail == null || detail.isBlank() ? "" : "\n" + detail);
    if (value.contentEquals(label.getText())) return;
    android.text.SpannableString styled = new android.text.SpannableString(value);
    if (value.contains("\n")) {
      int start = value.indexOf('\n') + 1;
      styled.setSpan(new android.text.style.RelativeSizeSpan(.80f), start, value.length(), 0);
      styled.setSpan(new android.text.style.ForegroundColorSpan(TvUi.MUTED), start, value.length(), 0);
    }
    label.setText(styled);
  }

  public static String time(long ms) {
    long seconds = Math.max(0, ms) / 1000;
    return seconds >= 3600
        ? String.format(Locale.US, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
        : String.format(Locale.US, "%02d:%02d", seconds / 60, seconds % 60);
  }

  void hideWhenIdle() { if (activity.hasWindowFocus() && !dragging && playback.playing()) hide(); }

  void seekTo(long target) {
    long duration = playback.duration();
    playback.seek(Math.max(0, duration > 0 ? Math.min(duration, target) : target));
  }

  @Override public void onWindowFocusChanged(boolean focused) {
    super.onWindowFocusChanged(focused);
    if (!focused) handler.removeCallbacks(hide);
    else if (visible) schedule();
  }

  public void sleepTimer() {
    int[] minutes = {0, 15, 30, 45, 60, 90, 120};
    String[] labels = {"Off", "15 minutes", "30 minutes", "45 minutes", "1 hour", "90 minutes", "2 hours"};
    TvUi.sheet(activity, "Sleep timer", sleepDeadline > 0
        ? "Pausing in " + Math.max(1, (sleepDeadline - android.os.SystemClock.elapsedRealtime()) / 60000) + " minutes"
        : "Pause playback after a set time.", labels, sleepDeadline == 0 ? 0 : -1,
        index -> setSleepTimer(minutes[index] * 60000L));
  }

  void setSleepTimer(long duration) {
    handler.removeCallbacks(sleep);
    sleepDeadline = duration > 0 ? android.os.SystemClock.elapsedRealtime() + duration : 0;
    if (sleepDeadline > 0) handler.postDelayed(sleep, duration);
    status(duration > 0 ? "Sleep timer set" : "Sleep timer off");
    show();
  }

  void finishSleepTimer() {
    sleepDeadline = 0;
    if (playback.playing()) playback.toggle();
    controlsLocked = false;
    status("Sleep timer finished");
    show();
  }

  public void lockControls() {
    controlsLocked = true;
    hide();
    Toast.makeText(activity, "Controls locked · press Back to unlock", Toast.LENGTH_LONG).show();
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
    playIcon.symbol(playback.playing() ? "Pause" : "Play");
    actionDetail("Speed", String.format(Locale.US, "%.2f×", playback.speed()));
    actionDetail("Audio", playback.track(false)); actionDetail("Subtitles", playback.track(true));
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
    lastInteraction = android.os.SystemClock.uptimeMillis();
    if (playback.playing() && !dragging && activity.hasWindowFocus()) handler.postDelayed(hide, 4500);
  }

  public void show() {
    boolean reveal = !visible;
    visible = true;
    setVisibility(VISIBLE);
    animate().alpha(1).setDuration(180).start();
    if (reveal || activity.getCurrentFocus() == null) playButton.post(playButton::requestFocus);
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
    if (controlsLocked) {
      if (key == KeyEvent.KEYCODE_BACK) { controlsLocked = false; show(); }
      return true;
    }
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
      if ((key == KeyEvent.KEYCODE_MEDIA_PLAY && !playback.playing())
          || (key == KeyEvent.KEYCODE_MEDIA_PAUSE && playback.playing())
          || key == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) playback.toggle();
      show();
      return true;
    }
    if (key == KeyEvent.KEYCODE_MEDIA_REWIND || key == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD) {
      seekTo(
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
      seekTo(
          Math.max(
              0,
              playback.position()
                  + ((key == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1)
                      * Long.parseLong(store.get("seekSeconds", "10"))
                      * 1000)));
      show();
      return true;
    }
    if (visible) schedule();
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
