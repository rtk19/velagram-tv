package com.velagram.tv;

import android.app.*;
import android.content.*;
import android.content.pm.*;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.widget.*;
import androidx.core.content.FileProvider;
import java.io.*;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.concurrent.*;
import okhttp3.*;
import org.json.JSONObject;

/** Foreground-only updater. No account token or storage permission is needed. */
final class AppUpdater {
  private final Activity activity;
  private final Handler main = new Handler(Looper.getMainLooper());
  private final ExecutorService worker = Executors.newSingleThreadExecutor();
  private final OkHttpClient client = new OkHttpClient.Builder()
      .connectTimeout(20, TimeUnit.SECONDS).readTimeout(45, TimeUnit.SECONDS)
      .callTimeout(20, TimeUnit.MINUTES).build();
  private final SharedPreferences prefs;
  private volatile boolean closed;
  private boolean busy, waitingPermission;
  private AlertDialog dialog;
  private File ready;
  AppUpdater(Activity activity) {
    this.activity = activity;
    prefs = activity.getSharedPreferences("app_updates", Context.MODE_PRIVATE);
  }
  void resume() {
    if (waitingPermission) {
      waitingPermission = false;
      if (activity.getPackageManager().canRequestPackageInstalls()) install();
      else message("Installation permission is still disabled. Check for updates to try again.");
    } else check(false);
  }
  void check(boolean manual) {
    if (busy) { if (manual) Toast.makeText(activity, "An update check or download is already running.", Toast.LENGTH_SHORT).show(); return; }
    long now = System.currentTimeMillis();
    if (!manual && now - prefs.getLong("lastCheck", 0) < TimeUnit.HOURS.toMillis(6)) return;
    File saved = new File(activity.getFilesDir(), "updates/update.apk");
    if (manual && saved.isFile()) {
      busy = true;
      worker.execute(() -> {
        try { verify(saved); post(() -> { busy = false; ready = saved; installPrompt(); }); }
        catch (Exception e) { saved.delete(); post(() -> { busy = false; check(true); }); }
      });
      return;
    }
    busy = true;
    if (manual) Toast.makeText(activity, "Checking for updates…", Toast.LENGTH_SHORT).show();
    worker.execute(() -> {
      try (Response response = client.newCall(new Request.Builder()
          .url("https://api.github.com/repos/rtk19/velagram-tv/releases/latest")
          .header("Accept", "application/vnd.github+json")
          .header("User-Agent", "Velagram-TV/" + BuildConfig.VERSION_NAME).build()).execute()) {
        if (response.code() == 404) {
          post(() -> { busy = false; checked(); if (manual) message("No published release is available yet."); });
          return;
        }
        if (!response.isSuccessful() || response.body() == null)
          throw new IOException(response.code() == 403 || response.code() == 429
              ? "GitHub's request limit was reached. Please try again later." : "GitHub returned HTTP " + response.code());
        UpdateRelease release = UpdateRelease.parse(new JSONObject(response.body().string()));
        post(() -> {
          busy = false; checked();
          if (release == null) { if (manual) message("The latest release has no supported, checksummed universal APK yet."); return; }
          if (!UpdateRelease.newer(release.tag, BuildConfig.VERSION_NAME)) {
            if (manual) message("You're up to date (" + BuildConfig.VERSION_NAME + ").");
            return;
          }
          String notes = release.notes.length() > 1800 ? release.notes.substring(0, 1800) + "…" : release.notes;
          dialog = new AlertDialog.Builder(activity).setTitle("Update available · " + release.tag)
              .setMessage("Installed: " + BuildConfig.VERSION_NAME + "\nDownload: " + (release.size / 1048576) + " MB\n\n" + notes)
              .setPositiveButton("Download", (d, w) -> download(release))
              .setNegativeButton("Later", null).show();
        });
      } catch (Exception e) { post(() -> { busy = false; if (manual) message("Couldn't check for updates. " + error(e)); }); }
    });
  }
  private void checked() { prefs.edit().putLong("lastCheck", System.currentTimeMillis()).apply(); }
  private void download(UpdateRelease release) {
    busy = true;
    LinearLayout layout = new LinearLayout(activity); layout.setOrientation(LinearLayout.VERTICAL); layout.setPadding(40, 24, 40, 24);
    TextView label = new TextView(activity); label.setText("Starting download…");
    ProgressBar progress = new ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal); progress.setMax(100);
    layout.addView(label); layout.addView(progress);
    dialog = new AlertDialog.Builder(activity).setTitle("Downloading " + release.tag).setView(layout)
        .setCancelable(false).setNegativeButton("Cancel", (d, w) -> client.dispatcher().cancelAll()).show();
    worker.execute(() -> {
      File dir = new File(activity.getFilesDir(), "updates");
      File partial = new File(dir, "update.part"), apk = new File(dir, "update.apk");
      try {
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Can't create download directory.");
        if (dir.getUsableSpace() < release.size + 32L * 1048576) throw new IOException("Not enough free storage.");
        MessageDigest hash = MessageDigest.getInstance("SHA-256");
        try (Response r = client.newCall(new Request.Builder().url(release.url).build()).execute()) {
          if (!r.isSuccessful() || r.body() == null) throw new IOException("Download failed (HTTP " + r.code() + ").");
          if (!r.request().url().isHttps()) throw new IOException("Insecure download redirect.");
          try (InputStream in = r.body().byteStream(); OutputStream out = new FileOutputStream(partial)) {
            byte[] buffer = new byte[65536]; long count = 0; int n, previous = -1;
            while ((n = in.read(buffer)) != -1) {
              if (closed || Thread.currentThread().isInterrupted()) throw new IOException("Download cancelled.");
              count += n;
              if (count > release.size) throw new IOException("Unexpected APK size.");
              out.write(buffer, 0, n); hash.update(buffer, 0, n);
              int percent = (int) (100 * count / release.size);
              if (percent != previous) {
                previous = percent;
                post(() -> { progress.setProgress(percent); label.setText(percent + "% downloaded"); });
              }
            }
            if (count != release.size) throw new IOException("Incomplete download. Please retry.");
          }
        }
        StringBuilder hex = new StringBuilder(); for (byte b : hash.digest()) hex.append(String.format("%02x", b & 255));
        if (!hex.toString().equalsIgnoreCase(release.digest)) throw new IOException("APK checksum verification failed.");
        verify(partial);
        if (apk.exists() && !apk.delete()) throw new IOException("Can't replace previous download.");
        if (!partial.renameTo(apk)) throw new IOException("Can't save download.");
        post(() -> { busy = false; dialog.dismiss(); ready = apk; installPrompt(); });
      } catch (Exception e) {
        partial.delete();
        post(() -> { busy = false; dialog.dismiss(); message("Update download stopped. " + error(e) + "\nCheck for updates to retry."); });
      }
    });
  }
  @SuppressWarnings("deprecation")
  private void verify(File apk) throws Exception {
    PackageManager pm = activity.getPackageManager();
    int flag = Build.VERSION.SDK_INT >= 28 ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;
    PackageInfo downloaded = pm.getPackageArchiveInfo(apk.getAbsolutePath(), flag);
    PackageInfo installed = pm.getPackageInfo(activity.getPackageName(), flag);
    if (downloaded == null || !activity.getPackageName().equals(downloaded.packageName)) throw new IOException("Wrong APK package.");
    long next = Build.VERSION.SDK_INT >= 28 ? downloaded.getLongVersionCode() : downloaded.versionCode;
    long current = Build.VERSION.SDK_INT >= 28 ? installed.getLongVersionCode() : installed.versionCode;
    if (next <= current) throw new IOException("APK is not newer than the installed app.");
    if (Build.VERSION.SDK_INT >= 28) {
      for (Signature signature : downloaded.signingInfo.getApkContentsSigners()) {
        if (!pm.hasSigningCertificate(activity.getPackageName(), signature.toByteArray(), PackageManager.CERT_INPUT_RAW_X509))
          throw new IOException("APK signing certificate does not match this installation.");
      }
    } else if (!Arrays.equals(downloaded.signatures, installed.signatures)) throw new IOException("APK signing certificate does not match.");
    if (Build.VERSION.SDK_INT >= 24 && downloaded.applicationInfo.minSdkVersion > Build.VERSION.SDK_INT)
      throw new IOException("This update requires a newer Android version.");
  }
  private void installPrompt() {
    dialog = new AlertDialog.Builder(activity).setTitle("Update ready")
        .setMessage("Download verified. Install now? Your library and settings will be preserved.")
        .setPositiveButton("Install", (d, w) -> install()).setNegativeButton("Later", null).show();
  }
  private void install() {
    if (ready == null || !ready.isFile()) { message("Download the update again."); return; }
    try {
      if (!activity.getPackageManager().canRequestPackageInstalls()) {
        dialog = new AlertDialog.Builder(activity).setTitle("Allow updates from Velagram")
            .setMessage("Enable ‘Allow from this source’, then return to Velagram to continue installing.")
            .setPositiveButton("Open settings", (d, w) -> {
              try { waitingPermission = true; activity.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + activity.getPackageName()))); }
              catch (ActivityNotFoundException e) { waitingPermission = false; message("Open Android Settings → Security → Install unknown apps and allow Velagram."); }
            }).setNegativeButton("Later", null).show();
        return;
      }
      Uri uri = FileProvider.getUriForFile(activity, activity.getPackageName() + ".updates", ready);
      Intent intent = new Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
          .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
      intent.setClipData(ClipData.newRawUri("Update", uri));
      activity.startActivity(intent);
    } catch (Exception e) { message("Couldn't open the installer. " + error(e)); }
  }
  private String error(Exception e) { return e.getMessage() == null ? "Please check your connection and try again." : e.getMessage(); }
  private void message(String text) { if (!closed) dialog = new AlertDialog.Builder(activity).setTitle("Velagram updates").setMessage(text).setPositiveButton("OK", null).show(); }
  private void post(Runnable action) { main.post(() -> { if (!closed && !activity.isFinishing() && !activity.isDestroyed()) action.run(); }); }
  void close() { closed = true; client.dispatcher().cancelAll(); worker.shutdownNow(); main.removeCallbacksAndMessages(null); if (dialog != null) dialog.dismiss(); }
}
