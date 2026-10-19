package df.root;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.core.content.FileProvider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Startup update check with an in-app download and installer hand-off.
 *
 * <p>Rather than guessing whether the user is in mainland China (IP geolocation
 * breaks behind VPNs and on roaming/HK nodes), both release APIs are queried at
 * the same time and the faster one wins: Gitee answers quickly from inside
 * China, GitHub from everywhere else. When both answer, the higher version is
 * used so a stale mirror cannot hide a release.
 *
 * <p>The same dual-source fetch loads the announcement in {@code notice.json},
 * which is shown on every launch and can only be dismissed with its button.
 */
final class UpdateChecker {

    private static final String TAG = "dfroot";

    private static final String GITHUB_LATEST =
            "https://api.github.com/repos/dugongzi/DirtyFragX/releases/latest";
    private static final String GITEE_LATEST =
            "https://gitee.com/api/v5/repos/blblwanfeng/DirtyFragX/releases/latest";
    private static final String GITHUB_RELEASES =
            "https://github.com/dugongzi/DirtyFragX/releases";
    private static final String GITEE_RELEASES =
            "https://gitee.com/blblwanfeng/DirtyFragX/releases";
    private static final String GITHUB_NOTICE =
            "https://raw.githubusercontent.com/dugongzi/DirtyFragX/master/notice.json";
    private static final String GITEE_NOTICE =
            "https://gitee.com/blblwanfeng/DirtyFragX/raw/master/notice.json";
    /** CDN mirror of the GitHub copy: usually far faster than Gitee raw in
     *  mainland China, and it is not affected by raw.githubusercontent.com
     *  being blocked. */
    private static final String JSDELIVR_NOTICE =
            "https://cdn.jsdelivr.net/gh/dugongzi/DirtyFragX@master/notice.json";
    private static final String[] NOTICE_SOURCES =
            {GITEE_NOTICE, JSDELIVR_NOTICE, GITHUB_NOTICE};

    private static final int CONNECT_TIMEOUT_MS = 4000;
    private static final int READ_TIMEOUT_MS = 4000;
    // Gitee's raw endpoint is cold-cached and can take well over 4s on the
    // first request after a launch, so notices get a longer deadline.
    private static final int NOTICE_CONNECT_TIMEOUT_MS = 10000;
    private static final int NOTICE_READ_TIMEOUT_MS = 10000;
    /** Returned by {@link #fetchNotice} when a source answered "no notice". */
    private static final Notice NO_NOTICE = new Notice(null, null);
    private static final String PREFS = "updates";

    private final Activity activity;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService net = Executors.newFixedThreadPool(2);
    private final ExecutorService noticeNet = Executors.newFixedThreadPool(3);
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private volatile boolean cancelled;
    private volatile boolean manualChecking;

    private AlertDialog updateDialog;
    private AlertDialog noticeDialog;
    /** An update that arrived while the announcement was on screen. */
    private Release pendingUpdate;
    /** The update currently on screen, so the announcement can take over and
     *  restore it once acknowledged. */
    private Release showingUpdate;
    private AlertDialog progressDialog;
    private ProgressBar progressBar;
    private TextView progressText;

    UpdateChecker(Activity activity) {
        this.activity = activity;
    }

    /** Called from onCreate. Shows a cached result immediately and always
     *  queries both sources in the background on every launch. */
    void checkOnStart() {
        SharedPreferences sp = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        Release cached = cachedRelease(sp);
        if (cached != null && isNewer(cached.tag)) {
            showUpdateDialog(cached);
        }
        io.execute(() -> {
            Release r = fetchFastest();
            if (cancelled || r == null) return;
            SharedPreferences.Editor ed = sp.edit();
            if (!isNewer(r.tag)) {
                ed.remove("cached_tag").remove("cached_notes")
                        .remove("cached_apk").remove("cached_page").apply();
                return;
            }
            ed.putString("cached_tag", r.tag)
                    .putString("cached_notes", r.notes)
                    .putString("cached_apk", r.apkUrl)
                    .putString("cached_page", r.pageUrl)
                    .apply();
            showUpdateDialog(r);
        });
    }

    /** Fetches the announcement from every mirror on each launch. The last
     *  copy is shown from cache immediately, so a slow or blocked mirror can
     *  no longer hide the announcement; the first fresh copy replaces it in
     *  place once a source answers. */
    void checkAnnouncement() {
        SharedPreferences sp = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        Notice cached = cachedNotice(sp);
        if (cached != null) showNotice(cached);

        final AtomicBoolean applied = new AtomicBoolean(false);
        final AtomicInteger gone = new AtomicInteger();
        for (String url : NOTICE_SOURCES) {
            try {
                noticeNet.execute(() -> {
                    Notice n = fetchNotice(url);
                    if (n == NO_NOTICE) {
                        // Only trust "no notice" once every mirror agrees, so
                        // one lagging mirror cannot wipe a valid announcement.
                        if (gone.incrementAndGet() == NOTICE_SOURCES.length) {
                            sp.edit().remove("notice_title")
                                    .remove("notice_content").apply();
                        }
                        return;
                    }
                    if (n == null) return;
                    if (applied.compareAndSet(false, true)) {
                        sp.edit().putString("notice_title", n.title)
                                .putString("notice_content", n.content).apply();
                        showNotice(n);
                    }
                });
            } catch (RejectedExecutionException e) {
                // cancel() shut the pool down while the check was starting.
            }
        }
    }

    private Notice cachedNotice(SharedPreferences sp) {
        String content = sp.getString("notice_content", null);
        if (content == null || content.trim().isEmpty()) return null;
        String title = sp.getString("notice_title", null);
        if (title == null || title.trim().isEmpty()) {
            title = activity.getString(R.string.notice_title);
        }
        return new Notice(title, content);
    }

    /** Manual check from Settings → About. Unlike the startup check this
     *  always reports the outcome, including "already up to date" and
     *  failures, so the user gets feedback from the tap. */
    void checkManually() {
        if (manualChecking || cancelled) return;
        manualChecking = true;
        Toast.makeText(activity, R.string.update_checking, Toast.LENGTH_SHORT).show();
        try {
            io.execute(() -> {
                Release r = null;
                try {
                    r = fetchFastest();
                } catch (Exception e) {
                    Log.i(TAG, "manual update check failed: " + e);
                }
                final Release result = r;
                main.post(() -> {
                    manualChecking = false;
                    if (isDead()) return;
                    if (result == null) {
                        Toast.makeText(activity, R.string.update_check_failed,
                                Toast.LENGTH_LONG).show();
                    } else if (isNewer(result.tag)) {
                        showUpdateDialog(result);
                    } else {
                        Toast.makeText(activity, R.string.update_latest,
                                Toast.LENGTH_SHORT).show();
                    }
                });
            });
        } catch (RejectedExecutionException e) {
            // cancel() shut the pool down while the check was starting.
            manualChecking = false;
        }
    }

    /** Stops everything still running. Safe to call from onDestroy. */
    void cancel() {
        cancelled = true;
        net.shutdownNow();
        noticeNet.shutdownNow();
        io.shutdownNow();
        main.post(() -> {
            if (updateDialog != null) {
                updateDialog.dismiss();
                updateDialog = null;
            }
            if (noticeDialog != null) {
                noticeDialog.dismiss();
                noticeDialog = null;
            }
            dismissProgress();
        });
    }

    // ---- fetching -----------------------------------------------------------

    /** Queries both endpoints in parallel and keeps the highest version that
     *  answered inside the timeout. */
    private Release fetchFastest() {
        final CountDownLatch done = new CountDownLatch(2);
        final List<Release> results = Collections.synchronizedList(new ArrayList<>());
        for (String api : new String[]{GITEE_LATEST, GITHUB_LATEST}) {
            try {
                net.execute(() -> {
                    try {
                        Release r = fetch(api);
                        if (r != null) results.add(r);
                    } catch (Exception e) {
                        Log.i(TAG, "update source unavailable: " + api + " (" + e + ")");
                    } finally {
                        done.countDown();
                    }
                });
            } catch (RejectedExecutionException e) {
                // cancel() shut the pool down while the check was starting.
                done.countDown();
            }
        }
        try {
            done.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
        Release best = null;
        synchronized (results) {
            for (Release r : results) {
                if (best == null || compareVersions(r.tag, best.tag) > 0) best = r;
            }
        }
        return best;
    }

    private Release fetch(String api) throws Exception {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(api).openConnection();
            c.setConnectTimeout(CONNECT_TIMEOUT_MS);
            c.setReadTimeout(READ_TIMEOUT_MS);
            c.setRequestProperty("User-Agent", "DirtyFragX/" + BuildConfig.VERSION_NAME);
            c.setRequestProperty("Accept", "application/json");
            if (c.getResponseCode() != HttpURLConnection.HTTP_OK) return null;

            JSONObject o = new JSONObject(readAll(c.getInputStream()));
            String tag = o.optString("tag_name", "").trim();
            if (tag.isEmpty()) return null;

            String apk = null;
            JSONArray assets = o.optJSONArray("assets");
            if (assets != null) {
                for (int i = 0; i < assets.length(); i++) {
                    JSONObject a = assets.optJSONObject(i);
                    if (a == null) continue;
                    String name = a.optString("name", "");
                    String url = a.optString("browser_download_url", "");
                    if (url.isEmpty() || !name.toLowerCase(Locale.ROOT).endsWith(".apk")) continue;
                    if ("dirtyfrag.apk".equalsIgnoreCase(name)) {
                        apk = url;
                        break;
                    }
                    if (apk == null) apk = url;
                }
            }

            String page = o.optString("html_url", "");
            if (page.isEmpty()) {
                page = api.contains("gitee.com") ? GITEE_RELEASES : GITHUB_RELEASES;
            }
            return new Release(tag, o.optString("body", ""), apk, page);
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static String readAll(InputStream in) throws IOException {
        try (InputStream is = in; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) != -1) out.write(buf, 0, n);
            return out.toString("UTF-8");
        }
    }

    private Notice fetchNotice(String url) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(NOTICE_CONNECT_TIMEOUT_MS);
            c.setReadTimeout(NOTICE_READ_TIMEOUT_MS);
            c.setRequestProperty("User-Agent", "DirtyFragX/" + BuildConfig.VERSION_NAME);
            c.setRequestProperty("Cache-Control", "no-cache");
            int code = c.getResponseCode();
            if (code == HttpURLConnection.HTTP_NOT_FOUND) return NO_NOTICE;
            if (code != HttpURLConnection.HTTP_OK) return null;

            JSONObject o = new JSONObject(readAll(c.getInputStream()));
            if (!o.optBoolean("enabled", true)) return NO_NOTICE;
            String title = o.optString("title", "").trim();
            String content = o.optString("content", "").trim();
            if (content.isEmpty()) return NO_NOTICE;
            if (title.isEmpty()) title = activity.getString(R.string.notice_title);
            return new Notice(title, content);
        } catch (Exception e) {
            Log.i(TAG, "notice source unavailable: " + url + " (" + e + ")");
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    // ---- UI -----------------------------------------------------------------

    private void showUpdateDialog(Release r) {
        main.post(() -> {
            if (isDead()) return;
            if (noticeDialog != null) {
                // The announcement owns the screen; bring this back afterwards.
                pendingUpdate = r;
                return;
            }
            if (updateDialog != null) return;
            String notes = r.notes == null ? "" : r.notes.trim();
            if (notes.length() > 600) notes = notes.substring(0, 600) + "…";
            AlertDialog dialog = new AlertDialog.Builder(activity)
                    .setTitle(activity.getString(R.string.update_available) + " · " + r.tag)
                    .setMessage(notes)
                    .setPositiveButton(R.string.update, (d, w) -> {
                        if (r.apkUrl == null) openPage(r.pageUrl);
                        else startDownload(r);
                    })
                    .setNegativeButton(R.string.update_later, null)
                    .create();
            dialog.setOnDismissListener(d -> {
                updateDialog = null;
                showingUpdate = null;
            });
            dialog.show();
            updateDialog = dialog;
            showingUpdate = r;
        });
    }

    /** Shows the announcement. It is deliberately not cancelable: the only way
     *  past it is the button, and it appears again on the next launch. */
    private void showNotice(Notice n) {
        main.post(() -> {
            if (isDead()) return;
            if (noticeDialog != null) {
                // The cached copy is already on screen; swap in the fresh text.
                TextView message = noticeDialog.findViewById(android.R.id.message);
                if (message != null) message.setText(n.content);
                noticeDialog.setTitle(n.title);
                return;
            }
            // Take over from an update dialog, remembering it for afterwards.
            if (updateDialog != null) {
                pendingUpdate = showingUpdate;
                updateDialog.dismiss();
            }
            AlertDialog dialog = new AlertDialog.Builder(activity)
                    .setTitle(n.title)
                    .setMessage(n.content)
                    .setPositiveButton(R.string.notice_ok, null)
                    .setCancelable(false)
                    .create();
            dialog.setCanceledOnTouchOutside(false);
            dialog.setOnDismissListener(d -> {
                noticeDialog = null;
                Release next = pendingUpdate;
                pendingUpdate = null;
                if (next != null) showUpdateDialog(next);
            });
            dialog.show();
            noticeDialog = dialog;
        });
    }

    private void openPage(String pageUrl) {
        if (pageUrl == null) return;
        try {
            activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(pageUrl)));
        } catch (Exception e) {
            Toast.makeText(activity, R.string.update_install_failed, Toast.LENGTH_SHORT).show();
        }
    }

    private void startDownload(Release r) {
        showProgress();
        try {
            io.execute(() -> {
                File dir = new File(activity.getCacheDir(), "updates");
                if (!dir.exists() && !dir.mkdirs()) {
                    fail(R.string.update_download_failed);
                    return;
                }
                File[] old = dir.listFiles();
                if (old != null) for (File f : old) f.delete();
                File apk = new File(dir, "dirtyfrag.apk");
                try {
                    download(r.apkUrl, apk);
                } catch (Exception e) {
                    apk.delete();
                    if (!cancelled) fail(R.string.update_download_failed);
                    return;
                }
                if (cancelled) {
                    apk.delete();
                    return;
                }
                if (!isSameSignature(apk)) {
                    apk.delete();
                    fail(R.string.update_bad_package);
                    return;
                }
                main.post(() -> {
                    if (isDead()) return;
                    dismissProgress();
                    launchInstaller(apk);
                });
            });
        } catch (RejectedExecutionException e) {
            // cancel() ran between the dialog tap and the download start.
            dismissProgress();
        }
    }

    private void download(String url, File out) throws IOException {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(CONNECT_TIMEOUT_MS);
            c.setReadTimeout(READ_TIMEOUT_MS);
            c.setRequestProperty("User-Agent", "DirtyFragX/" + BuildConfig.VERSION_NAME);
            if (c.getResponseCode() != HttpURLConnection.HTTP_OK) {
                throw new IOException("HTTP " + c.getResponseCode());
            }
            long total = c.getContentLengthLong();
            try (InputStream in = new BufferedInputStream(c.getInputStream());
                 FileOutputStream fos = new FileOutputStream(out)) {
                byte[] buf = new byte[8192];
                long read = 0;
                int lastPct = -1;
                int n;
                while ((n = in.read(buf)) != -1) {
                    if (cancelled) throw new IOException("cancelled");
                    fos.write(buf, 0, n);
                    read += n;
                    if (total > 0) {
                        int pct = (int) (read * 100 / total);
                        if (pct != lastPct) {
                            lastPct = pct;
                            setProgress(pct);
                        }
                    }
                }
            }
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** The update must be signed by the same key as the running app, otherwise
     *  the system would reject the install (and a tampered download must never
     *  reach the installer). */
    private boolean isSameSignature(File apk) {
        try {
            PackageManager pm = activity.getPackageManager();
            PackageInfo archive = pm.getPackageArchiveInfo(apk.getAbsolutePath(),
                    PackageManager.GET_SIGNING_CERTIFICATES);
            if (archive == null || !activity.getPackageName().equals(archive.packageName)) {
                return false;
            }
            PackageInfo self = pm.getPackageInfo(activity.getPackageName(),
                    PackageManager.GET_SIGNING_CERTIFICATES);
            return sameSigners(self, archive);
        } catch (Exception e) {
            Log.i(TAG, "update signature check failed: " + e);
            return false;
        }
    }

    private static boolean sameSigners(PackageInfo a, PackageInfo b) {
        Signature[] sa = a.signingInfo == null ? null : a.signingInfo.getApkContentsSigners();
        Signature[] sb = b.signingInfo == null ? null : b.signingInfo.getApkContentsSigners();
        if (sa == null || sb == null || sa.length != sb.length) return false;
        List<String> ha = hashes(sa);
        List<String> hb = hashes(sb);
        Collections.sort(ha);
        Collections.sort(hb);
        return ha.equals(hb);
    }

    private static List<String> hashes(Signature[] signatures) {
        List<String> out = new ArrayList<>();
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            for (Signature s : signatures) {
                byte[] d = md.digest(s.toByteArray());
                StringBuilder sb = new StringBuilder(d.length * 2);
                for (byte x : d) sb.append(String.format(Locale.ROOT, "%02x", x));
                out.add(sb.toString());
            }
        } catch (Exception e) {
            Log.i(TAG, "signature hash failed: " + e);
        }
        return out;
    }

    private void launchInstaller(File apk) {
        if (!activity.getPackageManager().canRequestPackageInstalls()) {
            Toast.makeText(activity, R.string.update_install_permission, Toast.LENGTH_LONG).show();
            try {
                activity.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + activity.getPackageName())));
            } catch (Exception ignored) {
            }
            return;
        }
        Uri uri = FileProvider.getUriForFile(activity,
                activity.getPackageName() + ".fileprovider", apk);
        Intent intent = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            activity.startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(activity, R.string.update_install_failed, Toast.LENGTH_SHORT).show();
        }
    }

    private void showProgress() {
        main.post(() -> {
            if (isDead() || progressDialog != null) return;
            float d = activity.getResources().getDisplayMetrics().density;
            int pad = Math.round(20 * d);
            LinearLayout box = new LinearLayout(activity);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setPadding(pad, pad, pad, pad);
            progressBar = new ProgressBar(activity, null,
                    android.R.attr.progressBarStyleHorizontal);
            progressBar.setMax(100);
            progressBar.setIndeterminate(true);
            progressText = new TextView(activity);
            progressText.setText(R.string.update_downloading);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.topMargin = Math.round(8 * d);
            box.addView(progressBar, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));
            box.addView(progressText, lp);
            progressDialog = new AlertDialog.Builder(activity)
                    .setTitle(R.string.update_available)
                    .setView(box)
                    .setCancelable(false)
                    .show();
        });
    }

    private void setProgress(int pct) {
        main.post(() -> {
            if (progressDialog == null || isDead()) return;
            progressBar.setIndeterminate(false);
            progressBar.setProgress(pct);
            progressText.setText(activity.getString(R.string.update_downloading)
                    + " " + pct + "%");
        });
    }

    private void dismissProgress() {
        if (progressDialog != null) {
            progressDialog.dismiss();
            progressDialog = null;
        }
    }

    private void fail(int message) {
        main.post(() -> {
            dismissProgress();
            if (!isDead()) Toast.makeText(activity, message, Toast.LENGTH_LONG).show();
        });
    }

    private boolean isDead() {
        return cancelled || activity.isFinishing() || activity.isDestroyed();
    }

    // ---- versions -----------------------------------------------------------

    private static boolean isNewer(String tag) {
        String t = tag == null ? "" : tag.trim();
        // A release tagged with a plain number ("34") is treated as a
        // versionCode; anything dotted ("v1.17") is compared as a version name.
        if (t.matches("[vV]?\\d+")) {
            try {
                return Integer.parseInt(t.replaceFirst("[vV]", "")) > BuildConfig.VERSION_CODE;
            } catch (NumberFormatException ignored) {
                return false;
            }
        }
        return compareVersions(t, BuildConfig.VERSION_NAME) > 0;
    }

    /** Numeric dotted comparison, ignoring a leading "v" and any suffix such as
     *  "-beta": 1.17 > 1.16, 1.16.1 > 1.16, 2.0 == 2.0.0. */
    private static int compareVersions(String a, String b) {
        List<Integer> pa = numbers(a);
        List<Integer> pb = numbers(b);
        if (pa.isEmpty()) return -1;
        int n = Math.max(pa.size(), pb.size());
        for (int i = 0; i < n; i++) {
            int x = i < pa.size() ? pa.get(i) : 0;
            int y = i < pb.size() ? pb.get(i) : 0;
            if (x != y) return x < y ? -1 : 1;
        }
        return 0;
    }

    private static List<Integer> numbers(String version) {
        List<Integer> out = new ArrayList<>();
        if (version == null) return out;
        Matcher m = Pattern.compile("\\d+").matcher(version);
        while (m.find()) {
            try {
                out.add(Integer.parseInt(m.group()));
            } catch (NumberFormatException ignored) {
            }
        }
        return out;
    }

    private static Release cachedRelease(SharedPreferences sp) {
        String tag = sp.getString("cached_tag", null);
        if (tag == null) return null;
        return new Release(tag, sp.getString("cached_notes", ""),
                sp.getString("cached_apk", null), sp.getString("cached_page", null));
    }

    private static final class Notice {
        final String title;
        final String content;

        Notice(String title, String content) {
            this.title = title;
            this.content = content;
        }
    }

    private static final class Release {
        final String tag;
        final String notes;
        /** APK asset URL, or null when the release has no APK attached. */
        final String apkUrl;
        final String pageUrl;

        Release(String tag, String notes, String apkUrl, String pageUrl) {
            this.tag = tag;
            this.notes = notes;
            this.apkUrl = apkUrl;
            this.pageUrl = pageUrl;
        }
    }
}
