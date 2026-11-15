package com.velagram.tv;

import android.app.*;
import android.content.*;
import android.net.Uri;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.io.*;
import org.json.*;

public class SubtitleStudioActivity extends Activity {
  Store store;
  String source = "", subtitleKey = "", mediaKey = "", sourceUri = "";
  long offset;
  TextView offsetLabel, preview;
  LinearLayout root;

  int dp(int n) {
    return TvUi.dp(this, n);
  }

  @Override
  public void onCreate(Bundle state) {
    super.onCreate(state);
    getWindow().setFlags(1024, 1024);
    store = new Store(this);
    mediaKey = getIntent().getStringExtra("mediaKey");
    if (mediaKey == null) mediaKey = "local";
    subtitleKey = getIntent().getStringExtra("subtitleKey");
    if (subtitleKey == null) subtitleKey = "local";
    sourceUri = getIntent().getStringExtra("subtitleURI");
    if (sourceUri != null)
      try {
        read(Uri.parse(sourceUri));
      } catch (Exception ignored) {
      }
    render();
  }

  void render() {
    root = TvUi.col(this);
    root.setPadding(dp(40), dp(30), dp(40), dp(28));
    root.setBackgroundColor(TvUi.BG);
    TextView eyebrow = TvUi.text(this, "VELAGRAM  /  SUBTITLE STUDIO", 11, TvUi.accent(store));
    eyebrow.setLetterSpacing(.15f);
    root.addView(eyebrow);
    TextView title = TvUi.text(this, "Make every line land.", 32, TvUi.TEXT);
    title.setTypeface(null, android.graphics.Typeface.BOLD);
    title.setPadding(0, dp(17), 0, dp(10));
    root.addView(title);
    TextView description =
        TvUi.text(
            this,
            "Adjust the timing. Save a version for this episode and subtitle release.",
            13,
            TvUi.MUTED);
    root.addView(description);
    LinearLayout work = TvUi.row(this);
    LinearLayout.LayoutParams wp = new LinearLayout.LayoutParams(-1, 0, 1);
    wp.setMargins(0, dp(26), 0, dp(22));
    root.addView(work, wp);
    LinearLayout timing = TvUi.col(this);
    timing.setGravity(Gravity.CENTER);
    timing.setBackground(TvUi.shape(TvUi.SURFACE, 16, this));
    work.addView(timing, new LinearLayout.LayoutParams(dp(280), -1));
    TextView label = TvUi.text(this, "TIMING OFFSET", 11, TvUi.MUTED);
    label.setLetterSpacing(.13f);
    timing.addView(label);
    offsetLabel = TvUi.text(this, "", 45, TvUi.TEXT);
    offsetLabel.setPadding(0, dp(15), 0, dp(22));
    timing.addView(offsetLabel);
    LinearLayout adjustments = TvUi.row(this);
    adjustments.addView(TvUi.button(this, "− 0.5s", false, () -> adjust(-500)));
    LinearLayout.LayoutParams gap = new LinearLayout.LayoutParams(dp(12), 1);
    adjustments.addView(new View(this), gap);
    adjustments.addView(TvUi.button(this, "+ 0.5s", false, () -> adjust(500)));
    timing.addView(adjustments);
    TextView reset =
        TvUi.button(
            this,
            "Reset timing",
            false,
            () -> {
              offset = 0;
              update();
            });
    LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-2, dp(38));
    rp.setMargins(0, dp(12), 0, 0);
    timing.addView(reset, rp);
    ScrollView scroll = new ScrollView(this);
    LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(0, -1, 1);
    sp.setMargins(dp(24), 0, 0, 0);
    work.addView(scroll, sp);
    preview = TvUi.text(this, "", 16, 0xffd5dae4);
    preview.setLineSpacing(dp(7), 1);
    preview.setPadding(dp(20), dp(14), dp(20), dp(14));
    preview.setBackground(TvUi.shape(TvUi.SURFACE, 12, this));
    scroll.addView(preview);
    LinearLayout actions = TvUi.row(this);
    actions.addView(TvUi.button(this, "‹  Back", false, this::finish));
    View spacer = new View(this);
    actions.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1));
    actions.addView(TvUi.button(this, "Open SRT / VTT", false, this::pick));
    View tiny = new View(this);
    actions.addView(tiny, new LinearLayout.LayoutParams(dp(12), 1));
    actions.addView(TvUi.button(this, "Save version & use", true, this::save));
    root.addView(actions);
    setContentView(root);
    update();
  }

  void adjust(long delta) {
    offset = Math.max(-300000, Math.min(300000, offset + delta));
    update();
  }

  void update() {
    offsetLabel.setText(String.format(java.util.Locale.US, "%+.1f s", offset / 1000.0));
    preview.setText(
        source.isEmpty()
            ? "Open a subtitle file to preview its cues here."
            : Subtitles.shift(source, offset));
  }

  void pick() {
    try {
      startActivityForResult(
          new Intent(Intent.ACTION_OPEN_DOCUMENT)
              .setType("*/*")
              .addCategory(Intent.CATEGORY_OPENABLE),
          1);
    } catch (Exception e) {
      Toast.makeText(
              this, "A document provider is needed to import files on this TV.", Toast.LENGTH_LONG)
          .show();
    }
  }

  void read(Uri uri) throws Exception {
    try (InputStream in = getContentResolver().openInputStream(uri)) {
      source =
          new String(Streams.read(in, 2 * 1024 * 1024), java.nio.charset.StandardCharsets.UTF_8);
    }
    sourceUri = uri.toString();
    if (subtitleKey.equals("local")) subtitleKey = sourceUri;
  }

  void save() {
    if (source.isEmpty()) {
      pick();
      return;
    }
    try {
      JSONObject version = SubtitleSync.save(this, store, mediaKey, subtitleKey, source, offset);
      setResult(
          RESULT_OK,
          new Intent()
              .setData(Uri.fromFile(new File(version.getString("file"))))
              .putExtra("subtitleKey", subtitleKey));
      finish();
    } catch (Exception e) {
      Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
    }
  }

  @Override
  protected void onActivityResult(int r, int result, Intent data) {
    super.onActivityResult(r, result, data);
    if (r == 1 && result == RESULT_OK && data != null && data.getData() != null)
      try {
        getContentResolver()
            .takePersistableUriPermission(data.getData(), Intent.FLAG_GRANT_READ_URI_PERMISSION);
        subtitleKey = data.getData().toString();
        read(data.getData());
        update();
      } catch (Exception e) {
        Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
      }
  }
}
