package com.velagram.tv;

import android.app.*;
import android.content.*;
import android.net.Uri;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.util.*;
import org.json.*;
import org.videolan.libvlc.*;
import org.videolan.libvlc.util.VLCVideoLayout;

@androidx.media3.common.util.UnstableApi
public class VlcActivity extends Activity {
  LibVLC lib;
  MediaPlayer player;
  VLCVideoLayout video;
  Store store;
  JSONObject media;
  String key;
  Uri currentSubtitle;
  String currentSubtitleKey = "local";
  NextPreloader preloader;
  PlaybackJourney journey;
  boolean transitioning;
  TelegramRelay relay;
  HeaderRelay headerRelay;
  CinemaControls controls;
  Handler h = new Handler(Looper.getMainLooper());
  boolean destroyed;
  boolean loadedProviderSubtitle;
  final Runnable save =
      new Runnable() {
        public void run() {
          persist();
          if (!destroyed) h.postDelayed(this, 1000);
        }
      };

  @Override
  public void onCreate(Bundle b) {
    super.onCreate(b);
    getWindow().setFlags(1024 | 128, 1024 | 128);
    store = new Store(this);
    preloader = new NextPreloader(this, store);
    try {
      media = new JSONObject(getIntent().getStringExtra("media"));
      key = media.optString("progressKey", media.optString("id", media.optString("url")));
      FrameLayout root = new FrameLayout(this);
      root.setBackgroundColor(0xff000000);
      video = new VLCVideoLayout(this);
      root.addView(video, new FrameLayout.LayoutParams(-1, -1));
      LinkedHashMap<String, Runnable> actions = new LinkedHashMap<>();
      actions.put("Speed", this::speed);
      actions.put("Audio", () -> tracks(false));
      actions.put("Subtitles", this::subtitles);
      actions.put("Options", this::options);
      controls =
          new CinemaControls(
              this,
              store,
              Models.title(media),
              PlaybackPrefs.context(media),
              "VLC",
              new CinemaControls.Playback() {
                public boolean playing() {
                  return player != null && player.isPlaying();
                }

                public long position() {
                  return player == null ? 0 : player.getTime();
                }

                public long duration() {
                  return player == null ? 0 : player.getLength();
                }

                public void toggle() {
                  if (player != null) {
                    if (player.isPlaying()) player.pause();
                    else player.play();
                  }
                }

                public void seek(long t) {
                  if (player != null) player.setTime(t);
                }
              },
              actions,
              this::finish);
      root.addView(controls, new FrameLayout.LayoutParams(-1, -1));
      video.setOnClickListener(
          v -> {
            if (controls.visible()) controls.hide();
            else controls.show();
          });
      setContentView(root);
      journey = new PlaybackJourney(this, store, media, controls, this::nextEpisode);
      lib =
          new LibVLC(
              getApplicationContext(),
              new ArrayList<>(
                  Arrays.asList(
                      "--network-caching=1500", "--audio-time-stretch", "--sub-autodetect-file")));
      player = new MediaPlayer(lib);
      player.attachViews(video, null, false, false);
      String url = media.getString("url");
      if (url.startsWith("td:")) {
        relay = new TelegramRelay(Telegram.get(this));
        url = relay.url(Integer.parseInt(Uri.parse(url).getHost()));
      } else if (url.startsWith("http") && media.optJSONObject("headers") != null && media.optJSONObject("headers").length() > 0) {
        headerRelay = new HeaderRelay(url, Api.headers(media.optJSONObject("headers")));
        url = headerRelay.url();
      }
      Media m = new Media(lib, Uri.parse(url));
      m.setHWDecoderEnabled(true, false);
      JSONObject headers = media.optJSONObject("headers");
      if (headers != null) {
        if (headers.has("User-Agent"))
          m.addOption(":http-user-agent=" + headers.getString("User-Agent"));
        if (headers.has("Referer")) m.addOption(":http-referrer=" + headers.getString("Referer"));
      }
      m.addOption(":start-time=" + (store.position(key) / 1000.0));
      boolean anime = false;
      JSONObject catalog = media.optJSONObject("catalog");
      if (catalog != null) for (JSONObject genre : Models.list(catalog.optJSONArray("genres")))
        if (genre.optInt("id") == 16) anime = true;
      m.addOption(":audio-language=" + store.get(anime ? "animeAudio" : "audioLanguage", "en")
          + "," + (anime ? store.get("animeBackup", "ja") : "en"));
      m.addOption(":sub-language=" + store.get("subtitleLanguage", "en"));
      if (!PlaybackPrefs.subtitles(store, media)) m.addOption(":no-spu");
      player.setMedia(m);
      m.release();
      player.setEventListener(
          event -> {
            if (destroyed) return;
            if (event.type == MediaPlayer.Event.EncounteredError)
              runOnUiThread(
                  () ->
                      failure("This stream could not be decoded or reached. Try another release."));
            if (event.type == MediaPlayer.Event.Playing) {
              player.setRate(PlaybackPrefs.speed(store, media));
              player.setSpuDelay(Long.parseLong(store.get("subtitleDelay." + key, "0")));
              runOnUiThread(() -> {
                controls.status("");
                applyPicture();
                if (!loadedProviderSubtitle && PlaybackPrefs.subtitles(store, media)) {
                  loadedProviderSubtitle = true;
                  List<JSONObject> tracks = Models.list(media.optJSONArray("subtitles"));
                  JSONObject selected = null;
                  for (JSONObject track : tracks)
                    if (track.optString("language").equals(store.get("subtitleLanguage", "en"))) { selected = track; break; }
                  if (selected == null && !tracks.isEmpty()) selected = tracks.get(0);
                  if (selected != null) {
                    JSONObject track = selected;
                    Api.IO.execute(() -> {
                      try {
                        java.io.File file = Subtitles.download(this, track);
                        runOnUiThread(() -> { if (!destroyed) { currentSubtitleKey = SubtitleSync.key(track); attach(Uri.fromFile(file)); } });
                      } catch (Exception ignored) { }
                    });
                  }
                }
              });
            }
            if (event.type == MediaPlayer.Event.Buffering)
              runOnUiThread(() -> controls.status(event.getBuffering() < 100 ? "Buffering…" : ""));
            if (event.type == MediaPlayer.Event.EndReached) runOnUiThread(() -> {
              if (journey != null) journey.finish();
              if (store.get("autoNext", "true").equals("true")) nextEpisode();
            });
          });
      player.play();
      h.postDelayed(save, 5000);
      controls.show();
    } catch (Exception e) {
      failure(e.getMessage());
    }
  }

  void speed() {
    String[] values = {"0.5", "0.75", "1.0", "1.25", "1.5", "1.75", "2.0"};
    TvUi.sheet(
        this,
        "Playback speed",
        "Remembered for this title.",
        Arrays.stream(values).map(v -> v + "×").toArray(String[]::new),
        -1,
        i -> {
          player.setRate(Float.parseFloat(values[i]));
          store.put("speed." + PlaybackPrefs.titleKey(media), values[i]);
          controls.status(values[i] + "× playback speed");
          controls.show();
        });
  }

  void tracks(boolean subs) {
    MediaPlayer.TrackDescription[] tracks = subs ? player.getSpuTracks() : player.getAudioTracks();
    if (tracks == null || tracks.length == 0) {
      TvUi.sheet(
          this,
          subs ? "Subtitle tracks" : "Audio tracks",
          "No tracks are available for this release.",
          new String[] {"Back"},
          -1,
          i -> {});
      return;
    }
    TvUi.sheet(
        this,
        subs ? "Subtitle tracks" : "Audio tracks",
        "",
        Arrays.stream(tracks).map(t -> t.name).toArray(String[]::new),
        -1,
        i -> {
          if (subs) { player.setSpuTrack(tracks[i].id); PlaybackPrefs.subtitles(store, media, tracks[i].id >= 0); }
          else player.setAudioTrack(tracks[i].id);
        });
  }

  void subtitles() {
    TvUi.sheet(
        this,
        "Subtitles",
        "Tracks, online matches and timing.",
        new String[] {
          "Embedded subtitle tracks",
          "Find online subtitles",
          "Import subtitle file",
          "Timing adjustment",
          "Subtitle Studio"
        },
        -1,
        i -> {
          switch (i) {
            case 0:
              tracks(true);
              break;
            case 1:
              online();
              break;
            case 2:
              try {
                startActivityForResult(
                    new Intent(Intent.ACTION_OPEN_DOCUMENT)
                        .addCategory(Intent.CATEGORY_OPENABLE)
                        .setType("*/*"),
                    7);
              } catch (Exception e) {
                failure(e.getMessage());
              }
              break;
            case 3:
              timing();
              break;
            case 4:
              Intent studio =
                  new Intent(this, SubtitleStudioActivity.class)
                      .putExtra("mediaKey", key)
                      .putExtra("subtitleKey", currentSubtitleKey);
              if (currentSubtitle != null)
                studio.putExtra("subtitleURI", currentSubtitle.toString());
              startActivityForResult(studio, 8);
          }
        });
  }

  void timing() {
    TvUi.sheet(
        this,
        "Subtitle timing",
        String.format(Locale.US, "Current offset: %+.1f seconds", player.getSpuDelay() / 1000000.0),
        new String[] {"Earlier by 0.5 seconds", "Reset timing", "Later by 0.5 seconds"},
        -1,
        i -> {
          long delay = i == 1 ? 0 : player.getSpuDelay() + (i == 0 ? -500000 : 500000);
          player.setSpuDelay(delay);
          store.put("subtitleDelay." + key, Long.toString(delay));
          controls.status(String.format(Locale.US, "Subtitles %+.1f s", delay / 1000000.0));
          controls.show();
        });
  }

  void online() {
    JSONObject title = media.optJSONObject("catalog");
    if (title == null) {
      failure("Open a catalog title to find matching online subtitles.");
      return;
    }
    controls.status("Searching subtitles…");
    Api.IO.execute(
        () -> {
          try {
            List<JSONObject> results =
                new Subtitles(new Api(store), store)
                    .search(title, media.optInt("season"), media.optInt("episode"));
            runOnUiThread(
                () -> {
                  if (isFinishing()) return;
                  controls.status("");
                  TvUi.sheet(
                      this,
                      "Online subtitles",
                      results.isEmpty()
                          ? "No matches for this title and language."
                          : "Choose a release.",
                      results.stream().map(o -> o.optString("name")).toArray(String[]::new),
                      -1,
                      i ->
                          Api.IO.execute(
                              () -> {
                                try {
                                  currentSubtitleKey = SubtitleSync.key(results.get(i));
                                  java.io.File file = Subtitles.download(this, results.get(i));
                                  runOnUiThread(() -> attach(Uri.fromFile(file)));
                                } catch (Exception e) {
                                  runOnUiThread(() -> failure(e.getMessage()));
                                }
                              }));
                });
          } catch (Exception e) {
            runOnUiThread(() -> failure(e.getMessage()));
          }
        });
  }

  void attach(Uri uri) {
    currentSubtitle = uri;
    PlaybackPrefs.subtitles(store, media, true);
    uri = SubtitleSync.latest(store, key, currentSubtitleKey, uri);
    player.addSlave(Media.Slave.Type.Subtitle, uri, true);
    controls.status("Subtitle loaded");
    controls.show();
  }

  @Override
  protected void onActivityResult(int r, int result, Intent data) {
    super.onActivityResult(r, result, data);
    if ((r == 7 || r == 8) && result == RESULT_OK && data != null && data.getData() != null) {
      if (r == 7)
        getContentResolver()
            .takePersistableUriPermission(data.getData(), Intent.FLAG_GRANT_READ_URI_PERMISSION);
      currentSubtitleKey = r == 7 ? data.getData().toString() : data.getStringExtra("subtitleKey");
      if (currentSubtitleKey == null) currentSubtitleKey = "local";
      attach(data.getData());
    }
  }

  void options() {
    TvUi.sheet(
        this,
        "Playback options",
        "",
        new String[] {
          "Fit picture",
          "Fill screen",
          "Audio timing",
          "Next episode",
          "Choose another source",
          "Use standard player"
        },
        -1,
        i -> {
          if (i == 0 || i == 1) { store.put("zoom." + PlaybackPrefs.titleKey(media), i == 0 ? "0" : "4"); applyPicture(); }
          if (i == 2)
            TvUi.sheet(
                this,
                "Audio timing",
                "",
                new String[] {"Earlier by 0.1 seconds", "Reset", "Later by 0.1 seconds"},
                -1,
                j ->
                    player.setAudioDelay(
                        j == 1 ? 0 : player.getAudioDelay() + (j == 0 ? -100000 : 100000)));
          if (i == 3) nextEpisode();
          if (i == 4) otherSources();
          if (i == 5) {
            persist();
            startActivity(
                new Intent(this, PlayerActivity.class).putExtra("media", media.toString()));
            finish();
          }
        });
  }

  void applyPicture() {
    int mode;
    try { mode = Integer.parseInt(store.get("zoom." + PlaybackPrefs.titleKey(media), "0")); }
    catch (Exception ignored) { mode = 0; }
    player.setVideoScale(mode == 4 ? MediaPlayer.ScaleType.SURFACE_FIT_SCREEN
        : mode == 3 ? MediaPlayer.ScaleType.SURFACE_FILL : MediaPlayer.ScaleType.SURFACE_BEST_FIT);
  }

  void otherSources() {
    JSONObject title = media.optJSONObject("catalog");
    if (title == null) return;
    persist();
    startActivity(
        new Intent(this, MainActivity.class)
            .putExtra("nextTitle", title.toString())
            .putExtra("season", media.optInt("season"))
            .putExtra("episode", media.optInt("episode")));
    finish();
  }

  void nextEpisode() {
    if (!Store.episode(media) || transitioning) return;
    transitioning = true;
    if (journey != null) journey.finish();
    JSONObject ready = preloader == null ? null : preloader.take();
    if (ready != null) {
      persist();
      startActivity(new Intent(this, VlcActivity.class).putExtra("media", ready.toString()));
      finish();
      return;
    }
    controls.show();
    controls.status("Finding the next episode…");
    EpisodeNavigator.nextUnwatched(
        new Api(store),
        store,
        media,
        (next, error) -> {
          if (isFinishing()) return;
          if (error != null) {
            transitioning = false;
            controls.status(error);
            return;
          }
          new NextPlayback(this, store)
              .find(
                  next,
                  media.optString("provider"),
                  () -> !isFinishing(),
                  result -> {
                    if (result == null) {
                      startActivity(
                          new Intent(this, MainActivity.class)
                              .putExtra("nextTitle", next.optJSONObject("catalog").toString())
                              .putExtra("season", next.optInt("season"))
                              .putExtra("episode", next.optInt("episode")));
                    } else {
                      persist();
                      startActivity(
                          new Intent(this, VlcActivity.class).putExtra("media", result.toString()));
                    }
                    finish();
                  });
        });
  }

  void failure(String message) {
    if (isFinishing()) return;
    TvUi.sheet(
        this,
        "Playback unavailable",
        message == null ? "Try another release." : message,
        new String[] {"Choose another source", "Close player"},
        -1,
        i -> {
          if (i == 0 && media != null) otherSources();
          else finish();
        });
  }

  void persist() {
    if (player != null && media != null) {
      if (journey != null) journey.persist(player.getTime(), player.getLength());
      else store.progress(key, player.getTime(), player.getLength(), media);
      if (preloader != null) preloader.consider(media, player.getTime(), player.getLength());
    }
  }

  @Override
  public boolean dispatchKeyEvent(KeyEvent e) {
    if (controls != null && controls.key(e)) return true;
    if (e.getAction() == KeyEvent.ACTION_DOWN && e.getKeyCode() == KeyEvent.KEYCODE_MENU) {
      options();
      return true;
    }
    return super.dispatchKeyEvent(e);
  }

  @Override
  protected void onPause() {
    persist();
    if (player != null) player.pause();
    super.onPause();
  }

  @Override
  protected void onResume() {
    super.onResume();
  }

  @Override
  protected void onDestroy() {
    destroyed = true;
    h.removeCallbacksAndMessages(null);
    if (controls != null) controls.release();
    persist();
    if (journey != null) journey.release();
    if (player != null) {
      player.stop();
      player.detachViews();
      player.release();
      player = null;
    }
    if (lib != null) lib.release();
    if (relay != null) relay.close();
    if (headerRelay != null) headerRelay.close();
    super.onDestroy();
  }
}
