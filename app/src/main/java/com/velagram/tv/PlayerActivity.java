package com.velagram.tv;

import android.app.*;
import android.content.*;
import android.net.Uri;
import android.os.*;
import android.view.*;
import android.widget.*;
import androidx.media3.common.*;
import androidx.media3.datasource.*;
import androidx.media3.exoplayer.*;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.session.MediaSession;
import androidx.media3.ui.*;
import java.util.*;
import org.json.*;

@androidx.media3.common.util.UnstableApi
public class PlayerActivity extends Activity {
  ExoPlayer player;
  PlayerView view;
  MediaSession session;
  Store store;
  JSONObject media;
  String key;
  NextPreloader preloader;
  PlaybackJourney journey;
  boolean transitioning;
  Uri currentSubtitle;
  String currentSubtitleKey = "local";
  Handler handler = new Handler(Looper.getMainLooper());
  CinemaControls controls;
  FrameLayout playerRoot;
  boolean stopped = false;
  List<JSONObject> online = new ArrayList<>();
  final Runnable save =
      new Runnable() {
        public void run() {
          persist();
          if (!stopped) handler.postDelayed(this, 1000);
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
      playerRoot = new FrameLayout(this);
      playerRoot.setBackgroundColor(0xff000000);
      view = new PlayerView(this);
      view.setUseController(false);
      view.setFocusable(false);
      playerRoot.addView(view, new FrameLayout.LayoutParams(-1, -1));
      LinkedHashMap<String, Runnable> actions = new LinkedHashMap<>();
      actions.put("Speed", this::speed);
      actions.put("Audio", () -> tracks(C.TRACK_TYPE_AUDIO));
      actions.put("Subtitles", this::subtitles);
      actions.put("Options", this::options);
      controls =
          new CinemaControls(
              this,
              store,
              Models.title(media),
              PlaybackPrefs.context(media),
              "Velagram",
              new CinemaControls.Playback() {
                public boolean playing() {
                  return player != null && player.isPlaying();
                }

                public long position() {
                  return player == null ? 0 : player.getCurrentPosition();
                }

                public long duration() {
                  return player == null ? 0 : player.getDuration();
                }

                public void toggle() {
                  if (player != null) {
                    if (player.isPlaying()) player.pause();
                    else player.play();
                  }
                }

                public void seek(long t) {
                  if (player != null) player.seekTo(t);
                }
              },
              actions,
              this::finish);
      playerRoot.addView(controls, new FrameLayout.LayoutParams(-1, -1));
      view.setOnClickListener(
          v -> {
            if (controls.visible()) controls.hide();
            else controls.show();
          });
      setContentView(playerRoot);
      journey = new PlaybackJourney(this, store, media, controls, this::nextEpisode);
      buildPlayer(null);
      controls.show();
      handler.postDelayed(save, 5000);
    } catch (Exception e) {
      failure(e);
    }
  }

  void options() {
    TvUi.sheet(
        this,
        "Playback options",
        "Preferences are remembered for this title.",
        new String[] {
          "Video quality",
          "Picture: fit / zoom / fill",
          "Play next episode",
          "Choose another source",
          "Use VLC player",
          "Picture in picture",
          "Repeat: " + (player.getRepeatMode() == Player.REPEAT_MODE_ONE ? "on" : "off")
        },
        -1,
        i -> {
          switch (i) {
            case 0:
              quality();
              break;
            case 1:
              fit();
              break;
            case 2:
              nextEpisode();
              break;
            case 3:
              otherSources();
              break;
            case 4:
              vlc();
              break;
            case 5:
              if (getPackageManager().hasSystemFeature("android.software.picture_in_picture"))
                enterPictureInPictureMode(
                    new PictureInPictureParams.Builder()
                        .setAspectRatio(new android.util.Rational(16, 9))
                        .build());
              break;
            case 6:
              player.setRepeatMode(
                  player.getRepeatMode() == Player.REPEAT_MODE_ONE
                      ? Player.REPEAT_MODE_OFF
                      : Player.REPEAT_MODE_ONE);
          }
        });
  }

  void quality() {
    String[] labels = {
      "Auto · best available", "4K / 2160p", "Full HD / 1080p", "HD / 720p", "SD / 480p"
    };
    int[] heights = {0, 2160, 1080, 720, 480};
    TvUi.sheet(
        this,
        "Video quality",
        "Applies to adaptive streams with matching variants.",
        labels,
        -1,
        i -> {
          store.put("quality." + PlaybackPrefs.titleKey(media), Integer.toString(heights[i]));
          applyPreferences();
        });
  }

  void applyPreferences() {
    int height =
        Integer.parseInt(
            store.get("quality." + PlaybackPrefs.titleKey(media), store.get("qualityHeight", "0")));
    String primary = store.get("subtitleLanguage", "en"), backup = store.get("subtitleBackup", "");
    boolean anime = false;
    JSONObject catalog = media.optJSONObject("catalog");
    if (catalog != null)
      for (JSONObject genre : Models.list(catalog.optJSONArray("genres")))
        if (genre.optInt("id") == 16) anime = true;
    String audio = store.get(anime ? "animeAudio" : "audioLanguage", "en");
    player.setTrackSelectionParameters(
        player
            .getTrackSelectionParameters()
            .buildUpon()
            .setPreferredAudioLanguages(audio, anime ? store.get("animeBackup", "ja") : "en")
            .setPreferredTextLanguages(primary, backup)
            .setTrackTypeDisabled(
                C.TRACK_TYPE_TEXT, !PlaybackPrefs.subtitles(store, media))
            .setMaxVideoSize(Integer.MAX_VALUE, height == 0 ? Integer.MAX_VALUE : height)
            .build());
  }

  void otherSources() {
    JSONObject title = media.optJSONObject("catalog");
    if (title == null) return;
    persist();
    Intent i =
        new Intent(this, MainActivity.class)
            .putExtra("nextTitle", title.toString())
            .putExtra("season", media.optInt("season"))
            .putExtra("episode", media.optInt("episode"));
    startActivity(i);
    finish();
  }

  void buildPlayer(MediaItem.SubtitleConfiguration subtitle) throws Exception {
    long position = player == null ? store.position(key) : player.getCurrentPosition();
    if (player != null) {
      if (session != null) session.release();
      player.release();
    }
    Map<String, String> headers = Api.headers(media.optJSONObject("headers"));
    DefaultHttpDataSource.Factory http =
        new DefaultHttpDataSource.Factory()
            .setUserAgent("Velagram-TV/1.0")
            .setAllowCrossProtocolRedirects(true)
            .setDefaultRequestProperties(headers);
    DefaultDataSource.Factory fallback = new DefaultDataSource.Factory(this, http);
    // Route TDLib through a scheme-aware source; all standard schemes use Media3.
    DataSource.Factory routed = () -> new RoutingSource(fallback, Telegram.get(this));
    if (!media.optString("url").startsWith("td:")) routed = fallback;
    player =
        new ExoPlayer.Builder(this)
            .setMediaSourceFactory(new DefaultMediaSourceFactory(routed))
            .setSeekBackIncrementMs(10000)
            .setSeekForwardIncrementMs(10000)
            .build();
    player.setAudioAttributes(
        new AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .build(),
        true);
    player.setHandleAudioBecomingNoisy(true);
    view.setPlayer(player);
    session =
        new MediaSession.Builder(this, player)
            .setId(java.util.UUID.randomUUID().toString())
            .build();
    MediaItem.Builder item =
        new MediaItem.Builder()
            .setUri(media.getString("url"))
            .setMediaId(key)
            .setMediaMetadata(new MediaMetadata.Builder().setTitle(Models.title(media)).build());
    if (subtitle != null) item.setSubtitleConfigurations(List.of(subtitle));
    else {
      List<MediaItem.SubtitleConfiguration> available = new ArrayList<>();
      for (JSONObject track : Models.list(media.optJSONArray("subtitles")))
        try {
          available.add(
              new MediaItem.SubtitleConfiguration.Builder(Uri.parse(track.getString("url")))
                  .setMimeType(
                      track.optString("url").contains(".srt")
                          ? MimeTypes.APPLICATION_SUBRIP
                          : MimeTypes.TEXT_VTT)
                  .setLanguage(track.optString("language", "en"))
                  .setLabel(track.optString("label", "Subtitle"))
                  .build());
        } catch (Exception ignored) {
        }
      if (!available.isEmpty()) item.setSubtitleConfigurations(available);
    }
    player.setMediaItem(item.build());
    applyPreferences();
    player.setPlaybackSpeed(PlaybackPrefs.speed(store, media));
    view.setResizeMode(Integer.parseInt(store.get("zoom." + PlaybackPrefs.titleKey(media), "0")));
    player.seekTo(position);
    player.addListener(
        new Player.Listener() {
          @Override
          public void onPlayerError(PlaybackException e) {
            if ((e.errorCode >= 4000 && e.errorCode < 5000) || e.errorCode == 3003) {
              vlc();
              return;
            }
            failure(
                new Exception(
                    "Playback failed: "
                        + e.getErrorCodeName()
                        + ". The stream may be offline or use an unsupported codec."));
          }

          @Override
          public void onPlaybackStateChanged(int state) {
            if (controls != null) {
              controls.status(state == Player.STATE_BUFFERING ? "Buffering…" : "");
              controls.refresh();
            }
            if (state == Player.STATE_ENDED) {
              if (journey != null) journey.finish();
              if (store.get("autoNext", "true").equals("true")) nextEpisode();
            }
          }
        });
    player.prepare();
    player.play();
    if (controls != null) controls.show();
  }

  void persist() {
    if (player != null && media != null) {
      if (journey != null) journey.persist(player.getCurrentPosition(), player.getDuration());
      else store.progress(key, player.getCurrentPosition(), player.getDuration(), media);
      if (preloader != null)
        preloader.consider(media, player.getCurrentPosition(), player.getDuration());
    }
  }

  void speed() {
    String[] rates = {"0.5", "0.75", "1.0", "1.25", "1.5", "1.75", "2.0"};
    String[] labels =
        Arrays.stream(rates)
            .map(v -> v + "×" + (v.equals("1.0") ? " · Normal" : ""))
            .toArray(String[]::new);
    TvUi.sheet(
        this,
        "Playback speed",
        "Remembered for " + Models.title(media),
        labels,
        Arrays.asList(rates).indexOf(Float.toString(player.getPlaybackParameters().speed)),
        i -> {
          player.setPlaybackSpeed(Float.parseFloat(rates[i]));
          store.put("speed." + PlaybackPrefs.titleKey(media), rates[i]);
          controls.status(rates[i] + "× playback speed");
          controls.show();
        });
  }

  void fit() {
    int mode = view.getResizeMode();
    view.setResizeMode(
        mode == AspectRatioFrameLayout.RESIZE_MODE_FIT
            ? AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            : mode == AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                ? AspectRatioFrameLayout.RESIZE_MODE_FILL
                : AspectRatioFrameLayout.RESIZE_MODE_FIT);
    store.put("zoom." + PlaybackPrefs.titleKey(media), Integer.toString(view.getResizeMode()));
    Toast.makeText(
            this,
            view.getResizeMode() == 0 ? "Fit" : view.getResizeMode() == 4 ? "Zoom" : "Fill",
            Toast.LENGTH_SHORT)
        .show();
  }

  void tracks(int type) {
    List<String> labels = new ArrayList<>();
    List<Tracks.Group> groups = new ArrayList<>();
    List<Integer> indices = new ArrayList<>();
    labels.add(type == C.TRACK_TYPE_TEXT ? "Off" : "Automatic");
    for (Tracks.Group g : player.getCurrentTracks().getGroups())
      if (g.getType() == type)
        for (int i = 0; i < g.length; i++) {
          Format f = g.getTrackFormat(i);
          labels.add(
              (f.label == null ? "Track " + (i + 1) : f.label)
                  + "  "
                  + (f.language == null ? "" : f.language));
          groups.add(g);
          indices.add(i);
        }
    TvUi.sheet(
        this,
        type == C.TRACK_TYPE_TEXT ? "Subtitle tracks" : "Audio tracks",
        "",
        labels.toArray(new String[0]),
        -1,
        w -> {
          TrackSelectionParameters.Builder b =
              player
                  .getTrackSelectionParameters()
                  .buildUpon()
                  .clearOverridesOfType(type)
                  .setTrackTypeDisabled(type, w == 0 && type == C.TRACK_TYPE_TEXT);
          if (w > 0)
            b.setOverrideForType(
                new TrackSelectionOverride(
                    groups.get(w - 1).getMediaTrackGroup(), indices.get(w - 1)));
          player.setTrackSelectionParameters(b.build());
          if (type == C.TRACK_TYPE_TEXT) PlaybackPrefs.subtitles(store, media, w > 0);
        });
  }

  void subtitles() {
    TvUi.sheet(
        this,
        "Subtitles",
        "Choose a track or find a better match.",
        new String[] {
          "Embedded tracks",
          "Find online subtitles",
          "Import SRT / WebVTT",
          "Caption appearance",
          "Subtitle Studio & saved timing"
        },
        -1,
        i -> {
          if (i == 0) tracks(C.TRACK_TYPE_TEXT);
          if (i == 1) onlineSubtitles();
          if (i == 2)
            try {
              startActivityForResult(
                  new Intent(Intent.ACTION_OPEN_DOCUMENT)
                      .addCategory(Intent.CATEGORY_OPENABLE)
                      .setType("*/*"),
                  7);
            } catch (Exception e) {
              failure(e);
            }
          if (i == 3)
            TvUi.sheet(
                this,
                "Caption appearance",
                "",
                new String[] {"Small", "Medium", "Large", "System caption settings"},
                -1,
                j -> {
                  if (j < 3) {
                    store.put("captionSize", Integer.toString(j));
                    view.getSubtitleView()
                        .setFractionalTextSize(new float[] {.035f, .0533f, .07f}[j]);
                  } else
                    try {
                      startActivity(new Intent("android.settings.CAPTIONING_SETTINGS"));
                    } catch (Exception e) {
                      failure(e);
                    }
                });
          if (i == 4) subtitleStudio();
        });
  }

  void attach(Uri uri, String mime) {
    currentSubtitle = uri;
    PlaybackPrefs.subtitles(store, media, true);
    uri = SubtitleSync.latest(store, key, currentSubtitleKey, uri);
    try {
      MediaItem.SubtitleConfiguration c =
          new MediaItem.SubtitleConfiguration.Builder(uri)
              .setMimeType(mime)
              .setLanguage(store.get("subtitleLanguage", "en"))
              .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
              .build();
      buildPlayer(c);
    } catch (Exception e) {
      failure(e);
    }
  }

  @Override
  protected void onActivityResult(int r, int result, Intent data) {
    super.onActivityResult(r, result, data);
    if (r == 8 && result == RESULT_OK && data != null && data.getData() != null) {
      currentSubtitleKey =
          data.getStringExtra("subtitleKey") == null ? "local" : data.getStringExtra("subtitleKey");
      attach(data.getData(), MimeTypes.APPLICATION_SUBRIP);
      return;
    }
    if (r == 7 && result == RESULT_OK && data != null && data.getData() != null) {
      Uri uri = data.getData();
      currentSubtitleKey = uri.toString();
      getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
      attach(
          uri,
          uri.toString().toLowerCase().contains(".vtt")
              ? MimeTypes.TEXT_VTT
              : MimeTypes.APPLICATION_SUBRIP);
    }
  }

  void onlineSubtitles() {
    JSONObject catalog = media.optJSONObject("catalog");
    if (catalog == null) {
      failure(new Exception("Online subtitles require playback from a catalog title."));
      return;
    }
    Toast.makeText(this, "Searching subtitles…", Toast.LENGTH_SHORT).show();
    Api.IO.execute(
        () -> {
          try {
            online =
                new Subtitles(new Api(store), store)
                    .search(catalog, media.optInt("season"), media.optInt("episode"));
            runOnUiThread(
                () -> {
                  if (online.isEmpty()) {
                    failure(new Exception("No subtitles found for this title and language."));
                    return;
                  }
                  new AlertDialog.Builder(this)
                      .setTitle("Online subtitles")
                      .setItems(
                          online.stream().map(o -> o.optString("name")).toArray(String[]::new),
                          (d, w) -> downloadSubtitle(online.get(w)))
                      .show();
                });
          } catch (Exception e) {
            runOnUiThread(() -> failure(e));
          }
        });
  }

  void downloadSubtitle(JSONObject sub) {
    currentSubtitleKey = SubtitleSync.key(sub);
    Api.IO.execute(
        () -> {
          try {
            java.io.File file = Subtitles.download(this, sub);
            runOnUiThread(
                () ->
                    attach(
                        Uri.fromFile(file),
                        file.getName().endsWith(".vtt")
                            ? MimeTypes.TEXT_VTT
                            : MimeTypes.APPLICATION_SUBRIP));
          } catch (Exception e) {
            runOnUiThread(() -> failure(e));
          }
        });
  }

  void subtitleStudio() {
    List<JSONObject> versions = new ArrayList<>();
    for (JSONObject version : store.items("subtitleVersions"))
      if (version.optString("mediaKey").equals(key)) versions.add(version);
    String[] labels = new String[versions.size() + 1];
    labels[0] = "Adjust subtitle timing";
    for (int i = 0; i < versions.size(); i++)
      labels[i + 1] = versions.get(i).optString("name") + " · saved version";
    TvUi.sheet(
        this,
        "Subtitle Studio",
        "Saved for this episode.",
        labels,
        -1,
        i -> {
          if (i > 0) {
            JSONObject version = versions.get(i - 1);
            currentSubtitleKey = version.optString("subtitleKey");
            attach(
                Uri.fromFile(new java.io.File(version.optString("file"))),
                MimeTypes.APPLICATION_SUBRIP);
          } else {
            Intent intent =
                new Intent(this, SubtitleStudioActivity.class)
                    .putExtra("mediaKey", key)
                    .putExtra("subtitleKey", currentSubtitleKey);
            if (currentSubtitle != null) intent.putExtra("subtitleURI", currentSubtitle.toString());
            startActivityForResult(intent, 8);
          }
        });
  }

  void nextEpisode() {
    if (!Store.episode(media) || transitioning) return;
    transitioning = true;
    if (journey != null) journey.finish();
    JSONObject ready = preloader == null ? null : preloader.take();
    if (ready != null) {
      persist();
      startActivity(new Intent(this, PlayerActivity.class).putExtra("media", ready.toString()));
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
                      Intent i =
                          new Intent(this, MainActivity.class)
                              .putExtra("nextTitle", next.optJSONObject("catalog").toString())
                              .putExtra("season", next.optInt("season"))
                              .putExtra("episode", next.optInt("episode"));
                      startActivity(i);
                      finish();
                    } else {
                      persist();
                      Intent i =
                          new Intent(this, PlayerActivity.class)
                              .putExtra("media", result.toString());
                      startActivity(i);
                      finish();
                    }
                  });
        });
  }

  void vlc() {
    persist();
    startActivity(new Intent(this, VlcActivity.class).putExtra("media", media.toString()));
    finish();
  }

  void failure(Exception e) {
    if (isFinishing()) return;
    TvUi.sheet(
        this,
        "Playback unavailable",
        e.getMessage() == null ? "Try another release or playback engine." : e.getMessage(),
        new String[] {"Try VLC player", "Choose another source", "Close player"},
        -1,
        i -> {
          if (i == 0 && media != null) vlc();
          else if (i == 1 && media != null) otherSources();
          else finish();
        });
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
    if (player != null && !isInPictureInPictureMode()) {
      if (!isFinishing()
          && store.get("backgroundAudio", "false").equals("true")
          && player.getPlayWhenReady()) {
        PlaybackService.activeSession = session;
        startForegroundService(new Intent(this, PlaybackService.class));
      } else player.pause();
    }
    super.onPause();
  }

  @Override
  protected void onResume() {
    super.onResume();
    stopService(new Intent(this, PlaybackService.class));
  }

  @Override
  protected void onDestroy() {
    stopService(new Intent(this, PlaybackService.class));
    PlaybackService.activeSession = null;
    stopped = true;
    if (controls != null) controls.release();
    handler.removeCallbacksAndMessages(null);
    persist();
    if (journey != null) journey.release();
    if (session != null) session.release();
    if (player != null) player.release();
    super.onDestroy();
  }

  static final class RoutingSource implements DataSource {
    final DataSource.Factory fallback;
    final Telegram tg;
    DataSource current;

    RoutingSource(DataSource.Factory f, Telegram t) {
      fallback = f;
      tg = t;
    }

    public void addTransferListener(TransferListener l) {}

    public long open(DataSpec d) throws java.io.IOException {
      current =
          "td".equals(d.uri.getScheme()) ? new TelegramDataSource(tg) : fallback.createDataSource();
      return current.open(d);
    }

    public int read(byte[] b, int o, int n) throws java.io.IOException {
      return current.read(b, o, n);
    }

    public Uri getUri() {
      return current == null ? null : current.getUri();
    }

    public void close() throws java.io.IOException {
      if (current != null) current.close();
    }
  }
}
