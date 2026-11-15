package com.velagram.tv;

import android.app.*;
import android.content.*;
import android.graphics.Typeface;
import android.graphics.drawable.*;
import android.os.*;
import android.text.InputType;
import android.text.TextUtils;
import android.view.*;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import com.bumptech.glide.Glide;
import java.util.*;
import java.util.function.Consumer;
import org.drinkless.tdlib.TdApi;
import org.json.*;

@androidx.media3.common.util.UnstableApi
public class MainActivity extends androidx.activity.ComponentActivity {
  static final int BG = 0xff0b0d12,
      SURFACE = 0xff191d27,
      INK = 0xfff4f6fb,
      MUTED = 0xffa0a9bb,
      ACCENT = 0xffcbd3e1;
  AppUpdater updater;
  Store store;
  Api api;
  LinearLayout root, nav, content;
  ScrollView scroll;
  String current = "Home";
  final Deque<Runnable> back = new ArrayDeque<>();
  int generation = 0, nextGeneration = 0;
  final Set<Integer> retainedGenerations = new HashSet<>();
  final Map<Integer, List<Runnable>> pendingScreens = new HashMap<>();
  Telegram tg;
  JSONObject selected;
  String pendingFile = "";
  List<JSONObject> live = new ArrayList<>();

  SourceDiscovery discovery;
  final Map<String, LinearLayout> providerRows = new LinkedHashMap<>();
  final Map<String, TextView> providerStatuses = new LinkedHashMap<>();
  final Set<String> releaseIds = new HashSet<>();
  boolean suppressBack = false;
  boolean qrRequested = false;
  Runnable currentRoute;
  boolean refreshOnReturn;
  boolean initialFocusPending = true;
  View lastContentFocus;
  final Map<String, JSONObject> episodeMetadata = new HashMap<>();

  interface Task<T> {
    T run() throws Exception;
  }

  @Override
  public void onCreate(Bundle b) {
    super.onCreate(b);
    getWindow().setFlags(1024, 1024);
    updater = new AppUpdater(this);
    store = new Store(this);
    api = new Api(store);
    Downloads.reconcile(this);
    if (PlaybackService.activeSession == null)
      Api.IO.execute(
          () ->
              CachePolicy.trim(
                  getCacheDir(),
                  Math.max(1, TemporaryMedia.budget(Long.parseLong(store.get("cacheMB", "500")) * 1048576, getCacheDir().getUsableSpace())),
                  Integer.parseInt(store.get("cacheDays", "30"))));
    home("Home");
    if (getIntent().hasExtra("nextTitle"))
      sources(
          Models.obj(getIntent().getStringExtra("nextTitle")),
          getIntent().getIntExtra("season", 1),
          getIntent().getIntExtra("episode", 1));
    if (getIntent().getData() != null) {
      android.net.Uri link = getIntent().getData();
      if ("play".equals(link.getHost())) {
        String url = link.getQueryParameter("url");
        if (url != null) {
          String id = link.getQueryParameter("tmdb");
          if (id != null)
            load(
                () -> api.details(Models.json("id", Integer.parseInt(id), "kind", "movie")),
                title -> playTitle(Models.json("url", url), title, 0, 0));
          else
            play(
                Models.json(
                    "name",
                    link.getQueryParameter("title") == null
                        ? "Direct stream"
                        : link.getQueryParameter("title"),
                    "url",
                    url));
        }
      } else if ("title".equals(link.getHost())) {
        String id = link.getQueryParameter("id"), kind = link.getQueryParameter("kind");
        if (id != null)
          load(
              () ->
                  api.details(
                      Models.json(
                          "id", Integer.parseInt(id), "kind", kind == null ? "movie" : kind)),
              this::detail);
      }
    }
  }

  int dp(float n) {
    return (int) (getResources().getDisplayMetrics().density * n + .5f);
  }

  TextView text(String s, int size, int color) {
    TextView t = new TextView(this);
    t.setText(s);
    t.setTextSize(size);
    t.setTextColor(color);
    t.setFontFeatureSettings("kern");
    t.setIncludeFontPadding(false);
    return t;
  }

  LinearLayout column() {
    LinearLayout l = new LinearLayout(this);
    l.setOrientation(LinearLayout.VERTICAL);
    return l;
  }

  LinearLayout row() {
    LinearLayout l = new LinearLayout(this);
    l.setGravity(Gravity.CENTER_VERTICAL);
    return l;
  }

  GradientDrawable shape(int color, int radius) {
    GradientDrawable d = new GradientDrawable();
    d.setColor(color);
    d.setCornerRadius(dp(radius));
    d.setStroke(dp(1), 0xff303645);
    return d;
  }

  void focus(View v) {
    TvUi.focus(v, false);
  }

  TextView button(String title, Runnable action) {
    TextView b = TvUi.button(this, title, false, action);
    LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, -2);
    p.setMargins(dp(26), dp(5), dp(10), dp(5));
    b.setLayoutParams(p);
    return b;
  }

  TextView primary(String title, Runnable action) {
    TextView b = TvUi.button(this, title, true, action);
    LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, dp(42));
    p.setMargins(0, 0, dp(10), 0);
    b.setLayoutParams(p);
    return b;
  }

  void heading(String title, String subtitle) {
    TextView h = text(title, 29, INK);
    h.setTypeface(null, Typeface.BOLD);
    h.setPadding(dp(26), dp(22), dp(26), dp(8));
    content.addView(h);
    if (!subtitle.isEmpty()) {
      TextView sub = text(subtitle, 13, MUTED);
      sub.setPadding(dp(26), 0, dp(26), dp(19));
      content.addView(sub);
    }
  }

  void note(String value) {
    TextView t = text(value, 14, MUTED);
    t.setPadding(dp(26), dp(10), dp(26), dp(16));
    content.addView(t);
  }

  void shell(String active) {
    generation = ++nextGeneration;
    if (discovery != null) {
      discovery.cancel();
      discovery = null;
    }
    if (tg != null) tg.listener = null;
    current = active;
    root = row();
    root.setBaselineAligned(false);
    root.setGravity(Gravity.TOP);
    root.setBackgroundColor(TvUi.BG);
    root.setClipChildren(false);
    nav = column();
    nav.setClipChildren(false);
    nav.setClipToPadding(false);
    nav.setElevation(dp(12));
    nav.setGravity(Gravity.CENTER_HORIZONTAL);
    nav.setPadding(0, dp(22), 0, dp(14));
    nav.setBackgroundColor(0xff0d1017);
    root.addView(nav, new LinearLayout.LayoutParams(dp(78), -1));
    ImageView logo = new ImageView(this);
    logo.setImageResource(R.drawable.vela_logo);
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(34), dp(38));
    lp.setMargins(0, 0, 0, dp(24));
    nav.addView(logo, lp);
    for (String name :
        new String[] {
          "Home", "Movies", "Series", "Search", "Telegram", "Live TV", "Library", "More"
        }) {
      FrameLayout item = new FrameLayout(this);
      item.setClipChildren(false);
      item.setFocusable(true);
      item.setClickable(true);
      item.setContentDescription(name);
      View focusHalo = new View(this);
      GradientDrawable halo = TvUi.shape(androidx.core.graphics.ColorUtils.blendARGB(TvUi.BG, TvUi.accent(store), .20f), 10, this);
      halo.setStroke(dp(1.5f), TvUi.accent(store)); focusHalo.setBackground(halo); focusHalo.setVisibility(View.GONE);
      focusHalo.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
      item.addView(focusHalo, new FrameLayout.LayoutParams(dp(180), dp(46)));
      TvUi.Icon icon = new TvUi.Icon(this, name, name.equals(active) ? INK : MUTED);
      item.addView(icon, new FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER));
      TextView label = text(name.equals("More") ? "Settings" : name, 14, INK);
      label.setPadding(dp(14), 0, dp(18), 0);
      label.setGravity(Gravity.CENTER_VERTICAL);
      label.setVisibility(View.GONE);
      label.setBackgroundColor(android.graphics.Color.TRANSPARENT);
      FrameLayout.LayoutParams labelParams = new FrameLayout.LayoutParams(dp(136), dp(46));
      labelParams.leftMargin = dp(44);
      item.addView(label, labelParams);
      LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(dp(60), dp(46));
      ip.setMargins(0, 0, 0, dp(4));
      nav.addView(item, ip);
      if (name.equals(active)) item.setBackground(TvUi.shape(0xff232936, 10, this));
      item.setOnFocusChangeListener(
          (v, f) -> {
            item.setBackground(TvUi.shape(name.equals(current) ? 0xff232936 : 0xff0d1017, 10, this));
            focusHalo.setVisibility(f ? View.VISIBLE : View.GONE);
            icon.tint(f || name.equals(current) ? INK : MUTED);
            label.setVisibility(f ? View.VISIBLE : View.GONE);
          });
      item.setOnClickListener(
          v -> {
            back.clear(); retainedGenerations.clear(); pendingScreens.clear();
            home(name);
          });
    }
    View gap = new View(this);
    nav.addView(gap, new LinearLayout.LayoutParams(1, 0, 1));
    TextView profile =
        TvUi.button(
            this, store.profile().substring(0, 1).toUpperCase(Locale.ROOT), false, this::profiles);
    profile.setContentDescription("Switch profile");
    profile.setPadding(0, 0, 0, 0);
    nav.addView(profile, new LinearLayout.LayoutParams(dp(30), dp(30)));
    scroll = new ScrollView(this);
    scroll.setClipToPadding(false);
    scroll.setFillViewport(true);
    scroll.setVerticalScrollBarEnabled(false);
    content = column();
    lastContentFocus = null;
    content.setPadding(0, 0, 0, dp(35));
    scroll.addView(content);
    root.addView(scroll, new LinearLayout.LayoutParams(0, -1, 1));
    setContentView(root);
  }

  void eyebrow(String value) {
    TextView t = text(value.toUpperCase(Locale.ROOT), 10, TvUi.accent(store));
    t.setLetterSpacing(.16f);
    t.setTypeface(null, Typeface.BOLD);
    t.setPadding(dp(26), dp(22), 0, dp(8));
    content.addView(t);
  }

  void deliver(int ticket, Runnable update) {
    if (isFinishing() || isDestroyed()) return;
    if (ticket == generation) update.run();
    else if (retainedGenerations.contains(ticket)) pendingScreens.computeIfAbsent(ticket, key -> new ArrayList<>()).add(update);
  }

  <T> void load(Task<T> task, Consumer<T> done) {
    int ticket = generation;
    Api.IO.execute(
        () -> {
          try {
            T value = task.run();
            runOnUiThread(
                () -> {
                  deliver(ticket, () -> done.accept(value));
                });
          } catch (Exception e) {
            runOnUiThread(
                () -> {
                  deliver(ticket, () -> error(e));
                });
          }
        });
  }

  void error(Exception e) {
    TvUi.sheet(
        this,
        "Something needs attention",
        e.getMessage() == null ? "Please try again." : e.getMessage(),
        new String[] {"Back"},
        -1,
        i -> {});
  }

  void home(String page) {
    currentRoute = () -> home(page);
    shell(page);
    switch (page) {
      case "Home":
        catalogHome();
        break;
      case "Movies":
        catalogPage("movie");
        break;
      case "Series":
        catalogPage("tv");
        break;
      case "Search":
        search();
        break;
      case "Telegram":
        telegram();
        break;
      case "Live TV":
        livePage("");
        break;
      case "Library":
        library();
        break;
      default:
        more();
    }
  }

  void catalogHome() {
    LinearLayout loading = column();
    loading.setPadding(dp(28), dp(70), dp(28), dp(70));
    TextView title = text("Your next great watch.", 36, INK);
    title.setTypeface(null, Typeface.BOLD);
    loading.addView(title);
    TextView status = text("Loading your library…", 14, MUTED);
    status.setPadding(0, dp(18), 0, 0);
    loading.addView(status);
    content.addView(loading);
    int ticket = generation;
    Api.IO.execute(
        () -> {
          try {
            List<JSONObject> items = api.catalog("trending/movie/week", "movie");
            runOnUiThread(
                () -> deliver(ticket, () -> {
                  content.removeView(loading);
                  if (!items.isEmpty()) hero(items.get(0));
                  List<JSONObject> history = new ArrayList<>();
                  for (JSONObject item : store.continueWatching())
                    if (item.has("catalog")) history.add(item);
                  if (!history.isEmpty()) shelf("Continue watching", history, this::resume, false);
                  shelf("Trending now", items, this::detail, true);
                  load(
                      () -> api.catalog("tv/popular", "tv"),
                      r -> shelf("Series to get lost in", r, this::detail, true));
                  load(
                      () -> api.catalog("movie/top_rated", "movie"),
                      r -> shelf("The ones worth watching", r, this::detail, true));
                }));
          } catch (Exception e) {
            runOnUiThread(
                () -> deliver(ticket, () -> {
                  status.setText(
                      "The catalog could not be reached. Check your connection or TMDB settings.");
                  loading.addView(button("Try again", () -> home("Home")));
                  loading.addView(button("Open connected libraries", () -> home("Library")));
                }));
          }
        });
  }

  void catalogPage(String kind) {
    eyebrow("THE VELAGRAM COLLECTION");
    heading(kind.equals("movie") ? "Movies" : "Series", "Find your next obsession.");
    LinearLayout chips = row();
    chips.setPadding(dp(26), 0, 0, dp(14));
    for (String[] g :
        new String[][] {
          {"All", ""},
          {"Action", kind.equals("movie") ? "28" : "10759"},
          {"Drama", "18"},
          {"Comedy", "35"},
          {"Animation", "16"},
          {"Documentary", "99"}
        }) {
      TextView chip =
          TvUi.button(
              this,
              g[0],
              false,
              () -> {
                push(
                    () -> {
                      shell(kind.equals("movie") ? "Movies" : "Series");
                      heading(g[0].equals("All") ? "All titles" : g[0], "");
                      pagedCatalog(
                          "discover/" + kind + (g[1].isEmpty() ? "" : "?with_genres=" + g[1]),
                          kind,
                          1);
                    });
              });
      LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-2, dp(34));
      cp.setMargins(0, 0, dp(8), 0);
      chip.setTextSize(12);
      chips.addView(chip, cp);
    }
    content.addView(chips);
    for (String[] section :
        new String[][] {
          {"popular", "Popular right now"},
          {"top_rated", "Critically acclaimed"},
          {kind.equals("movie") ? "now_playing" : "on_the_air", "New & noteworthy"}
        })
      load(
          () -> api.catalog(kind + "/" + section[0], kind),
          r -> shelf(section[1], r, this::detail, true));
  }

  void pagedCatalog(String path, String kind, int page) {
    load(
        () -> api.catalog(path + (path.contains("?") ? "&" : "?") + "page=" + page, kind),
        r -> {
          grid(r, this::detail, true);
          if (!r.isEmpty())
            content.addView(button("Load more", () -> pagedCatalog(path, kind, page + 1)));
        });
  }

  void hero(JSONObject item) {
    hero(item, false);
  }

  void hero(JSONObject item, boolean details) {
    View hero = DetailHero.create(this, item, details, store,
        () -> {
          if (!details) detail(item);
          else if (item.optString("kind").equals("tv")) {
            View episodes = content.findViewWithTag("episode-section");
            if (episodes != null) episodes.requestFocus(); else seasons(item);
          } else sources(item, 0, 0);
        },
        () -> {
          if (store.contains("watchlist", item)) store.remove("watchlist", item);
          else store.upsert("watchlist", item);
        },
        () -> TvUi.sheet(this, Models.title(item), "Your library",
            new String[] {store.contains("watched", item) ? "Mark unwatched" : "Mark watched", "Remove from Continue watching"},
            -1, index -> {
              if (index == 0) {
                if (store.contains("watched", item)) store.remove("watched", item); else store.upsert("watched", item);
              } else for (JSONObject history : store.items("history"))
                if (history.optJSONObject("catalog") != null && Models.key(history.optJSONObject("catalog")).equals(Models.key(item))) store.removeContinueWatching(history);
            }));
    if (details) hero.setTag("detail-hero");
    content.addView(hero, new LinearLayout.LayoutParams(-1, dp(details ? 290 : 260)));
    if (!details && initialFocusPending) { initialFocusPending = false; hero.post(() -> { hero.requestFocus(); lastContentFocus = hero; }); }
    else if (details) hero.post(() -> {
      if (content.findViewWithTag("detail-hero") == hero && hasWindowFocus()) { hero.requestFocus(); lastContentFocus = hero; }
    });
  }

  void shelf(String name, List<JSONObject> items, Consumer<JSONObject> action, boolean poster) {
    if (items.isEmpty()) return;
    TextView title = text(name, 19, INK);
    title.setTypeface(null, Typeface.BOLD);
    title.setTextSize(16);
    title.setPadding(dp(28), dp(10), dp(26), dp(4));
    content.addView(title);
    View shelf = BrowseComponents.shelf(this, items, poster, action,
        item -> { if (item.has("catalog")) continueMenu(item); else itemMenu(item); });
    shelf.setTag(name.equals("More like this") ? "related-section" : "artwork-shelf");
    content.addView(shelf, new LinearLayout.LayoutParams(-1, dp(poster ? 236 : 138)));
  }

  View card(JSONObject item, Consumer<JSONObject> action, boolean poster) {
    boolean history = item.has("catalog");
    JSONObject visual = history ? item.optJSONObject("catalog") : item;
    if (visual == null) visual = item;
    int width = poster ? 113 : 220;
    LinearLayout c = column();
    LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(dp(width), -2);
    cp.setMargins(0, 0, dp(13), dp(7));
    c.setLayoutParams(cp);
    c.setPadding(dp(2), dp(2), dp(2), dp(2));
    c.setFocusable(true);
    c.setClickable(true);
    c.setContentDescription(Models.title(item));
    c.setBackground(TvUi.shape(0x00000000, 9, this));
    FrameLayout artwork = new FrameLayout(this);
    artwork.setBackground(TvUi.shape(TvUi.SURFACE, 7, this));
    artwork.setClipToOutline(true);
    c.addView(artwork, new LinearLayout.LayoutParams(-1, dp(poster ? 163 : 116)));
    ImageView image = new ImageView(this);
    image.setScaleType(
        poster || history ? ImageView.ScaleType.CENTER_CROP : ImageView.ScaleType.FIT_CENTER);
    artwork.addView(image, new FrameLayout.LayoutParams(-1, -1));
    String url = Models.image(visual);
    if (!poster && !visual.optString("backdrop_path").isEmpty())
      url = "https://image.tmdb.org/t/p/w780" + visual.optString("backdrop_path");
    if (history && !item.optString("episodeStill").isEmpty())
      url = "https://image.tmdb.org/t/p/w780" + item.optString("episodeStill");
    if (!url.isEmpty()) Glide.with(this).load(url).into(image);
    if (history) {
      View gradient = new View(this);
      gradient.setBackground(
          new GradientDrawable(
              GradientDrawable.Orientation.TOP_BOTTOM, new int[] {0x00000000, 0xe0080a10}));
      FrameLayout.LayoutParams gp = new FrameLayout.LayoutParams(-1, dp(82), Gravity.BOTTOM);
      artwork.addView(gradient, gp);
      LinearLayout labels = column();
      labels.setPadding(dp(10), 0, dp(10), dp(9));
      TextView title = text(Models.title(visual), 13, INK);
      title.setTypeface(null, Typeface.BOLD); title.setSingleLine(true); title.setEllipsize(TextUtils.TruncateAt.END);
      labels.addView(title);
      if (Store.episode(item)) {
        TextView episodeName = text(item.optString("episodeTitle", "Episode " + item.optInt("episode")), 11, 0xffc6ccd7);
        episodeName.setSingleLine(true); episodeName.setEllipsize(TextUtils.TruncateAt.END); labels.addView(episodeName);
      }
      TextView label = text((Store.episode(item) ? String.format(Locale.US, "S%02dE%02d • ", item.optInt("season"), item.optInt("episode")) : "")
          + CinemaControls.time(item.optLong("position")), 10, 0xffb7bfcd);
      label.setPadding(0, dp(4), 0, dp(3)); labels.addView(label);
      artwork.addView(labels, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
      if (Store.episode(item) && item.optLong("position") <= 0 && item.optLong("duration") <= 0) {
        TextView badge = text("Next Up", 10, INK);
        badge.setPadding(dp(8), dp(5), dp(8), dp(5)); badge.setBackground(TvUi.shape(0xb0000000, 5, this));
        FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.RIGHT);
        bp.setMargins(0, dp(8), dp(8), 0); artwork.addView(badge, bp);
      }
      ProgressBar progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
      progress.setProgressTintList(android.content.res.ColorStateList.valueOf(TvUi.accent(store)));
      progress.setMax(1000);
      progress.setProgress(
          (int) (1000 * item.optDouble("position") / Math.max(1, item.optDouble("duration"))));
      FrameLayout.LayoutParams pp = new FrameLayout.LayoutParams(-1, dp(3), Gravity.BOTTOM);
      artwork.addView(progress, pp);
    }
    TextView name = text(Models.title(item), 12, INK);
    name.setMaxLines(1);
    name.setEllipsize(TextUtils.TruncateAt.END);
    name.setPadding(dp(2), dp(8), dp(2), 0);
    if (!history) c.addView(name);
    TextView meta =
        text(
            visual.has("vote_average")
                ? Models.year(visual)
                    + "   ★ "
                    + String.format(Locale.US, "%.1f", visual.optDouble("vote_average"))
                : item.optString("group", item.optString("description", "")),
            10,
            MUTED);
    meta.setMaxLines(1);
    meta.setEllipsize(TextUtils.TruncateAt.END);
    meta.setPadding(dp(2), dp(4), 0, 0);
    if (!history) c.addView(meta);
    c.setOnFocusChangeListener(
        (v, f) -> {
          GradientDrawable border = TvUi.shape(f ? 0xff252c3a : 0x00000000, 9, this);
          if (f) border.setStroke(dp(2), INK);
          v.setBackground(border);
          artwork.animate().scaleX(f ? 1.04f : 1).scaleY(f ? 1.04f : 1).setDuration(175).start();
        });
    c.setOnClickListener(v -> action.accept(item));
    c.setOnLongClickListener(
        v -> {
          if (history) continueMenu(item); else itemMenu(item);
          return true;
        });
    return c;
  }

  void grid(List<JSONObject> items, Consumer<JSONObject> action, boolean poster) {
    int count = poster ? 6 : 3;
    for (int start = 0; start < items.size(); start += count) {
      List<JSONObject> group = new ArrayList<>(items.subList(start, Math.min(items.size(), start + count)));
      View cards = BrowseComponents.shelf(this, group, poster, action, this::itemMenu);
      content.addView(cards, new LinearLayout.LayoutParams(-1, dp(poster ? 236 : 138)));
    }
  }

  void push(Runnable action) {
    final int savedTicket = generation;
    final Map<String, LinearLayout> savedProviderRows = new LinkedHashMap<>(providerRows);
    final Map<String, TextView> savedProviderStatuses = new LinkedHashMap<>(providerStatuses);
    final Set<String> savedReleaseIds = new HashSet<>(releaseIds);
    retainedGenerations.add(savedTicket);
    final LinearLayout savedRoot = root, savedNav = nav, savedContent = content;
    final ScrollView savedScroll = scroll;
    final View savedContentFocus = lastContentFocus;
    final View savedFocus = getCurrentFocus();
    final String savedCurrent = current;
    final Runnable savedRoute = currentRoute;
    back.push(() -> {
      generation = savedTicket;
      retainedGenerations.remove(savedTicket);
      if (discovery != null) { discovery.cancel(); discovery = null; }
      root = savedRoot; nav = savedNav; content = savedContent; scroll = savedScroll; lastContentFocus = savedContentFocus;
      current = savedCurrent; currentRoute = savedRoute;
      providerRows.clear(); providerRows.putAll(savedProviderRows);
      providerStatuses.clear(); providerStatuses.putAll(savedProviderStatuses);
      releaseIds.clear(); releaseIds.addAll(savedReleaseIds);
      for (TextView status : providerStatuses.values()) if (status.getText().toString().equals("Searching")) status.setText("Search paused · Retry");
      setContentView(root);
      List<Runnable> updates = pendingScreens.remove(savedTicket);
      if (updates != null) for (Runnable update : updates) update.run();
      BrowseComponents.refresh(root);
      if (savedFocus != null) savedFocus.post(savedFocus::requestFocus);
    });
    currentRoute = action;
    action.run();
  }

  void detail(JSONObject item) { detail(item, ""); }

  void detail(JSONObject item, String destination) {
    if (item.optString("kind").equals("person")) {
      person(item);
      return;
    }
    selected = item;
    push(
        () -> {
          shell(current);
          hero(item, true);
          note("Loading details…");
          content.getChildAt(content.getChildCount() - 1).setTag("details-loading");
          load(
              () -> api.details(item),
              full -> {
                selected = full;
                View existingHero = content.findViewWithTag("detail-hero");
                if (existingHero != null) DetailHero.update(existingHero, full, store);
                else { content.removeAllViews(); hero(full, true); }
                View loading = content.findViewWithTag("details-loading"); if (loading != null) content.removeView(loading);
                content.addView(DetailHero.tabs(this,
                    full.optString("kind").equals("tv") ? new String[] {"Episodes", "Cast & crew", "More like this"} : new String[] {"Overview", "Cast & crew", "More like this"},
                    Arrays.asList(() -> focusSection(full.optString("kind").equals("tv") ? "episode-section" : "detail-hero"),
                        () -> focusSection("cast-section"), () -> focusSection("related-section")), TvUi.accent(store)),
                    new LinearLayout.LayoutParams(-1, dp(38)));
                if (full.optString("kind").equals("tv")) episodeShelf(full, firstSeason(full), true);
                cast(full);
                load(
                    () ->
                        api.catalog(
                            full.optString("kind") + "/" + full.optInt("id") + "/recommendations",
                            full.optString("kind")),
                    r -> {
                      if (r.isEmpty()) { TextView empty = text("No related titles are available.", 13, MUTED); empty.setPadding(dp(28), dp(20), dp(28), dp(20)); empty.setTag("related-section"); content.addView(empty); }
                      else shelf("More like this", r, this::detail, true);
                      if (destination.equals("related-section")) content.post(() -> focusSection(destination));
                    });
                if (!destination.isEmpty() && !destination.equals("related-section")) content.post(() -> focusSection(destination));
              });
        });
  }

  void focusSection(String tag) {
    View target = content.findViewWithTag(tag);
    if (target == null) return;
    android.graphics.Rect rect = new android.graphics.Rect(); target.getDrawingRect(rect);
    scroll.offsetDescendantRectToMyCoords(target, rect); scroll.smoothScrollTo(0, rect.top);
    target.requestFocus();
  }

  int firstSeason(JSONObject item) {
    for (JSONObject season : Models.list(item.optJSONArray("seasons")))
      if (season.optInt("season_number") > 0) return season.optInt("season_number");
    return 1;
  }

  void cast(JSONObject item) {
    JSONObject credits = item.optJSONObject("credits");
    List<JSONObject> cast = credits == null ? Collections.emptyList() : Models.list(credits.optJSONArray("cast"));
    if (cast.isEmpty()) {
      TextView empty = text("Cast information is not available.", 13, MUTED); empty.setPadding(dp(28), dp(20), dp(28), dp(20)); empty.setTag("cast-section"); content.addView(empty); return;
    }
    TextView h = text("Cast & characters", 19, INK);
    h.setTypeface(null, Typeface.BOLD);
    h.setPadding(dp(28), dp(18), 0, dp(13));
    content.addView(h);
    HorizontalScrollView hs = new HorizontalScrollView(this);
    hs.setHorizontalScrollBarEnabled(false);
    LinearLayout r = row();
    r.setPadding(dp(28), dp(5), dp(28), dp(16));
    r.setGravity(Gravity.TOP);
    for (JSONObject actor : cast.subList(0, Math.min(20, cast.size()))) {
      LinearLayout c = column();
      c.setGravity(Gravity.CENTER_HORIZONTAL);
      c.setFocusable(true);
      c.setClickable(true);
      LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(dp(93), -2);
      p.setMargins(0, 0, dp(19), 0);
      r.addView(c, p);
      ImageView photo = new ImageView(this);
      photo.setBackground(TvUi.shape(TvUi.SURFACE, 50, this));
      photo.setClipToOutline(true);
      photo.setScaleType(ImageView.ScaleType.CENTER_CROP);
      c.addView(photo, new LinearLayout.LayoutParams(dp(75), dp(75)));
      String path = actor.optString("profile_path");
      if (!path.isEmpty() && !path.equals("null"))
        Glide.with(this).load("https://image.tmdb.org/t/p/w185" + path).circleCrop().into(photo);
      TextView name = text(actor.optString("name"), 11, INK);
      name.setGravity(Gravity.CENTER);
      name.setMaxLines(1);
      name.setEllipsize(TextUtils.TruncateAt.END);
      name.setPadding(0, dp(8), 0, dp(4));
      c.addView(name);
      TextView role = text(actor.optString("character"), 10, MUTED);
      role.setGravity(Gravity.CENTER);
      role.setMaxLines(1);
      role.setEllipsize(TextUtils.TruncateAt.END);
      c.addView(role);
      c.setOnClickListener(v -> person(actor));
      c.setOnFocusChangeListener(
          (v, f) -> {
            v.animate().scaleX(f ? 1.1f : 1).scaleY(f ? 1.1f : 1).setDuration(150).start();
            name.setTextColor(f ? TvUi.accent(store) : INK);
          });
    }
    hs.addView(r);
    hs.setTag("cast-section");
    content.addView(hs);
  }

  void person(JSONObject actor) {
    push(
        () -> {
          shell(current);
          eyebrow("THE PEOPLE BEHIND THE STORIES");
          heading(actor.optString("name"), "Movies & series");
          load(() -> api.filmography(actor.optInt("id")), r -> grid(r, this::detail, true));
        });
  }

  void seasons(JSONObject item) {
    load(
        () -> Models.list(api.tmdb("tv/" + item.optInt("id")).optJSONArray("seasons")),
        list ->
            choose(
                "Choose a season",
                list,
                o -> o.optString("name"),
                season -> episodes(item, season.optInt("season_number"))));
  }

  void episodes(JSONObject item, int season) {
    push(
        () -> {
          shell("Series");
          content.addView(DetailHero.compact(this, item, store, "Episodes"), new LinearLayout.LayoutParams(-1, dp(162)));
          content.addView(DetailHero.tabs(this, new String[] {"Episodes", "Overview", "More like this"},
              Arrays.asList(() -> focusSection("episode-section"), () -> detail(item, "detail-hero"), () -> detail(item, "related-section")), TvUi.accent(store)), new LinearLayout.LayoutParams(-1, dp(38)));
          episodeShelf(item, season);
        });
  }

  void episodeShelf(JSONObject title, int season) { episodeShelf(title, season, false); }

  void episodeShelf(JSONObject title, int season, boolean horizontal) {
    LinearLayout section = column();
    section.setTag(R.id.presentation_mode, horizontal);
    content.addView(section);
    renderEpisodeSeason(section, title, season, false);
  }

  void renderEpisodeSeason(LinearLayout section, JSONObject title, int season, boolean focusEpisode) {
    section.setTag(season);
    section.removeAllViews();
    TextView picker = TvUi.button(this, "Season " + season + "  ▾", false, () -> {
      List<JSONObject> seasons = Models.list(title.optJSONArray("seasons"));
      if (seasons.isEmpty()) { seasons(title); return; }
      String[] names = new String[seasons.size()]; int selectedSeason = 0;
      for (int i = 0; i < names.length; i++) { names[i] = seasons.get(i).optString("name"); if (seasons.get(i).optInt("season_number") == season) selectedSeason = i; }
      TvUi.sheet(this, "Choose a season", Models.title(title), names, selectedSeason,
          index -> renderEpisodeSeason(section, title, seasons.get(index).optInt("season_number"), true));
    });
    picker.setTextSize(12); picker.setPadding(dp(14), dp(8), dp(14), dp(8));
    picker.setTag("episode-section");
    LinearLayout toolbar = row(); toolbar.setPadding(dp(28), dp(8), dp(28), dp(8));
    toolbar.addView(picker, new LinearLayout.LayoutParams(-2, dp(32)));
    View gap = new View(this); toolbar.addView(gap, new LinearLayout.LayoutParams(0, 1, 1));
    TextView manual = TvUi.button(this, "Enter episode", false, () -> manualEpisode(title));
    manual.setTextSize(11); manual.setPadding(dp(12), 0, dp(12), 0);
    toolbar.addView(manual, new LinearLayout.LayoutParams(-2, dp(32)));
    section.addView(toolbar);
    if (!focusEpisode && !Boolean.TRUE.equals(section.getTag(R.id.presentation_mode))) picker.post(() -> {
      if (picker.isAttachedToWindow() && hasWindowFocus()) picker.requestFocus();
    });
    load(
        () ->
            Models.list(
                api.tmdb("tv/" + title.optInt("id") + "/season/" + season)
                    .optJSONArray("episodes")),
        episodes -> {
          if (!Integer.valueOf(season).equals(section.getTag())) return;
          if (Boolean.TRUE.equals(section.getTag(R.id.presentation_mode))) {
            List<JSONObject> requests = new ArrayList<>();
            for (JSONObject episode : episodes) { JSONObject request = episodeRequest(title, season, episode); requests.add(request); episodeMetadata.put(Store.progressKey(request), episode); }
            View cards = BrowseComponents.episodes(this, episodes, requests, store,
                request -> sources(title, season, request.optInt("episode")),
                request -> episodeMenu(request, episodeMetadata.get(Store.progressKey(request))));
            section.addView(cards, new LinearLayout.LayoutParams(-1, dp(148)));
            if (focusEpisode) cards.post(cards::requestFocus);
            return;
          }
          for (JSONObject episode : episodes) {
            JSONObject request = episodeRequest(title, season, episode);
            episodeMetadata.put(Store.progressKey(request), episode);
            View episodeRow = BrowseComponents.episode(this, episode, request, store,
                () -> sources(title, season, episode.optInt("episode_number")),
                () -> episodeMenu(request, episode));
            section.addView(episodeRow, new LinearLayout.LayoutParams(-1, dp(104)));
            if (focusEpisode && section.getChildCount() == 2) episodeRow.post(episodeRow::requestFocus);
          }
        });
  }

  JSONObject episodeRequest(JSONObject title, int season, JSONObject episode) {
    return Models.json("catalog", title, "season", season, "episode", episode.optInt("episode_number"),
        "episodeTitle", episode.optString("name"), "episodeStill", episode.optString("still_path"));
  }

  void episodeMenu(JSONObject request, JSONObject episode) {
    boolean watched = store.isWatched(request);
    TvUi.sheet(this, episode.optString("name", "Episode " + request.optInt("episode")), episode.optString("overview"),
        new String[] {store.position(Store.progressKey(request)) > 0 ? "Resume" : "Play",
            watched ? "Mark unwatched" : "Mark watched", "Mark previous episodes watched", "Episode info"}, -1, i -> {
          if (i == 0) resume(request);
          if (i == 1) {
            store.setWatched(request, !watched);
            if (!watched) EpisodeNavigator.nextUnwatched(api, store, request, (next, error) -> { if (next != null) store.promote(next); currentRoute.run(); });
            else currentRoute.run();
          }
          if (i == 2) markPreviousEpisodes(request);
          if (i == 3) TvUi.sheet(this, episode.optString("name"), "Season " + request.optInt("season") + " · Episode " + request.optInt("episode")
              + " · " + episode.optString("air_date") + "\n\n" + episode.optString("overview"), new String[] {"Play"}, -1, j -> resume(request));
        });
  }

  void markPreviousEpisodes(JSONObject selected) {
    JSONObject title = selected.optJSONObject("catalog");
    load(() -> {
      List<JSONObject> requests = new ArrayList<>();
      JSONObject details = api.tmdb("tv/" + title.optInt("id"));
      for (JSONObject season : Models.list(details.optJSONArray("seasons"))) {
        int number = season.optInt("season_number");
        if (number < 0 || number > selected.optInt("season")) continue;
        for (JSONObject episode : Models.list(api.tmdb("tv/" + title.optInt("id") + "/season/" + number).optJSONArray("episodes")))
          if (number < selected.optInt("season") || episode.optInt("episode_number") < selected.optInt("episode"))
            requests.add(episodeRequest(title, number, episode));
      }
      return requests;
    }, requests -> { store.markPrevious(requests, selected); currentRoute.run(); });
  }

  void manualEpisode(JSONObject title) {
    form(
        "Choose an episode",
        new String[] {"Season number", "Episode number"},
        values -> {
          try {
            int season = Integer.parseInt(values[0]), episode = Integer.parseInt(values[1]);
            if (season < 0 || episode < 1) throw new NumberFormatException();
            sources(title, season, episode);
          } catch (Exception e) {
            error(new Exception("Enter valid season and episode numbers."));
          }
        });
  }

  void sources(JSONObject item, int season, int episode) {
    push(() -> showSources(item, season, episode));
  }

  void showSources(JSONObject item, int season, int episode) {
    shell(current);
    JSONObject episodeInfo = episodeMetadata.get(Store.progressKey(Models.json("catalog", item, "season", season, "episode", episode)));
    content.addView(DetailHero.source(this, item, store, season, episode, episodeInfo), new LinearLayout.LayoutParams(-1, dp(176)));
    final String[] providerFilter = {"All"};
    LinearLayout controls = row();
    TextView filter = button("Providers · All", () -> {});
    filter.setOnClickListener(v -> {
      List<String> options = new ArrayList<>(); options.add("All"); options.addAll(providerRows.keySet());
      TvUi.sheet(this, "Providers", "Filter available results", options.toArray(new String[0]), options.indexOf(providerFilter[0]), index -> {
        providerFilter[0] = options.get(index); filter.setText("Providers · " + providerFilter[0]);
        for (Map.Entry<String, LinearLayout> group : providerRows.entrySet())
          group.getValue().setVisibility(providerFilter[0].equals("All") || providerFilter[0].equals(group.getKey()) ? View.VISIBLE : View.GONE);
      });
    });
    controls.addView(filter);
    controls.addView(button("↻  Search again", () -> showSources(item, season, episode)));
    if (item.optString("kind").equals("tv"))
      controls.addView(button("Change episode", () -> manualEpisode(item)));
    controls.addView(
        button(
            "Direct URL",
            () ->
                prompt(
                    "Stream URL",
                    "https://",
                    url -> playTitle(Models.json("url", url), item, season, episode))));
    controls.setPadding(dp(28), dp(4), dp(28), dp(10));
    for (int index = 0; index < controls.getChildCount(); index++) if (controls.getChildAt(index) instanceof TextView) {
      TextView control = (TextView) controls.getChildAt(index); control.setTextSize(11); control.setPadding(dp(14), 0, dp(14), 0);
      LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, dp(32)); p.setMargins(0, 0, dp(8), 0); control.setLayoutParams(p);
    }
    content.addView(controls);
    final int focusTicket = generation;
    filter.post(() -> { if (focusTicket == generation && hasWindowFocus()) filter.requestFocus(); });
    providerRows.clear();
    providerStatuses.clear();
    releaseIds.clear();
    discovery = new SourceDiscovery(this, store);
    int ticket = generation;
    discovery.search(
        item,
        season,
        episode,
        (provider, releases, status) -> {
          if (ticket != generation) return;
          LinearLayout group = providerRows.get(provider);
          TextView state = providerStatuses.get(provider);
          if (group == null) {
            group = column();
            group.setPadding(dp(12), dp(8), dp(12), dp(8));
            group.setBackground(TvUi.shape(0xff10141d, 8, this));
            LinearLayout.LayoutParams gp = new LinearLayout.LayoutParams(-1, -2);
            gp.setMargins(dp(28), dp(8), dp(28), 0);
            content.addView(group, gp);
            LinearLayout head = row(); head.setPadding(dp(4), dp(5), dp(4), dp(5));
            View marker = new View(this); marker.setBackground(TvUi.shape(TvUi.accent(store), 3, this));
            LinearLayout.LayoutParams dot = new LinearLayout.LayoutParams(dp(3), dp(18)); dot.setMargins(0, 0, dp(10), 0); head.addView(marker, dot);
            TextView name = text(provider, 13, INK);
            name.setTypeface(null, Typeface.BOLD);
            head.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
            state = text(status, 11, MUTED);
            head.addView(state);
            group.addView(head);
            providerRows.put(provider, group);
            group.setVisibility(providerFilter[0].equals("All") || providerFilter[0].equals(provider) ? View.VISIBLE : View.GONE);
            providerStatuses.put(provider, state);
          }
          state.setText(status.equals("1 releases") ? "1 release" : status);
          final View anchor = hasWindowFocus() ? getCurrentFocus() : null;
          final int[] anchorPosition = new int[2];
          if (anchor != null) anchor.getLocationOnScreen(anchorPosition);
          for (JSONObject release : releases) {
            String id =
                provider
                    + ":"
                    + release.optString("id", release.optString("url", Models.title(release)));
            if (!releaseIds.add(id)) continue;
            group.setBackgroundColor(android.graphics.Color.TRANSPARENT);
            group.setPadding(0, dp(8), 0, dp(6));
            View result = BrowseComponents.source(this, release, provider,
                () -> load(
                    () -> new SourceDiscovery(this, store).resolve(release),
                    resolved -> {
                      try { resolved.put("provider", provider); } catch (Exception ignored) { }
                      playTitle(resolved, item, season, episode);
                    }));
            group.addView(result, new LinearLayout.LayoutParams(-1, dp(64)));
          }
          if (anchor != null) content.post(() -> {
            if (ticket != generation || anchor != getCurrentFocus() || !anchor.isAttachedToWindow()) return;
            int[] position = new int[2]; anchor.getLocationOnScreen(position);
            scroll.scrollBy(0, position[1] - anchorPosition[1]);
          });
        });
  }

  void playTitle(JSONObject source, JSONObject title, int season, int episode) {
    try {
      JSONObject p = new JSONObject(source.toString());
      p.put("name", Models.title(title) + (season > 0 ? " · S" + season + " E" + episode : ""));
      p.put("catalog", title);
      p.put("season", season);
      p.put("episode", episode);
      p.put("progressKey", Models.key(title) + ":" + season + ":" + episode);
      JSONObject metadata = episodeMetadata.get(Store.progressKey(p));
      if (metadata != null) { p.put("episodeTitle", metadata.optString("name")); p.put("episodeStill", metadata.optString("still_path")); }
      if (!p.optString("provider").isEmpty()) store.put("source." + PlaybackPrefs.titleKey(p), p.optString("provider"));
      play(p);
    } catch (Exception e) {
      error(e);
    }
  }

  void resume(JSONObject item) {
    if (item.optJSONObject("catalog") != null)
      sources(item.optJSONObject("catalog"), item.optInt("season"), item.optInt("episode"));
    else if (item.has("url")) play(item);
    else detail(item);
  }

  void continueMenu(JSONObject item) {
    TvUi.sheet(this, Models.title(item), "Continue watching",
        new String[] {"Resume", "Show details", "Mark watched", "Remove from Continue watching"}, -1, i -> {
          if (i == 0) resume(item);
          if (i == 1) detail(item.optJSONObject("catalog"));
          if (i == 2) {
            if (Store.episode(item)) {
              store.finished(item, null);
              EpisodeNavigator.nextUnwatched(api, store, item, (next, error) -> {
                if (next != null) store.promote(next);
                home(current);
              });
            } else {
              store.finished(item, null);
              home(current);
            }
          }
          if (i == 3) { store.removeContinueWatching(item); home(current); }
        });
  }

  void search() {
    eyebrow("A WORLD OF STORIES");
    heading("What are you looking for?", "Search movies, series and the people who make them.");
    LinearLayout bar = row();
    bar.setPadding(dp(28), dp(5), dp(28), dp(20));
    EditText field = input("Titles, actors, directors…", false);
    bar.addView(field, new LinearLayout.LayoutParams(0, dp(48), 1));
    TextView go =
        TvUi.button(this, "Search", true, () -> searchResults(field.getText().toString()));
    LinearLayout.LayoutParams gp = new LinearLayout.LayoutParams(dp(100), dp(48));
    gp.setMargins(dp(12), 0, 0, 0);
    bar.addView(go, gp);
    content.addView(bar);
    field.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
    field.setOnEditorActionListener(
        (v, action, event) -> {
          if (action == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) {
            hideKeyboard(field);
            searchResults(field.getText().toString());
            return true;
          }
          return false;
        });
    load(
        () -> api.catalog("trending/all/day", "movie"),
        r -> shelf("Popular searches", r, this::detail, true));
  }

  void searchResults(String query) {
    if (query.isBlank()) return;
    push(
        () -> {
          shell("Search");
          eyebrow("SEARCH RESULTS");
          heading(query, "Movies, series and people");
          load(
              () -> {
                List<JSONObject> r =
                    Models.list(
                        api.tmdb("search/multi?query=" + Api.enc(query)).optJSONArray("results"));
                for (JSONObject o : r) o.put("kind", o.optString("media_type", "movie"));
                return r;
              },
              r -> {
                List<JSONObject> titles = new ArrayList<>(), people = new ArrayList<>();
                for (JSONObject o : r)
                  if (o.optString("kind").equals("person")) people.add(o);
                  else titles.add(o);
                if (!people.isEmpty()) choosePeople(people);
                grid(titles, this::detail, true);
                if (r.isEmpty()) note("No results. Try another title or name.");
              });
        });
  }

  void choosePeople(List<JSONObject> people) {
    LinearLayout r = row();
    r.setPadding(dp(28), 0, 0, dp(16));
    for (JSONObject person : people.subList(0, Math.min(4, people.size()))) {
      TextView b = TvUi.button(this, person.optString("name") + "  →", false, () -> person(person));
      LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, dp(38));
      p.setMargins(0, 0, dp(10), 0);
      r.addView(b, p);
    }
    content.addView(r);
  }

  EditText input(String hint, boolean secret) {
    EditText e = new EditText(this);
    e.setSingleLine(true);
    e.setTextColor(INK);
    e.setHintTextColor(MUTED);
    e.setHint(hint);
    e.setTextSize(17);
    e.setPadding(dp(16), dp(12), dp(16), dp(12));
    e.setBackground(shape(SURFACE, 12));
    e.setInputType(
        secret
            ? InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD
            : InputType.TYPE_CLASS_TEXT);
    return e;
  }

  void hideKeyboard(View v) {
    ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE))
        .hideSoftInputFromWindow(v.getWindowToken(), 0);
  }

  void prompt(String title, String value, Consumer<String> done) {
    EditText e = input(title, title.toLowerCase(Locale.ROOT).matches(".*(password|token|key).*"));
    e.setText(value);
    TvUi.panel(
        this,
        title,
        "Enter the details below.",
        e,
        "Save",
        () -> done.accept(e.getText().toString().trim()));
  }

  interface Label {
    String label(JSONObject o);
  }

  void choose(String title, List<JSONObject> items, Label label, Consumer<JSONObject> done) {
    if (items.isEmpty()) {
      TvUi.sheet(this, title, "No items available.", new String[] {"Back"}, -1, i -> {});
      return;
    }
    TvUi.sheet(
        this,
        title,
        "",
        items.stream().map(label::label).toArray(String[]::new),
        -1,
        i -> done.accept(items.get(i)));
  }

  void itemMenu(JSONObject item) {
    List<String> actions =
        new ArrayList<>(
            Arrays.asList(
                store.contains("favorites", item) ? "Remove favorite" : "Add favorite",
                "Hide / show channel",
                "Move channel to top",
                "Save offline",
                "Remove from this profile"));
    new AlertDialog.Builder(this)
        .setTitle(Models.title(item))
        .setItems(
            actions.toArray(new String[0]),
            (d, w) -> {
              if (w == 0) {
                if (store.contains("favorites", item)) store.remove("favorites", item);
                else store.upsert("favorites", item);
              }
              if (w == 1) {
                if (store.contains("hidden", item)) store.remove("hidden", item);
                else store.upsert("hidden", item);
              }
              if (w == 2) store.upsert("channelOrder", item);
              if (w == 3) {
                try {
                  Downloads.start(this, item);
                  Toast.makeText(this, "Download queued", Toast.LENGTH_SHORT).show();
                } catch (Exception e) {
                  error(e);
                }
                return;
              }
              if (w == 4) {
                store.remove("channels", item);
                store.remove("history", item);
                store.remove("downloads", item);
              }
              home(current);
            })
        .show();
  }

  void livePage(String filter) {
    heading("Live TV & radio", "Your channels. A little closer.");
    LinearLayout controls = row();
    controls.addView(
        button(
            "Search",
            () ->
                prompt(
                    "Channel search",
                    filter,
                    q -> {
                      shell("Live TV");
                      livePage(q);
                    })));
    controls.addView(
        button(
            "Groups",
            () -> {
              Set<String> groups = new TreeSet<>();
              for (JSONObject c : live) groups.add(c.optString("group"));
              new AlertDialog.Builder(this)
                  .setTitle("Channel groups")
                  .setItems(
                      groups.toArray(new String[0]),
                      (d, w) -> {
                        shell("Live TV");
                        livePage(groups.toArray(new String[0])[w]);
                      })
                  .show();
            }));
    controls.addView(button("Program guide", this::guide));
    controls.addView(button("＋ IPTV", this::importOptions));
    content.addView(controls);
    load(
        () -> {
          List<JSONObject> all = api.bundled(this);
          all.addAll(store.items("channels"));
          all.removeIf(o -> o.optBoolean("vod") || store.contains("hidden", o));
          List<JSONObject> order = store.items("channelOrder");
          Map<String, Integer> rank = new HashMap<>();
          for (int i = 0; i < order.size(); i++) rank.put(order.get(i).optString("id"), i);
          all.sort(
              Comparator.comparingInt(
                  o -> rank.getOrDefault(o.optString("id"), Integer.MAX_VALUE)));
          return all;
        },
        r -> {
          live = r;
          List<JSONObject> shown = new ArrayList<>();
          for (JSONObject c : r)
            if (filter.isEmpty()
                || (Models.title(c) + " " + c.optString("group"))
                    .toLowerCase()
                    .contains(filter.toLowerCase())) shown.add(c);
          if (filter.isEmpty()) shelf("Favorites", store.items("favorites"), this::play, false);
          grid(shown, this::play, false);
        });
  }

  void importOptions() {
    TvUi.sheet(
        this,
        "Connect IPTV",
        "Live channels and on-demand titles will join your library.",
        new String[] {
          "M3U playlist URL", "Import M3U file", "Xtream Codes account", "XMLTV guide URL"
        },
        -1,
        i -> {
          if (i == 0) prompt("M3U playlist URL", "https://", this::importPlaylist);
          if (i == 1) pick("playlist", "*/*");
          if (i == 2) xtream();
          if (i == 3)
            prompt(
                "XMLTV guide URL",
                store.get("guide", ""),
                url -> {
                  store.put("guide", url);
                  guide();
                });
        });
  }

  void importPlaylist(String url) {
    load(
        () -> {
          String text = Api.request(url, Collections.emptyMap(), null);
          List<JSONObject> channels = Models.m3u(text, url);
          if (channels.isEmpty())
            throw new Exception("This playlist contains no supported channels");
          store.mergeChannels(channels);
          String guide = Models.attr(text, "url-tvg", Models.attr(text, "x-tvg-url", ""));
          if (!guide.isEmpty()) store.put("guide", guide);
          return channels.size();
        },
        n -> {
          Toast.makeText(this, n + " channels imported", Toast.LENGTH_LONG).show();
          home("Live TV");
        });
  }

  void xtream() {
    form(
        "Xtream Codes",
        new String[] {"Server URL", "Username", "Password"},
        values ->
            load(
                () -> {
                  JSONObject account =
                      Models.json(
                          "id",
                          UUID.randomUUID().toString(),
                          "name",
                          values[1],
                          "url",
                          values[0].replaceAll("/+$", ""),
                          "username",
                          values[1],
                          "password",
                          values[2]);
                  Xtream client = new Xtream(account);
                  client.verify();
                  store.upsert("xtream", account);
                  store.mergeChannels(client.live());
                  return account;
                },
                a -> {
                  Toast.makeText(this, "Account connected", Toast.LENGTH_SHORT).show();
                  home("Live TV");
                }));
  }

  void guide() {
    String url = store.get("guide", "");
    if (url.isBlank()) {
      prompt(
          "XMLTV guide URL",
          "",
          v -> {
            store.put("guide", v);
            guide();
          });
      return;
    }
    push(
        () -> {
          shell("Live TV");
          heading("Program guide", "Now & next from your XMLTV guide");
          load(
              () -> Guide.parse(Api.request(url, Collections.emptyMap(), null)),
              programs -> {
                long now = System.currentTimeMillis();
                for (JSONObject c : live) {
                  List<Guide.Program> p = new ArrayList<>();
                  for (Guide.Program g : programs)
                    if (g.channel.equals(c.optString("tvgID")) && g.end > now) p.add(g);
                  p.sort(Comparator.comparingLong(x -> x.start));
                  if (!p.isEmpty()) {
                    Guide.Program n = p.get(0);
                    content.addView(
                        button(
                            Models.title(c)
                                + "  ·  "
                                + n.title
                                + "  "
                                + new java.text.SimpleDateFormat("HH:mm", Locale.getDefault())
                                    .format(new Date(n.start)),
                            () -> play(c)));
                    if (p.size() > 1) note("Next: " + p.get(1).title);
                  }
                }
              });
        });
  }

  void library() {
    Downloads.reconcile(this);
    heading("Your library", "Pick up where you left off.");
    shelf("Continue watching", store.continueWatching(), this::resume, false);
    shelf("Watchlist", store.items("watchlist"), this::detail, true);
    shelf("Watched", store.items("watched"), this::detail, true);
    shelf("Downloads", store.items("downloads"), this::play, false);
    List<JSONObject> vod = new ArrayList<>();
    for (JSONObject c : store.items("channels")) if (c.optBoolean("vod")) vod.add(c);
    shelf("IPTV on demand", vod, this::play, false);
    for (JSONObject a : store.items("xtream"))
      content.addView(button("Browse " + a.optString("name") + " VOD", () -> xtreamVod(a)));
    for (JSONObject s : store.items("servers"))
      content.addView(button(s.optString("name"), () -> serverBrowser(s, "")));
    content.addView(button("＋ Connect Jellyfin / Emby", this::serverLogin));
  }

  void xtreamVod(JSONObject account) {
    TvUi.sheet(
        this,
        "On demand",
        account.optString("name"),
        new String[] {"Movies", "Series"},
        -1,
        index ->
            push(
                () -> {
                  shell("Library");
                  eyebrow("YOUR IPTV LIBRARY");
                  heading(index == 0 ? "Movies" : "Series", account.optString("name"));
                  load(
                      () -> new Xtream(account).categories(index == 0 ? "vod" : "series"),
                      categories -> {
                        for (JSONObject category : categories)
                          settingRow(
                              category.optString("category_name"),
                              "Browse this collection",
                              "Open",
                              () ->
                                  load(
                                      () ->
                                          new Xtream(account)
                                              .items(
                                                  index == 0 ? "vod" : "series",
                                                  category.optString("category_id")),
                                      items ->
                                          push(
                                              () -> {
                                                shell("Library");
                                                heading(
                                                    category.optString("category_name"),
                                                    account.optString("name"));
                                                xtreamGrid(account, items, index == 0, 0);
                                              })));
                      });
                }));
  }

  void xtreamGrid(JSONObject account, List<JSONObject> items, boolean movies, int start) {
    int end = Math.min(items.size(), start + 60);
    List<JSONObject> cards = new ArrayList<>();
    for (JSONObject item : items.subList(start, end))
      try {
        item.put("logo", item.optString("stream_icon", item.optString("cover")));
        cards.add(item);
      } catch (Exception ignored) {
      }
    grid(
        cards,
        item -> {
          if (movies) play(new Xtream(account).movie(item));
          else
            load(
                () -> new Xtream(account).episodes(item.optInt("series_id")),
                episodes -> choose("Episodes", episodes, Models::title, this::play));
        },
        true);
    if (end < items.size())
      content.addView(button("Load more titles", () -> xtreamGrid(account, items, movies, end)));
  }

  void serverLogin() {
    form(
        "Connect Jellyfin / Emby",
        new String[] {"Server URL", "Username", "Password"},
        v ->
            load(
                () -> api.serverSignIn(v[0], v[1], v[2], "Jellyfin / Emby"),
                s -> serverBrowser(s, "")));
  }

  void serverBrowser(JSONObject server, String parent) {
    push(
        () -> {
          shell("Library");
          heading(server.optString("name"), "Personal media library");
          content.addView(
              button(
                  "Search library",
                  () ->
                      prompt(
                          "Search",
                          "",
                          q ->
                              load(
                                  () -> api.serverItems(server, "", q),
                                  r -> serverRows(server, r)))));
          load(() -> api.serverItems(server, parent, ""), r -> serverRows(server, r));
        });
  }

  void serverRows(JSONObject server, List<JSONObject> items) {
    for (JSONObject item : items)
      try {
        item.put(
            "logo",
            server.optString("url")
                + "/Items/"
                + item.optString("Id")
                + "/Images/Primary?maxWidth=400&api_key="
                + Api.enc(store.get("serverToken." + server.optString("id"), "")));
        item.put("description", item.optString("Type"));
      } catch (Exception ignored) {
      }
    grid(
        items,
        item -> {
          if (item.optBoolean("IsFolder")
              || Arrays.asList("Series", "Season", "Folder", "CollectionFolder", "BoxSet")
                  .contains(item.optString("Type"))) serverBrowser(server, item.optString("Id"));
          else load(() -> api.serverStream(server, item), this::play);
        },
        true);
  }

  void more() {
    eyebrow("MAKE YOURSELF AT HOME");
    heading("Your space", "Connections, preferences and everything that makes Velagram yours.");
    connections();
    LinearLayout tiles = row();
    tiles.setPadding(dp(28), dp(20), dp(28), dp(10));
    String[][] entries = {
      {"Playback & appearance", "Themes, languages and player defaults"},
      {"Profiles & library", "Your list, progress and preferences"},
      {"Storage & backup", "Files, downloads and data"}
    };
    for (int i = 0; i < entries.length; i++) {
      final int index = i;
      LinearLayout tile = column();
      tile.setPadding(dp(18), dp(20), dp(18), dp(20));
      tile.setBackground(TvUi.shape(TvUi.SURFACE, 12, this));
      tile.setFocusable(true);
      tile.setClickable(true);
      LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, dp(110), 1);
      tp.setMargins(0, 0, dp(12), 0);
      tiles.addView(tile, tp);
      TextView title = text(entries[i][0], 16, INK);
      title.setTypeface(null, Typeface.BOLD);
      tile.addView(title);
      TextView desc = text(entries[i][1], 12, MUTED);
      desc.setPadding(0, dp(10), 0, 0);
      tile.addView(desc);
      tile.setOnFocusChangeListener(
          (v, f) -> v.setBackground(TvUi.shape(f ? 0xff303b50 : TvUi.SURFACE, 12, this)));
      tile.setOnClickListener(
          v -> {
            if (index == 0) settings();
            if (index == 1) profiles();
            if (index == 2) storage();
          });
    }
    content.addView(tiles);
    LinearLayout actions = row();
    actions.addView(
        button(
            "Open direct URL",
            () ->
                prompt(
                    "Stream URL",
                    "https://",
                    url -> play(Models.json("name", "Direct stream", "url", url)))));
    actions.addView(button("Import local media", () -> pick("media", "*/*")));
    actions.addView(button("Backup & restore", this::backupMenu));
    actions.addView(button("About Velagram", this::about));
    content.addView(actions);
  }

  void about() {
    TvUi.sheet(
        this,
        "Velagram TV",
        "A personal home for your movies, series and live television.",
        new String[] {"Version " + BuildConfig.VERSION_NAME, "Open-source notices"},
        -1,
        i -> {
          if (i == 1)
            push(
                () -> {
                  shell("More");
                  heading("Open-source notices", "");
                  load(
                      () ->
                          new String(
                              Streams.read(getAssets().open("THIRD_PARTY.txt"), 1024 * 1024),
                              java.nio.charset.StandardCharsets.UTF_8),
                      this::note);
                });
        });
  }

  void connections() {
    TextView h = text("Connected libraries", 19, INK);
    h.setPadding(dp(28), dp(20), 0, dp(14));
    h.setTypeface(null, Typeface.BOLD);
    content.addView(h);
    LinearLayout cards = row();
    cards.setPadding(dp(28), 0, dp(28), 0);
    boolean telegramReady = false;
    try {
      telegramReady = Telegram.get(this).ready();
    } catch (Throwable ignored) {
    }
    String[] names = {"Telegram", "Jellyfin / Emby", "IPTV & Xtream"};
    String[] details = {
      telegramReady ? "Connected · your chats are searchable" : "Link your account with a QR code",
      store.items("servers").isEmpty()
          ? "Bring your personal media library"
          : store.items("servers").size() + " server connections",
      store.items("channels").isEmpty() && store.items("xtream").isEmpty()
          ? "Live channels, movies and series"
          : store.items("channels").size() + " imported channels"
    };
    for (int i = 0; i < 3; i++) {
      final int index = i;
      LinearLayout card = column();
      card.setPadding(dp(18), dp(20), dp(18), dp(20));
      card.setBackground(TvUi.shape(0xff171e2b, 12, this));
      card.setFocusable(true);
      card.setClickable(true);
      LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(0, dp(133), 1);
      cp.setMargins(0, 0, dp(12), 0);
      cards.addView(card, cp);
      TextView label = text(names[i], 19, INK);
      label.setTypeface(null, Typeface.BOLD);
      card.addView(label);
      TextView sub = text(details[i], 12, MUTED);
      sub.setPadding(0, dp(12), 0, dp(12));
      card.addView(sub);
      TextView cta =
          text(
              (i == 0 && telegramReady
                      || i == 1 && !store.items("servers").isEmpty()
                      || i == 2 && !store.items("channels").isEmpty())
                  ? "Manage  →"
                  : "Connect  →",
              12,
              TvUi.accent(store));
      card.addView(cta);
      card.setOnFocusChangeListener(
          (v, f) -> {
            v.setBackground(TvUi.shape(f ? 0xff33425c : 0xff171e2b, 12, this));
          });
      card.setOnClickListener(
          v -> {
            if (index == 0) home("Telegram");
            if (index == 1) serverConnections();
            if (index == 2) importOptions();
          });
    }
    content.addView(cards);
  }

  void serverConnections() {
    if (store.items("servers").isEmpty()) {
      serverLogin();
      return;
    }
    List<JSONObject> servers = new ArrayList<>(store.items("servers"));
    servers.add(Models.json("name", "＋ Add a server", "new", true));
    choose(
        "Media servers",
        servers,
        Models::title,
        s -> {
          if (s.optBoolean("new")) serverLogin();
          else
            TvUi.sheet(
                this,
                s.optString("name"),
                s.optString("url"),
                new String[] {"Browse library", "Disconnect"},
                -1,
                i -> {
                  if (i == 0) serverBrowser(s, "");
                  else {
                    store.remove("servers", s);
                    store.put("serverToken." + s.optString("id"), "");
                    more();
                  }
                });
        });
  }

  void profiles() {
    List<String> profiles = new ArrayList<>();
    try {
      JSONArray array = new JSONArray(store.get("profiles", "[\"Me\"]"));
      for (int i = 0; i < array.length(); i++) profiles.add(array.getString(i));
    } catch (Exception ignored) {
    }
    profiles.add("＋ New profile");
    TvUi.sheet(
        this,
        "Who’s watching?",
        "Your progress and libraries, just for you.",
        profiles.toArray(new String[0]),
        profiles.indexOf(store.profile()),
        index -> {
          if (index == profiles.size() - 1)
            prompt(
                "Profile name",
                "",
                name -> {
                  if (!name.isBlank() && !profiles.contains(name)) {
                    profiles.remove(profiles.size() - 1);
                    profiles.add(name);
                    store.put("profiles", new JSONArray(profiles).toString());
                    store.put("profile", name);
                    home("Home");
                  }
                });
          else {
            store.put("profile", profiles.get(index));
            home("Home");
          }
        });
  }

  void settings() {
    push(() -> settingsPage("General"));
  }

  void settingsPage(String section) {
    currentRoute = () -> settingsPage(section);
    shell("More");
    eyebrow("TAILORED TO YOU");
    heading("Settings", "Make Velagram feel like home.");
    LinearLayout tabs = row();
    tabs.setPadding(dp(28), 0, 0, dp(18));
    for (String label :
        new String[] {"General", "Player", "Languages", "Subtitles", "Sources", "Connections"}) {
      TextView tab = TvUi.button(this, label, label.equals(section), () -> settingsPage(label));
      tab.setTextSize(12);
      LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-2, dp(35));
      tp.setMargins(0, 0, dp(7), 0);
      tabs.addView(tab, tp);
    }
    content.addView(tabs);
    switch (section) {
      case "General":
        settingRow("App updates", "Installed " + BuildConfig.VERSION_NAME + " · GitHub releases", "Check for updates", () -> updater.check(true));
        settingRow(
            "Appearance",
            "All eleven iOS accent themes. Graphite surfaces stay consistent.",
            store.get("theme", "Blue"),
            this::themes);
        settingRow(
            "Catalog language",
            "Titles, descriptions and search results.",
            languageLabel(store.get("language", "en-US")),
            () -> languageChoice("language", true, () -> settingsPage(section)));
        settingRow(
            "Profiles",
            "Separate library and live-TV preferences.",
            store.profile(),
            this::profiles);
        settingRow(
            "Cache & storage", "Retention, limits and saved downloads.", "Manage", this::storage);
        settingRow(
            "Backup & restore",
            "Progress, settings and library metadata.",
            "Manage",
            this::backupMenu);
        break;
      case "Player":
        settingChoice(
            "Playback engine",
            "Native streaming or VideoLAN for broad codec support.",
            "engine",
            new String[] {"Automatic", "Media3", "VLC"},
            "Automatic",
            section);
        settingToggle(
            "Automatically play next episode",
            "Searches sources and continues across seasons.",
            "autoNext",
            true,
            section);
        settingChoice(
            "Predictive preloading",
            "Prepares the next episode while you watch.",
            "preloadPolicy",
            new String[] {"Off", "Wi-Fi Only", "Wi-Fi & Cellular"},
            "Wi-Fi Only",
            section);
        settingChoice(
            "Default quality",
            "Uses the closest available lower resolution.",
            "qualityHeight",
            new String[] {"0", "2160", "1080", "720", "480"},
            "0",
            section);
        settingChoice(
            "Default playback speed",
            "Overridden by the speed you save for each title.",
            "speed",
            new String[] {"0.5", "0.75", "1.0", "1.25", "1.5", "1.75", "2.0"},
            "1.0",
            section);
        settingChoice(
            "Remote seek interval",
            "D-pad and skip controls.",
            "seekSeconds",
            new String[] {"5", "10", "15", "30", "60"},
            "10",
            section);
        settingToggle(
            "Background audio",
            "Continue in the primary player when leaving the screen.",
            "backgroundAudio",
            false,
            section);
        break;
      case "Languages":
        for (String[] item :
            new String[][] {
              {"Default audio", "audioLanguage", "en"},
              {"Anime audio", "animeAudio", "en"},
              {"Backup anime audio", "animeBackup", "ja"},
              {"Default subtitles", "subtitleLanguage", "en"},
              {"Backup subtitles", "subtitleBackup", ""}
            })
          settingRow(
              item[0],
              "Applied when a matching track is available.",
              languageLabel(store.get(item[1], item[2])),
              () -> languageChoice(item[1], false, () -> settingsPage(section)));
        settingToggle(
            "Subtitles on by default",
            "You can change this for the current title in the player.",
            "subtitlesEnabled",
            true,
            section);
        settingChoice(
            "Telegram search language",
            "Searches preferred title aliases first.",
            "searchLanguage",
            new String[] {"English first", "Hebrew first"},
            "English first",
            section);
        break;
      case "Subtitles":
        settingChoice(
            "Subtitle loading",
            "Fast searches preferred language; comprehensive includes backup.",
            "subtitleMode",
            new String[] {"Fast", "Comprehensive"},
            "Fast",
            section);
        for (String[] provider :
            new String[][] {
              {"SubDL", "subdl"},
              {"Wizdom", "wizdom"},
              {"Ktuvit", "ktuvit"},
              {"External add-on", "addon"}
            })
          settingToggle(
              provider[0],
              "Include this service when finding online subtitles.",
              "subtitle.provider." + provider[1],
              true,
              section);
        settingToggle(
            "Use latest saved sync automatically",
            "Restores saved timing for the same episode and subtitle release.",
            "subtitleAutoSync",
            true,
            section);
        settingRow(
            "Caption appearance",
            "Use the device’s accessibility caption preferences.",
            "System",
            () -> {
              try {
                startActivity(new Intent("android.settings.CAPTIONING_SETTINGS"));
              } catch (Exception e) {
                error(e);
              }
            });
        settingRow(
            "SubDL API key",
            "Stored securely on this device.",
            "Configured",
            () -> prompt("SubDL API key", "", v -> store.put("secret.subdl", v)));
        break;
      case "Sources":
        for (String[] provider :
            new String[][] {
              {"Telegram", "telegram"},
              {"StreamingCommunity", "community"},
              {"AnimeIL", "animeil"},
              {"HiAnime", "hianime"},
              {"Anikoto", "anikoto"},
              {"HTTP add-on", "addon"}
            })
          settingToggle(
              provider[0],
              "Search automatically when opening Sources.",
              "source." + provider[1],
              true,
              section);
        settingRow(
            "HTTP add-on manifest",
            "Stremio-compatible HTTP streams and subtitles.",
            store.get("addon", "").isBlank() ? "Not connected" : "Connected",
            () -> prompt("Manifest URL", store.get("addon", ""), v -> store.put("addon", v)));
        settingRow(
            "TMDB access token",
            "Catalog configuration copied from your iOS project.",
            "Configured",
            () -> prompt("TMDB access token", "", v -> store.put("secret.tmdb", v)));
        break;
      default:
        connections();
        content.addView(
            button(
                "Manage hidden channels",
                () ->
                    choose(
                        "Hidden channels",
                        store.items("hidden"),
                        Models::title,
                        c -> {
                          store.remove("hidden", c);
                          settingsPage(section);
                        })));
        break;
    }
  }

  void settingRow(String title, String description, String value, Runnable action) {
    LinearLayout row = row();
    row.setPadding(dp(17), dp(14), dp(18), dp(14));
    row.setBackground(TvUi.shape(TvUi.SURFACE, 9, this));
    row.setFocusable(true);
    row.setClickable(true);
    LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1, -2);
    rp.setMargins(dp(28), 0, dp(28), dp(6));
    content.addView(row, rp);
    LinearLayout copy = column();
    TextView label = text(title, 15, INK);
    label.setTypeface(null, Typeface.BOLD);
    copy.addView(label);
    TextView desc = text(description, 11, MUTED);
    desc.setPadding(0, dp(6), 0, 0);
    copy.addView(desc);
    row.addView(copy, new LinearLayout.LayoutParams(0, -2, 1));
    TextView detail = text(value + "  ›", 13, 0xffbac4d8);
    detail.setPadding(dp(15), 0, 0, 0);
    row.addView(detail);
    row.setOnFocusChangeListener(
        (v, f) -> v.setBackground(TvUi.shape(f ? 0xff303c51 : TvUi.SURFACE, 9, this)));
    row.setOnClickListener(v -> action.run());
  }

  void settingToggle(
      String title, String description, String key, boolean fallback, String section) {
    boolean enabled = store.get(key, Boolean.toString(fallback)).equals("true");
    settingRow(
        title,
        description,
        enabled ? "On" : "Off",
        () -> {
          store.put(key, Boolean.toString(!enabled));
          settingsPage(section);
        });
  }

  void settingChoice(
      String title,
      String description,
      String key,
      String[] values,
      String fallback,
      String section) {
    String current = store.get(key, fallback);
    String display =
        key.equals("qualityHeight")
            ? (current.equals("0") ? "Auto" : current + "p")
            : key.equals("speed")
                ? current + "×"
                : key.equals("seekSeconds") ? current + " seconds" : current;
    settingRow(
        title,
        description,
        display,
        () ->
            TvUi.sheet(
                this,
                title,
                description,
                Arrays.stream(values)
                    .map(
                        v ->
                            key.equals("qualityHeight")
                                ? (v.equals("0") ? "Auto" : v + "p")
                                : key.equals("speed") ? v + "×" : v)
                    .toArray(String[]::new),
                Arrays.asList(values).indexOf(current),
                i -> {
                  store.put(key, values[i]);
                  settingsPage(section);
                }));
  }

  String languageLabel(String code) {
    if (code.isEmpty()) return "None";
    String c = code.split("-")[0];
    return new Locale(c).getDisplayLanguage(Locale.ENGLISH);
  }

  void languageChoice(String key, boolean catalog, Runnable refresh) {
    String[] codes =
        catalog
            ? new String[] {"en-US", "he-IL", "ja-JP", "es-ES", "fr-FR", "de-DE", "ru-RU", "ar-SA"}
            : new String[] {"en", "he", "ja", "es", "fr", "de", "ru", "ar", ""};
    TvUi.sheet(
        this,
        "Choose language",
        "",
        Arrays.stream(codes).map(this::languageLabel).toArray(String[]::new),
        Arrays.asList(codes).indexOf(store.get(key, "en")),
        i -> {
          store.put(key, codes[i]);
          refresh.run();
        });
  }

  void themes() {
    push(
        () -> {
          shell("More");
          eyebrow("SAME SOUL. YOUR COLOUR.");
          heading("Appearance", "Choose an accent. Keep the cinema dark.");
          LinearLayout row = null;
          for (int i = 0; i < TvUi.THEMES.length; i++) {
            final int index = i;
            if (i % 4 == 0) {
              row = row();
              row.setPadding(dp(28), dp(10), 0, dp(8));
              content.addView(row);
            }
            LinearLayout swatch = column();
            swatch.setGravity(Gravity.CENTER);
            swatch.setPadding(dp(12), dp(15), dp(12), dp(15));
            GradientDrawable background = TvUi.shape(0xff151a25, 12, this);
            if (store.get("theme", "Blue").equals(TvUi.THEMES[i]))
              background.setStroke(dp(2), TvUi.COLORS[i]);
            swatch.setBackground(background);
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(dp(175), dp(100));
            p.setMargins(0, 0, dp(13), 0);
            row.addView(swatch, p);
            View color = new View(this);
            color.setBackground(TvUi.shape(TvUi.COLORS[i], 30, this));
            swatch.addView(color, new LinearLayout.LayoutParams(dp(32), dp(32)));
            TextView name = text(TvUi.THEMES[i], 13, INK);
            name.setPadding(0, dp(10), 0, 0);
            name.setGravity(Gravity.CENTER);
            swatch.addView(name);
            swatch.setFocusable(true);
            swatch.setClickable(true);
            swatch.setOnFocusChangeListener(
                (v, f) ->
                    v.animate()
                        .scaleX(f ? 1.05f : 1)
                        .scaleY(f ? 1.05f : 1)
                        .setDuration(120)
                        .start());
            swatch.setOnClickListener(
                v -> {
                  store.put("theme", TvUi.THEMES[index]);
                  themes();
                });
          }
        });
  }

  void storage() {
    push(
        () -> {
          shell("More");
          heading("Storage", "");
          content.addView(
              button(
                  "Temporary cache limit · " + store.get("cacheMB", "500") + " MB",
                  () ->
                      new AlertDialog.Builder(this)
                          .setTitle("Cache limit (MB)")
                          .setItems(
                              new String[] {"100", "250", "500", "1000", "0"},
                              (d, w) -> {
                                store.put(
                                    "cacheMB", new String[] {"100", "250", "500", "1000", "0"}[w]);
                                storage();
                              })
                          .show()));
          content.addView(
              button(
                  "Temporary cache retention · " + store.get("cacheDays", "30") + " days",
                  () ->
                      new AlertDialog.Builder(this)
                          .setTitle("Retention (0 = no expiry)")
                          .setItems(
                              new String[] {"7", "14", "30", "90", "0"},
                              (d, w) -> {
                                store.put(
                                    "cacheDays", new String[] {"7", "14", "30", "90", "0"}[w]);
                                storage();
                              })
                          .show()));
          long bytes = folderSize(getCacheDir());
          note("Artwork and temporary cache: " + (bytes / 1048576) + " MB");
          note(
              "Telegram media: "
                  + (folderSize(new java.io.File(getFilesDir(), "telegram/files")) / 1048576)
                  + " MB");
          content.addView(
              button(
                  "Clear temporary cache",
                  () -> {
                    if (PlaybackService.activeSession != null) { Toast.makeText(this, "Try again after playback", Toast.LENGTH_SHORT).show(); return; }
                    Api.IO.execute(() -> {
                      CachePolicy.trim(getCacheDir(), 1, 0);
                      runOnUiThread(() -> Toast.makeText(this, "Artwork cache cleared", Toast.LENGTH_SHORT).show());
                    });
                  }));
          note("Free space: " + getFilesDir().getUsableSpace() / 1048576 + " MB · streamed media expires after 24 hours when idle. Saved downloads are kept.");
          content.addView(button("Clear temporary Telegram streams", () -> {
            Telegram telegram = Telegram.get(this);
            if (!telegram.ready()) { Toast.makeText(this, "Connect Telegram to manage its temporary files", Toast.LENGTH_LONG).show(); return; }
            TemporaryMedia.trim(telegram, true, message -> Toast.makeText(this, message, Toast.LENGTH_LONG).show());
          }));
          content.addView(
              button(
                  "Manage saved downloads",
                  () ->
                      choose(
                          "Saved downloads",
                          store.items("downloads"),
                          Models::title,
                          item ->
                              new AlertDialog.Builder(this)
                                  .setTitle("Delete saved file?")
                                  .setMessage(Models.title(item))
                                  .setNegativeButton("Cancel", null)
                                  .setPositiveButton(
                                      "Delete",
                                      (d, w) -> {
                                        String u = item.optString("url");
                                        if (u.startsWith("file:"))
                                          new java.io.File(android.net.Uri.parse(u).getPath())
                                              .delete();
                                        store.remove("downloads", item);
                                      })
                                  .show())));
        });
  }

  long folderSize(java.io.File f) {
    if (f.isFile()) return f.length();
    long n = 0;
    java.io.File[] a = f.listFiles();
    if (a != null) for (java.io.File c : a) n += folderSize(c);
    return n;
  }

  void delete(java.io.File f) {
    java.io.File[] a = f.listFiles();
    if (a != null) for (java.io.File c : a) delete(c);
    f.delete();
  }

  void backupMenu() {
    new AlertDialog.Builder(this)
        .setTitle("Backup & restore")
        .setItems(
            new String[] {"Export backup", "Restore backup"},
            (d, w) -> {
              if (w == 0) {
                pendingFile = "export";
                Intent i =
                    new Intent(Intent.ACTION_CREATE_DOCUMENT)
                        .setType("application/json")
                        .putExtra(Intent.EXTRA_TITLE, "velagram-tv-backup.json")
                        .addCategory(Intent.CATEGORY_OPENABLE);
                startActivityForResult(i, 42);
              } else pick("restore", "application/json");
            })
        .show();
  }

  void pick(String mode, String mime) {
    pendingFile = mode;
    try {
      startActivityForResult(
          new Intent(Intent.ACTION_OPEN_DOCUMENT)
              .setType(mime)
              .addCategory(Intent.CATEGORY_OPENABLE)
              .addFlags(
                  Intent.FLAG_GRANT_READ_URI_PERMISSION
                      | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION),
          42);
    } catch (Exception e) {
      error(
          new Exception(
              "No document picker is installed on this TV. Use network URLs or install a TV file"
                  + " manager."));
    }
  }

  @Override
  protected void onActivityResult(int request, int result, Intent data) {
    super.onActivityResult(request, result, data);
    if (request != 42 || result != RESULT_OK || data == null || data.getData() == null) return;
    android.net.Uri uri = data.getData();
    try {
      if (pendingFile.equals("export")) {
        try (java.io.OutputStream o = getContentResolver().openOutputStream(uri)) {
          o.write(store.backup().toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        Toast.makeText(this, "Backup exported", Toast.LENGTH_SHORT).show();
      } else if (pendingFile.equals("media")) {
        getContentResolver()
            .takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        play(Models.json("name", uri.getLastPathSegment(), "url", uri.toString()));
      } else {
        String text;
        try (java.io.InputStream in = getContentResolver().openInputStream(uri)) {
          text =
              new String(
                  Streams.read(in, 32 * 1024 * 1024), java.nio.charset.StandardCharsets.UTF_8);
        }
        if (pendingFile.equals("restore")) {
          final String backup = text;
          new AlertDialog.Builder(this)
              .setTitle("Replace local data?")
              .setMessage(
                  "This restores profiles, libraries and preferences. Sign in to servers and"
                      + " Telegram again on a new TV.")
              .setNegativeButton("Cancel", null)
              .setPositiveButton(
                  "Restore",
                  (d, w) -> {
                    try {
                      store.restore(backup);
                      home("Home");
                    } catch (Exception e) {
                      error(e);
                    }
                  })
              .show();
        } else {
          List<JSONObject> channels = Models.m3u(text, "https://localhost/");
          store.mergeChannels(channels);
          home("Live TV");
        }
      }
    } catch (Exception e) {
      error(e);
    }
  }

  void form(String title, String[] labels, Consumer<String[]> done) {
    LinearLayout fields = column();
    List<EditText> inputs = new ArrayList<>();
    for (String label : labels) {
      TextView caption = text(label, 11, MUTED);
      caption.setPadding(0, dp(8), 0, dp(6));
      fields.addView(caption);
      EditText e = input(label, label.equals("Password"));
      if (label.contains("number")) e.setInputType(InputType.TYPE_CLASS_NUMBER);
      fields.addView(e, new LinearLayout.LayoutParams(-1, dp(44)));
      inputs.add(e);
    }
    TvUi.panel(
        this,
        title,
        title.toLowerCase(Locale.ROOT).contains("episode")
            ? "Choose where you want to start."
            : "Your connection stays on this device.",
        fields,
        title.contains("episode") ? "Find sources" : "Connect",
        () ->
            done.accept(
                inputs.stream().map(v -> v.getText().toString().trim()).toArray(String[]::new)));
  }

  void telegram() {
    eyebrow("YOUR PERSONAL MEDIA. ON THE BIG SCREEN.");
    heading("Telegram", "Your chats become part of your Velagram library.");
    try {
      tg = Telegram.get(this);
      tg.listener =
          status -> {
            if (current.equals("Telegram")) {
              shell("Telegram");
              telegram();
            }
          };
      if (!tg.ready()) {
        LinearLayout panel = row();
        panel.setPadding(dp(26), dp(25), dp(26), dp(25));
        panel.setBackground(TvUi.shape(TvUi.SURFACE, 16, this));
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(-1, dp(290));
        pp.setMargins(dp(28), dp(8), dp(28), dp(18));
        content.addView(panel, pp);
        LinearLayout instructions = column();
        panel.addView(instructions, new LinearLayout.LayoutParams(0, -2, 1));
        TextView title = text("Scan. Connect. Watch.", 28, INK);
        title.setTypeface(null, Typeface.BOLD);
        instructions.addView(title);
        for (String step :
            new String[] {
              "1   Open Telegram on your phone",
              "2   Go to Settings → Devices",
              "3   Tap Link Desktop Device and scan"
            }) {
          TextView t = text(step, 13, MUTED);
          t.setPadding(0, dp(18), 0, 0);
          instructions.addView(t);
        }
        TextView privacy = text("Your session stays on this TV.", 11, TvUi.accent(store));
        privacy.setPadding(0, dp(23), 0, 0);
        instructions.addView(privacy);
        LinearLayout qrBox = column();
        qrBox.setGravity(Gravity.CENTER);
        panel.addView(qrBox, new LinearLayout.LayoutParams(dp(230), -1));
        if (!tg.qr.isEmpty()) {
          ImageView image = new ImageView(this);
          image.setPadding(dp(10), dp(10), dp(10), dp(10));
          image.setBackground(TvUi.shape(0xffffffff, 12, this));
          qrBox.addView(image, new LinearLayout.LayoutParams(dp(205), dp(205)));
          try {
            com.google.zxing.common.BitMatrix bits =
                new com.google.zxing.qrcode.QRCodeWriter()
                    .encode(tg.qr, com.google.zxing.BarcodeFormat.QR_CODE, 420, 420);
            android.graphics.Bitmap bitmap =
                android.graphics.Bitmap.createBitmap(
                    420, 420, android.graphics.Bitmap.Config.RGB_565);
            for (int y = 0; y < 420; y++)
              for (int x = 0; x < 420; x++)
                bitmap.setPixel(x, y, bits.get(x, y) ? 0xff080a10 : 0xffffffff);
            image.setImageBitmap(bitmap);
          } catch (Exception ignored) {
          }
        } else {
          ProgressBar spinner = new ProgressBar(this);
          spinner.setIndeterminateTintList(
              android.content.res.ColorStateList.valueOf(TvUi.accent(store)));
          qrBox.addView(spinner, new LinearLayout.LayoutParams(dp(40), dp(40)));
          TextView state = text("Preparing secure link…", 12, MUTED);
          state.setPadding(0, dp(18), 0, 0);
          qrBox.addView(state);
        }
        if (!qrRequested && tg.state instanceof TdApi.AuthorizationStateWaitPhoneNumber) {
          qrRequested = true;
          tg.qr();
        }
        LinearLayout alternatives = row();
        alternatives.addView(
            button(
                "Use phone number instead",
                () ->
                    prompt("Phone number, including country code", "", value -> tg.login(value))));
        alternatives.addView(button("Refresh QR code", tg::qr));
        content.addView(alternatives);
        if (tg.state instanceof TdApi.AuthorizationStateWaitEmailAddress
            || tg.state instanceof TdApi.AuthorizationStateWaitCode
            || tg.state instanceof TdApi.AuthorizationStateWaitPassword
            || tg.state instanceof TdApi.AuthorizationStateWaitEmailCode)
          content.addView(button(tg.status, () -> prompt(tg.status, "", value -> tg.login(value))));
        return;
      }
      LinearLayout actions = row();
      actions.addView(
          button(
              "Open channel or invite link",
              () ->
                  prompt(
                      "Channel username or t.me link",
                      "",
                      value -> {
                        openTelegramLink(value);
                      })));
      actions.addView(
          button(
              "Log out",
              () ->
                  TvUi.sheet(
                      this,
                      "Log out of Telegram?",
                      "Your media library and playback progress stay on this TV.",
                      new String[] {"Keep connected", "Log out"},
                      -1,
                      i -> {
                        if (i == 1) {
                          qrRequested = false;
                          tg.send(new TdApi.LogOut(), o -> {});
                        }
                      })));
      content.addView(actions);
      content.addView(button("Search chats", () -> prompt("Find a chat", "", query ->
          tg.send(new TdApi.SearchChats(query, null, 100), result -> {
            if (result instanceof TdApi.Chats) showTelegramChatIds(((TdApi.Chats) result).chatIds);
          }))));
      if (tg.folders.length > 0)
        content.addView(
            button(
                "Chat folders",
                () ->
                    TvUi.sheet(
                        this,
                        "Chat folders",
                        "",
                        Arrays.stream(tg.folders).map(f -> f.name.text.text).toArray(String[]::new),
                        -1,
                        i ->
                            telegramChatList(new TdApi.ChatListFolder(tg.folders[i].id), tg.folders[i].name.text.text, 100))));
      shelf(
          "Pinned chats",
          store.items("telegramPins"),
          pin ->
              tg.send(
                  new TdApi.GetChat(pin.optLong("chat")),
                  result -> {
                    if (result instanceof TdApi.Chat) telegramChat((TdApi.Chat) result, "", 0);
                  }),
          false);
      int ticket = generation;
      tg.chats(
          chats -> {
            if (ticket != generation) return;
            for (TdApi.Chat chat : chats)
              settingRow(
                  chat.title,
                  "Videos, documents and media",
                  "Open",
                  () -> telegramChat(chat, "", 0));
            if (chats.size() >= 100) content.addView(button("More chats", () -> telegramChatList(new TdApi.ChatListMain(), "Chats", 200)));
          });
    } catch (Throwable e) {
      note("Telegram is currently unavailable. Try again or use another library.");
    }
  }

  void telegramChatList(TdApi.ChatList list, String name, int limit) {
    push(() -> {
      shell("Telegram"); heading(name, "Your Telegram chats"); int ticket = generation;
      tg.chats(list, limit, chats -> {
        if (ticket != generation) return;
        for (TdApi.Chat chat : chats) settingRow(chat.title, "Open media", "Open", () -> telegramChat(chat, "", 0));
        if (chats.size() >= limit) content.addView(button("More chats", () -> telegramChatList(list, name, limit + 100)));
      });
    });
  }

  void showTelegramChatIds(long[] ids) {
    List<JSONObject> rows = new ArrayList<>();
    for (long id : ids) rows.add(Models.json("id", id, "name", tg.chats.containsKey(id) ? tg.chats.get(id).title : Long.toString(id)));
    choose("Chats", rows, Models::title, row -> tg.send(new TdApi.GetChat(row.optLong("id")), result -> {
      if (result instanceof TdApi.Chat) telegramChat((TdApi.Chat) result, "", 0);
    }));
  }

  void openTelegramLink(String value) {
    if (value.contains("t.me/+") || value.contains("joinchat/")) {
      tg.send(new TdApi.CheckChatInviteLink(value), result -> {
        if (!(result instanceof TdApi.ChatInviteLinkInfo)) return;
        TdApi.ChatInviteLinkInfo info = (TdApi.ChatInviteLinkInfo) result;
        if (info.chatId != 0) tg.send(new TdApi.GetChat(info.chatId), chat -> {
          if (chat instanceof TdApi.Chat) telegramChat((TdApi.Chat) chat, "", 0);
        });
        else TvUi.sheet(this, info.title, "Join this group to access its media.", new String[] {"Join group", "Cancel"}, -1, i -> {
          if (i == 0) tg.send(new TdApi.JoinChatByInviteLink(value), chat -> {
            if (chat instanceof TdApi.Chat) telegramChat((TdApi.Chat) chat, "", 0);
          });
        });
      });
    } else tg.send(new TdApi.SearchPublicChat(value.trim().replaceFirst("^https?://t\\.me/", "").replaceAll("^@|/+$", "")), chat -> {
      if (chat instanceof TdApi.Chat) telegramChat((TdApi.Chat) chat, "", 0);
    });
  }

  void telegramChat(TdApi.Chat chat, String query, long from) {
    telegramChat(chat, query, from, 0);
  }

  void telegramChat(TdApi.Chat chat, String query, long from, int topic) {
    Map<String, Long> cursors = new LinkedHashMap<>();
    for (String filter : TelegramMediaPage.FILTERS) cursors.put(filter, from);
    telegramChat(chat, query, from, topic, cursors);
  }

  void telegramChat(TdApi.Chat chat, String query, long from, int topic, Map<String, Long> cursors) {
    push(
        () -> {
          shell("Telegram");
          heading(chat.title, query.isEmpty() ? "Video, audio and media links" : query);
          content.addView(
              button(
                  store.items("telegramPins").stream().anyMatch(pin -> pin.optLong("chat") == chat.id) ? "Unpin chat" : "Pin chat",
                  () -> {
                    JSONObject pin = Models.json("id", chat.id, "chat", chat.id, "name", chat.title);
                    if (store.contains("telegramPins", pin)) store.remove("telegramPins", pin); else store.upsert("telegramPins", pin);
                    Toast.makeText(this, "Pinned chats updated", Toast.LENGTH_SHORT).show();
                  }));
          content.addView(
              button(
                  "Forum topics",
                  () ->
                      tg.topics(
                          chat.id,
                          topics ->
                              choose(
                                  "Topics",
                                  topics,
                                  Models::title,
                                  t -> telegramChat(chat, query, 0, t.optInt("id"))))));
          content.addView(
              button(
                  "Sort & filter",
                  () ->
                      new AlertDialog.Builder(this)
                          .setTitle("Media order")
                          .setItems(
                              new String[] {
                                "Newest", "Oldest", "Name", "Largest", "Movies", "Series"
                              },
                              (d, w) -> {
                                store.put("telegramSort", Integer.toString(w));
                                telegramChat(chat, query, from, topic);
                              })
                          .show()));
          content.addView(
              button(
                  "Search media",
                  () -> prompt("Search this channel", query, q -> telegramChat(chat, q, 0))));
          int ticket = generation;
          TelegramMediaPage.load(tg::send,
              chat.id,
              query,
              topic,
              cursors,
              page -> {
                List<JSONObject> items = page.items;
                ReleaseGrouping.organize(items, Integer.parseInt(store.get("telegramSort", "0")));
                if (ticket != generation) return;
                grid(
                    items,
                    item ->
                        new AlertDialog.Builder(this)
                            .setTitle(Models.title(item))
                            .setItems(
                                new String[] {"Play now", "Save offline"},
                                (d, w) -> {
                                  if (w == 0) play(item);
                                  else {
                                    if (!item.has("file")) {
                                      try { Downloads.start(this, item); } catch (Exception e) { error(e); }
                                      return;
                                    }
                                    Toast.makeText(this, "Downloading…", Toast.LENGTH_SHORT).show();
                                    tg.download(
                                        item,
                                        s -> Toast.makeText(this, s, Toast.LENGTH_LONG).show());
                                  }
                                })
                            .show(),
                    false);
                if (page.hasMore())
                  content.addView(
                      button(
                          "Load older media",
                          () ->
                              telegramChat(
                                  chat,
                                  query,
                                  0,
                                  topic, page.next)));
                if (items.isEmpty()) note("No playable media found in this page.");
              });
        });
  }

  void telegramSource(JSONObject title, int season, int episode) {
    try {
      tg = Telegram.get(this);
      if (!tg.ready()) {
        home("Telegram");
        return;
      }
      tg.chats(
          chats -> {
            String[] names = chats.stream().map(c -> c.title).toArray(String[]::new);
            new AlertDialog.Builder(this)
                .setTitle("Search a Telegram channel")
                .setItems(
                    names,
                    (d, w) -> {
                      TdApi.Chat chat = chats.get(w);
                      String query = Models.title(title);
                      push(
                          () -> {
                            shell(current);
                            heading("Telegram sources", query);
                            tg.messages(
                                chat.id,
                                query,
                                0,
                                items -> {
                                  items.removeIf(
                                      file ->
                                          !ReleaseGrouping.matches(file, title, season, episode));
                                  for (JSONObject item : items)
                                    content.addView(
                                        button(
                                            Models.title(item),
                                            () -> playTitle(item, title, season, episode)));
                                  if (items.isEmpty())
                                    note("No matching media found in this channel.");
                                });
                          });
                    })
                .show();
          });
    } catch (Throwable e) {
      note("Telegram unavailable");
    }
  }

  void play(JSONObject item) {
    String u = item.optString("url");
    if (u.isBlank()) return;
    load(
        () -> api.resolve(item),
        resolved -> {
          if (!resolved.has("id"))
            try {
              resolved.put(
                  "id", "direct:" + Integer.toHexString(resolved.optString("url").hashCode()));
            } catch (Exception ignored) {
            }
          store.upsert("recent", resolved);
          Intent i =
              new Intent(
                  this,
                  PlaybackPrefs.useVlc(store, resolved) ? VlcActivity.class : PlayerActivity.class);
          i.putExtra("media", resolved.toString());
          refreshOnReturn = true;
          startActivity(i);
        });
  }

  @Override
  protected void onResume() {
    super.onResume();
    if (updater != null) updater.resume();
    if (refreshOnReturn && currentRoute != null) {
      refreshOnReturn = false;
      Downloads.reconcile(this);
      BrowseComponents.refresh(root);
      if (getCurrentFocus() != null) getCurrentFocus().requestFocus();
    }
  }

  @Override
  public void onBackPressed() {
    if (!back.isEmpty()) {
      currentRoute = back.pop();
      currentRoute.run();
    } else if (!current.equals("Home")) home("Home");
    else super.onBackPressed();
  }

  boolean inside(View child, View parent) {
    for (View node = child; node != null; node = node.getParent() instanceof View ? (View) node.getParent() : null)
      if (node == parent) return true;
    return false;
  }

  void focusStops(View node, List<View> stops) {
    if (node.getVisibility() != View.VISIBLE || !node.isEnabled() || Boolean.FALSE.equals(node.getTag(R.id.focus_stop))) return;
    if (node instanceof androidx.compose.ui.platform.ComposeView || node.isFocusable()) { stops.add(node); return; }
    if (node instanceof ViewGroup) for (int index = 0; index < ((ViewGroup) node).getChildCount(); index++) focusStops(((ViewGroup) node).getChildAt(index), stops);
  }

  android.graphics.Rect contentBounds(View view, boolean focused) {
    android.graphics.Rect rect = new android.graphics.Rect();
    if (focused) view.getFocusedRect(rect); else view.getDrawingRect(rect);
    content.offsetDescendantRectToMyCoords(view, rect);
    return rect;
  }

  // Every Compose island is one vertical stop; horizontal movement remains within its real row.
  // Explicit entry avoids ScrollView consuming Down merely as a scroll while focus stays in the hero.
  boolean verticalFocus(int direction) {
    View focus = getCurrentFocus();
    if (focus != null && inside(focus, nav)) return false;
    List<View> stops = new ArrayList<>(); focusStops(content, stops);
    boolean composeStops = false;
    for (View stop : stops) if (stop instanceof androidx.compose.ui.platform.ComposeView) composeStops = true;
    if (!composeStops) return false;
    View currentStop = null;
    for (View stop : stops) if (focus != null && inside(focus, stop)) { currentStop = stop; break; }
    if (currentStop == null && lastContentFocus != null && inside(lastContentFocus, content)) currentStop = lastContentFocus;
    if (currentStop == null) return false;
    android.graphics.Rect origin = focus != null && inside(focus, currentStop) ? contentBounds(focus, true) : contentBounds(currentStop, false);
    View next = null; long best = Long.MAX_VALUE;
    for (View stop : stops) {
      if (stop == currentStop) continue;
      android.graphics.Rect rect = contentBounds(stop, false);
      int delta = rect.centerY() - origin.centerY();
      if (direction == View.FOCUS_DOWN ? delta <= dp(6) : delta >= -dp(6)) continue;
      int gap = direction == View.FOCUS_DOWN ? Math.max(0, rect.top - origin.bottom) : Math.max(0, origin.top - rect.bottom);
      long score = gap * 10000L + Math.abs(rect.centerX() - origin.centerX());
      if (score < best) { next = stop; best = score; }
    }
    if (next == null) return true;
    if (next.requestFocus()) {
      lastContentFocus = next;
      android.graphics.Rect rect = new android.graphics.Rect(); next.getDrawingRect(rect);
      next.requestRectangleOnScreen(rect, false);
      return true;
    }
    return false;
  }

  @android.annotation.SuppressLint("RestrictedApi")
  @Override public boolean dispatchKeyEvent(KeyEvent event) {
    if (content != null && event.getAction() == KeyEvent.ACTION_DOWN) {
      View focused = getCurrentFocus();
      List<View> stops = new ArrayList<>(); focusStops(content, stops);
      for (View stop : stops) if (focused != null && inside(focused, stop)) { lastContentFocus = stop; break; }
      int key = event.getKeyCode();
      if ((key == KeyEvent.KEYCODE_DPAD_DOWN || key == KeyEvent.KEYCODE_DPAD_UP)
          && verticalFocus(key == KeyEvent.KEYCODE_DPAD_DOWN ? View.FOCUS_DOWN : View.FOCUS_UP)) return true;
      int intoContent = root.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL ? KeyEvent.KEYCODE_DPAD_LEFT : KeyEvent.KEYCODE_DPAD_RIGHT;
      if (key == intoContent && focused != null && inside(focused, nav)) {
        View target = lastContentFocus != null && inside(lastContentFocus, content) ? lastContentFocus : stops.isEmpty() ? null : stops.get(0);
        if (target != null && target.requestFocus()) return true;
        for (View stop : stops) if (stop.requestFocus()) { lastContentFocus = stop; return true; }
      }
    }
    boolean handled = super.dispatchKeyEvent(event);
    int towardNav = root != null && root.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL ? KeyEvent.KEYCODE_DPAD_RIGHT : KeyEvent.KEYCODE_DPAD_LEFT;
    if (!handled && event.getAction() == KeyEvent.ACTION_DOWN && event.getKeyCode() == towardNav
        && getCurrentFocus() != null && inside(getCurrentFocus(), content)) {
      for (int index = 0; index < nav.getChildCount(); index++) {
        View item = nav.getChildAt(index);
        if (current.contentEquals(item.getContentDescription() == null ? "" : item.getContentDescription())) return item.requestFocus();
      }
    }
    return handled;
  }

  @Override public void onUserInteraction() {
    super.onUserInteraction(); initialFocusPending = false;
  }

  @Override
  protected void onDestroy() {
    generation = ++nextGeneration; retainedGenerations.clear(); pendingScreens.clear();
    if (discovery != null) discovery.cancel();
    if (updater != null) updater.close();
    if (tg != null) tg.listener = null;
    super.onDestroy();
  }
}
