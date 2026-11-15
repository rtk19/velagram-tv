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
public class VlcActivity extends androidx.activity.ComponentActivity {
  LibVLC lib;
  MediaPlayer player;
  VLCVideoLayout video;
  Store store;
  boolean temporaryLease;
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
      if (media.optString("url").startsWith("td:")) { TemporaryMedia.acquire(); temporaryLease = true; }
      key = media.optString("progressKey", media.optString("id", media.optString("url")));
      FrameLayout root = new FrameLayout(this);
      root.setBackgroundColor(0xff000000);
      video = new VLCVideoLayout(this);
      root.addView(video, new FrameLayout.LayoutParams(-1, -1));
      LinkedHashMap<String, Runnable> actions = new LinkedHashMap<>();
      if (media.optJSONObject("catalog") != null) actions.put("Sources", this::otherSources);
      if (Store.episode(media)) actions.put("Episodes", () -> PlayerPanels.episodes(this, store, media, this::persist));
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
                public float speed() { return player == null ? 1f : player.getRate(); }
                public String track(boolean subtitles) {
                  if (player == null) return "";
                  if (subtitles && !PlaybackPrefs.subtitles(store, media)) return "Off";
                  int current = subtitles ? player.getSpuTrack() : player.getAudioTrack();
                  MediaPlayer.TrackDescription[] tracks = subtitles ? player.getSpuTracks() : player.getAudioTracks();
                  if (tracks != null) for (MediaPlayer.TrackDescription track : tracks) if (track.id == current) {
                    String language = TrackMemory.language("", track.name);
                    return language.isBlank() ? track.name : Locale.forLanguageTag(language).getDisplayLanguage();
                  }
                  return "";
                }

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
                restoreTrackChoices();
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
        Arrays.asList(values).indexOf(Float.toString(player.getRate())),
        i -> {
          player.setRate(Float.parseFloat(values[i]));
          store.put("speed." + PlaybackPrefs.titleKey(media), values[i]);
          controls.status(values[i] + "× playback speed");
          controls.show();
        });
  }

  void restoreTrackChoices() {
    for (boolean subtitles : new boolean[] {false, true}) {
      if (subtitles && !PlaybackPrefs.subtitles(store, media)) continue;
      JSONObject intent = TrackMemory.read(store, media, subtitles);
      MediaPlayer.TrackDescription[] tracks = subtitles ? player.getSpuTracks() : player.getAudioTracks();
      if (tracks == null || intent == null) continue;
      int best = -1; MediaPlayer.TrackDescription selected = null;
      for (MediaPlayer.TrackDescription track : tracks) {
        int score = TrackMemory.score(intent, "", track.name, 0);
        if (score > best) { best = score; selected = track; }
      }
      if (selected != null) {
        if (subtitles && player.getSpuTrack() != selected.id) player.setSpuTrack(selected.id);
        if (!subtitles && player.getAudioTrack() != selected.id) player.setAudioTrack(selected.id);
      }
    }
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
    int currentTrack = subs ? player.getSpuTrack() : player.getAudioTrack();
    int selectedTrack = 0;
    for (int index = 0; index < tracks.length; index++) if (tracks[index].id == currentTrack) selectedTrack = index;
    TvUi.sheet(
        this,
        subs ? "Subtitle tracks" : "Audio tracks",
        "",
        Arrays.stream(tracks).map(t -> t.name).toArray(String[]::new),
        selectedTrack,
        i -> {
          if (tracks[i].id >= 0) TrackMemory.save(store, media, subs, "", tracks[i].name, 0);
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
    LinkedHashMap<String, Runnable> options = new LinkedHashMap<>();
    options.put("Fit picture", () -> { store.put("zoom." + PlaybackPrefs.titleKey(media), "0"); applyPicture(); });
    options.put("Fill screen", () -> { store.put("zoom." + PlaybackPrefs.titleKey(media), "4"); applyPicture(); });
    options.put("Audio timing", () -> TvUi.sheet(this, "Audio timing", "", new String[] {"Earlier by 0.1 seconds", "Reset", "Later by 0.1 seconds"}, -1,
        index -> player.setAudioDelay(index == 1 ? 0 : player.getAudioDelay() + (index == 0 ? -100000 : 100000))));
    if (Store.episode(media)) options.put("Next episode", this::nextEpisode);
    if (media.optJSONObject("catalog") != null) options.put("Choose another source", this::otherSources);
    options.put("Use standard player", () -> {
      persist(); startActivity(new Intent(this, PlayerActivity.class).putExtra("media", media.toString())); finish();
    });
    options.put("Sleep timer", controls::sleepTimer); options.put("Lock controls", controls::lockControls);
    TvUi.sheet(this, "Playback options", "", options.keySet().toArray(new String[0]), -1,
        index -> new ArrayList<>(options.values()).get(index).run());
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

  // Intercept remote keys before focusable children, as the Activity contract requires.
  // The public androidx.activity superclass inherits this method from a restricted core shim:
  // https://android.googlesource.com/platform/frameworks/support/+/f2e05c341382db64d127118a13451dcaa554b702/core/core/src/main/java/androidx/core/app/ComponentActivity.java
  @android.annotation.SuppressLint("RestrictedApi")
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
    if (temporaryLease) { TemporaryMedia.release(); temporaryLease = false; }
    super.onDestroy();
  }
}
