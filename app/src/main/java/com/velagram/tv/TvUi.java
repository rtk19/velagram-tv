package com.velagram.tv;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.graphics.drawable.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.util.*;
import java.util.function.*;

/** Shared TV design: quiet chrome, clear typography and deliberate remote focus. */
public final class TvUi {
  public static final int BG = 0xff080a10,
      SURFACE = 0xff141822,
      TEXT = 0xfff3f5fa,
      MUTED = 0xff969eae;
  public static final String[] THEMES = {
    "Blue", "Silver", "Violet", "Aurora", "Rose", "Ember", "Ocean", "Mint", "Gold", "Crimson",
    "Indigo"
  };
  public static final int[] COLORS = {
    0xff397bff,
    0xffb8c2d4,
    0xff765bff,
    0xff20c7c5,
    0xffc85c9e,
    0xffdf6045,
    0xff2fa7c9,
    0xff4cc9a6,
    0xffd5a64a,
    0xffc84b62,
    0xff5367d8
  };

  public static int accent(Store s) {
    int i = Arrays.asList(THEMES).indexOf(s.get("theme", "Blue"));
    return COLORS[Math.max(0, i)];
  }

  public static int dp(Context c, float v) {
    return (int) (c.getResources().getDisplayMetrics().density * v + .5f);
  }

  public static GradientDrawable shape(int color, int radius, Context c) {
    GradientDrawable d = new GradientDrawable();
    d.setColor(color);
    d.setCornerRadius(dp(c, radius));
    return d;
  }

  public static TextView text(Context c, String value, int size, int color) {
    TextView t = new TextView(c);
    t.setText(value);
    t.setTextSize(size);
    t.setTextColor(color);
    t.setFontFeatureSettings("kern");
    t.setIncludeFontPadding(false);
    return t;
  }

  public static LinearLayout col(Context c) {
    LinearLayout l = new LinearLayout(c);
    l.setOrientation(LinearLayout.VERTICAL);
    return l;
  }

  public static LinearLayout row(Context c) {
    LinearLayout l = new LinearLayout(c);
    l.setGravity(Gravity.CENTER_VERTICAL);
    return l;
  }

  public static void focus(View view, boolean primary) {
    Context c = view.getContext();
    view.setFocusable(true);
    view.setClickable(true);
    view.setBackground(shape(primary ? TEXT : 0xff202530, 8, c));
    view.setOnFocusChangeListener(
        (v, f) -> {
          GradientDrawable d = shape(f ? TEXT : primary ? TEXT : 0xff202530, 8, c);
          if (f) d.setStroke(dp(c, 2), 0xffffffff);
          v.setBackground(d);
          if (v instanceof TextView) ((TextView) v).setTextColor(f || primary ? BG : TEXT);
          v.animate().scaleX(f ? 1.04f : 1).scaleY(f ? 1.04f : 1).setDuration(140).start();
        });
  }

  public static TextView button(Context c, String label, boolean primary, Runnable action) {
    TextView t = text(c, label, 14, primary ? BG : TEXT);
    t.setGravity(Gravity.CENTER);
    t.setTypeface(null, Typeface.BOLD);
    t.setPadding(dp(c, 18), dp(c, 12), dp(c, 18), dp(c, 12));
    focus(t, primary);
    t.setOnClickListener(v -> action.run());
    return t;
  }

  public static void sheet(
      Activity a,
      String title,
      String subtitle,
      String[] labels,
      int selected,
      IntConsumer action) {
    Dialog dialog = new Dialog(a);
    LinearLayout box = col(a);
    box.setPadding(dp(a, 28), dp(a, 28), dp(a, 28), dp(a, 24));
    box.setBackgroundColor(0xff12151e);
    TextView back = button(a, "‹  Back", false, dialog::dismiss);
    box.addView(back, new LinearLayout.LayoutParams(-2, dp(a, 40)));
    TextView h = text(a, title, 26, TEXT);
    h.setTypeface(null, Typeface.BOLD);
    LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(-1, -2);
    hp.setMargins(0, dp(a, 28), 0, dp(a, 10));
    box.addView(h, hp);
    if (!subtitle.isEmpty()) {
      TextView sub = text(a, subtitle, 13, MUTED);
      box.addView(sub);
    }
    ScrollView scroll = new ScrollView(a);
    scroll.setClipToPadding(false);
    scroll.setPadding(0, dp(a, 18), 0, 0);
    LinearLayout rows = col(a);
    for (int i = 0; i < labels.length; i++) {
      final int index = i;
      TextView b =
          button(
              a,
              (i == selected ? "✓   " : "") + labels[i],
              false,
              () -> {
                dialog.dismiss();
                action.accept(index);
              });
      b.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
      b.setTextSize(15);
      LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
      p.setMargins(0, 0, 0, dp(a, 7));
      rows.addView(b, p);
    }
    scroll.addView(rows);
    box.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
    dialog.setContentView(box);
    Window w = dialog.getWindow();
    if (w != null) {
      w.setBackgroundDrawableResource(android.R.color.transparent);
      w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
      WindowManager.LayoutParams p = new WindowManager.LayoutParams();
      p.copyFrom(w.getAttributes());
      p.width = dp(a, 365);
      p.height = -1;
      p.gravity = Gravity.END;
      p.dimAmount = .60f;
      w.setAttributes(p);
    }
    dialog.show();
    if (labels.length > 0) rows.getChildAt(Math.max(0, selected)).requestFocus();
  }

  public static Dialog panel(
      Activity a, String title, String subtitle, View body, String positive, Runnable save) {
    Dialog dialog = new Dialog(a);
    LinearLayout box = col(a);
    box.setPadding(dp(a, 28), dp(a, 26), dp(a, 28), dp(a, 24));
    box.setBackground(shape(SURFACE, 18, a));
    TextView h = text(a, title, 25, TEXT);
    h.setTypeface(null, Typeface.BOLD);
    box.addView(h);
    TextView sub = text(a, subtitle, 13, MUTED);
    sub.setPadding(0, dp(a, 10), 0, dp(a, 22));
    box.addView(sub);
    box.addView(body);
    LinearLayout controls = row(a);
    LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(a, 44), 1);
    p.setMargins(0, dp(a, 22), dp(a, 8), 0);
    controls.addView(button(a, "Cancel", false, dialog::dismiss), p);
    controls.addView(
        button(
            a,
            positive,
            true,
            () -> {
              dialog.dismiss();
              save.run();
            }),
        p);
    box.addView(controls);
    dialog.setContentView(box);
    Window w = dialog.getWindow();
    if (w != null) {
      w.setBackgroundDrawableResource(android.R.color.transparent);
      w.setLayout(dp(a, 510), -2);
      w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
      WindowManager.LayoutParams lp = w.getAttributes();
      lp.dimAmount = .7f;
      w.setAttributes(lp);
    }
    dialog.show();
    return dialog;
  }

  public static class Icon extends View {
    final String name;
    int color;
    final Paint p = new Paint(3);

    public Icon(Context c, String n, int tint) {
      super(c);
      name = n;
      color = tint;
      setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    public void tint(int c) {
      color = c;
      invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
      super.onDraw(canvas);
      canvas.save();
      float s = Math.min(getWidth(), getHeight()) / 24f;
      canvas.translate((getWidth() - 24 * s) / 2, (getHeight() - 24 * s) / 2);
      canvas.scale(s, s);
      p.setColor(color);
      p.setStrokeWidth(1.6f);
      p.setStrokeCap(Paint.Cap.ROUND);
      p.setStrokeJoin(Paint.Join.ROUND);
      p.setStyle(Paint.Style.STROKE);
      Path path = new Path();
      switch (name) {
        case "Home":
          path.moveTo(3, 11);
          path.lineTo(12, 3);
          path.lineTo(21, 11);
          path.moveTo(5, 10);
          path.lineTo(5, 21);
          path.lineTo(10, 21);
          path.lineTo(10, 15);
          path.lineTo(14, 15);
          path.lineTo(14, 21);
          path.lineTo(19, 21);
          path.lineTo(19, 10);
          canvas.drawPath(path, p);
          break;
        case "Search":
          canvas.drawCircle(10.5f, 10.5f, 6.5f, p);
          canvas.drawLine(16, 16, 21, 21, p);
          break;
        case "Movies":
          canvas.drawRoundRect(3, 5, 21, 21, 2, 2, p);
          canvas.drawLine(3, 10, 21, 10, p);
          canvas.drawLine(7, 5, 10, 10, p);
          canvas.drawLine(14, 5, 17, 10, p);
          break;
        case "Series":
        case "Live TV":
          canvas.drawRoundRect(2, 5, 22, 19, 3, 3, p);
          canvas.drawLine(8, 22, 16, 22, p);
          if (name.equals("Live TV")) {
            p.setStyle(Paint.Style.FILL);
            canvas.drawCircle(12, 12, 3, p);
          }
          break;
        case "Telegram":
          path.moveTo(2, 10);
          path.lineTo(22, 3);
          path.lineTo(18, 22);
          path.lineTo(11, 16);
          path.lineTo(7, 20);
          path.lineTo(8, 13);
          path.lineTo(18, 7);
          path.lineTo(9, 14);
          path.lineTo(2, 10);
          canvas.drawPath(path, p);
          break;
        case "Library":
          canvas.drawRoundRect(3, 4, 8, 21, 1, 1, p);
          canvas.drawRoundRect(10, 4, 15, 21, 1, 1, p);
          canvas.save();
          canvas.rotate(-12, 19, 13);
          canvas.drawRoundRect(18, 4, 22, 21, 1, 1, p);
          canvas.restore();
          break;
        case "More":
          canvas.drawCircle(12, 12, 4, p);
          for (int i = 0; i < 8; i++) {
            canvas.save();
            canvas.rotate(i * 45, 12, 12);
            canvas.drawLine(12, 2, 12, 5, p);
            canvas.restore();
          }
          canvas.drawCircle(12, 12, 8, p);
          break;
        case "Play":
          p.setStyle(Paint.Style.FILL);
          path.moveTo(8, 4);
          path.lineTo(21, 12);
          path.lineTo(8, 20);
          path.close();
          canvas.drawPath(path, p);
          break;
        case "Pause":
          p.setStyle(Paint.Style.FILL);
          canvas.drawRoundRect(6, 4, 10, 20, 1, 1, p);
          canvas.drawRoundRect(14, 4, 18, 20, 1, 1, p);
          break;
        case "Back":
          canvas.drawLine(16, 4, 8, 12, p);
          canvas.drawLine(8, 12, 16, 20, p);
          break;
        default:
          canvas.drawCircle(12, 12, 9, p);
          canvas.drawLine(8, 12, 16, 12, p);
          canvas.drawLine(12, 8, 12, 16, p);
      }
      canvas.restore();
    }
  }
}
