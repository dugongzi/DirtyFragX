package df.root;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

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

    private Dialog updateDialog;
    private Dialog noticeDialog;
    /** The announcement currently on screen, so an update can take over and
     *  put it back once the update dialog is gone. */
    private Notice showingNotice;
    /** An announcement waiting for the update prompt or download to finish. */
    private Notice pendingNotice;

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

    /** Fetches the announcement from every mirror on each launch. Nothing is
     *  cached locally: the first mirror that answers with a notice wins and
     *  the dialog appears right then. */
    void checkAnnouncement() {
        final AtomicBoolean applied = new AtomicBoolean(false);
        for (String url : NOTICE_SOURCES) {
            try {
                noticeNet.execute(() -> {
                    Notice n = fetchNotice(url);
                    if (n == null || n == NO_NOTICE) return;
                    if (applied.compareAndSet(false, true)) showNotice(n);
                });
            } catch (RejectedExecutionException e) {
                // cancel() shut the pool down while the check was starting.
            }
        }
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
            pendingNotice = null;
            showingNotice = null;
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
            if (updateDialog != null && updateDialog.isShowing()) return;
            // The update prompt has priority over the announcement: take the
            // screen from it and bring it back once this dialog is gone.
            if (noticeDialog != null) {
                pendingNotice = showingNotice;
                showingNotice = null;
                Dialog d = noticeDialog;
                noticeDialog = null;
                d.dismiss();
            }
            String notes = r.notes == null ? "" : r.notes.trim();
            if (notes.length() > 600) notes = notes.substring(0, 600) + "…";

            View view = LayoutInflater.from(activity)
                    .inflate(R.layout.dialog_update, null);
            ((TextView) view.findViewById(R.id.updateTitle))
                    .setText(R.string.update_available);
            ((TextView) view.findViewById(R.id.updateVersion))
                    .setText(r.tag == null ? "" : r.tag);
            ((TextView) view.findViewById(R.id.updateNotes)).setText(notes);

            Dialog dialog = new Dialog(activity);
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
            dialog.setContentView(view);
            dialog.setCancelable(true);
            dialog.setCanceledOnTouchOutside(false);
            Window window = dialog.getWindow();
            if (window != null) {
                window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
                window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
                WindowManager.LayoutParams lp = window.getAttributes();
                lp.dimAmount = 0.45f;
                window.setAttributes(lp);
            }
            view.findViewById(R.id.updateLater)
                    .setOnClickListener(v -> dialog.dismiss());
            view.findViewById(R.id.updateNow).setOnClickListener(v -> {
                if (r.apkUrl == null) {
                    dialog.dismiss();
                    openPage(r.pageUrl);
                } else {
                    // The progress lives inside this dialog: the notes and
                    // buttons give way to the bar, and back is blocked until
                    // the download finishes or fails.
                    dialog.setCancelable(false);
                    view.findViewById(R.id.updateButtons).setVisibility(View.GONE);
                    view.findViewById(R.id.updateScroller).setVisibility(View.GONE);
                    view.findViewById(R.id.updateProgressBox).setVisibility(View.VISIBLE);
                    ((TextView) view.findViewById(R.id.updateProgressText))
                            .setText(R.string.update_downloading);
                    ProgressBar bar = view.findViewById(R.id.updateProgress);
                    bar.setProgress(0);
                    bar.setIndeterminate(true);
                    startDownload(r);
                }
            });
            dialog.setOnDismissListener(d -> {
                if (updateDialog == dialog) updateDialog = null;
                pumpDialogs();
            });
            dialog.show();
            if (window != null) sizeDialog(window);
            updateDialog = dialog;
            clampScrollHeight(view.findViewById(R.id.updateScroller));
        });
    }

    /** Shows whatever is waiting once the higher-priority dialogs are gone.
     *  Updates always go first; the announcement follows. */
    private void pumpDialogs() {
        main.post(() -> {
            if (isDead()) return;
            if (updateDialog != null || noticeDialog != null) return;
            if (pendingNotice == null) return;
            Notice next = pendingNotice;
            pendingNotice = null;
            showNotice(next);
        });
    }

    /** Shows the announcement. It is deliberately not cancelable: the only way
     *  past it is the button, and it appears again on the next launch. It never
     *  covers an update prompt or a running download; those go first. */
    private void showNotice(Notice n) {
        main.post(() -> {
            if (isDead()) return;
            if (noticeDialog != null) return;
            if (updateDialog != null) {
                pendingNotice = n;
                return;
            }
            View view = LayoutInflater.from(activity)
                    .inflate(R.layout.dialog_notice, null);
            ((TextView) view.findViewById(R.id.noticeTitle)).setText(n.title);
            ((TextView) view.findViewById(R.id.noticeContent)).setText(n.content);

            Dialog dialog = new Dialog(activity);
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
            dialog.setContentView(view);
            dialog.setCancelable(false);
            dialog.setCanceledOnTouchOutside(false);
            Window window = dialog.getWindow();
            if (window != null) {
                window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
                window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
                WindowManager.LayoutParams lp = window.getAttributes();
                lp.dimAmount = 0.45f;
                window.setAttributes(lp);
            }
            view.findViewById(R.id.noticeOk).setOnClickListener(v -> dialog.dismiss());
            view.findViewById(R.id.noticeCommunity).setOnClickListener(v -> {
                try {
                    activity.startActivity(new Intent(Intent.ACTION_VIEW,
                            Uri.parse(activity.getString(R.string.community_url))));
                } catch (Exception ignored) {
                    // No browser installed; keep the dialog open.
                }
            });
            dialog.setOnDismissListener(d -> {
                if (noticeDialog == dialog) {
                    noticeDialog = null;
                    showingNotice = null;
                }
                pumpDialogs();
            });
            dialog.show();
            if (window != null) sizeDialog(window);
            noticeDialog = dialog;
            showingNotice = n;
            clampScrollHeight(view.findViewById(R.id.noticeScroller));
        });
    }

    /** Keeps a long body inside the screen instead of letting the dialog grow
     *  past the bottom edge; short ones stay wrap_content. */
    private void clampScrollHeight(View scroller) {
        if (!(scroller instanceof ScrollView)) return;
        ScrollView scroll = (ScrollView) scroller;
        scroll.post(() -> {
            int max = Math.round(activity.getResources().getDisplayMetrics()
                    .heightPixels * 0.42f);
            if (scroll.getHeight() <= max) return;
            ViewGroup.LayoutParams lp = scroll.getLayoutParams();
            lp.height = max;
            scroll.setLayoutParams(lp);
        });
    }

    /** Shared window sizing for the custom dialogs: side margins on narrow
     *  screens, a comfortable cap on tablets. */
    private void sizeDialog(Window window) {
        DisplayMetrics dm = activity.getResources().getDisplayMetrics();
        int margin = Math.round(24 * dm.density);
        int maxWidth = Math.round(420 * dm.density);
        int width = Math.min(dm.widthPixels - margin * 2, maxWidth);
        window.setLayout(width, WindowManager.LayoutParams.WRAP_CONTENT);
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
                    if (updateDialog != null) updateDialog.dismiss();
                    launchInstaller(apk);
                });
            });
        } catch (RejectedExecutionException e) {
            // cancel() ran between the dialog tap and the download start.
            resetDownloadUi();
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

    private void setProgress(int pct) {
        main.post(() -> {
            if (updateDialog == null || isDead()) return;
            ProgressBar bar = updateDialog.findViewById(R.id.updateProgress);
            TextView text = updateDialog.findViewById(R.id.updateProgressText);
            if (bar != null) {
                bar.setIndeterminate(false);
                bar.setProgress(pct);
            }
            if (text != null) {
                text.setText(activity.getString(R.string.update_downloading)
                        + " " + pct + "%");
            }
        });
    }

    /** Puts the update dialog back into its normal state after a failed
     *  download, so the user can retry or back out. */
    private void resetDownloadUi() {
        if (updateDialog == null) return;
        updateDialog.setCancelable(true);
        View buttons = updateDialog.findViewById(R.id.updateButtons);
        View scroller = updateDialog.findViewById(R.id.updateScroller);
        View box = updateDialog.findViewById(R.id.updateProgressBox);
        if (buttons != null) buttons.setVisibility(View.VISIBLE);
        if (scroller != null) scroller.setVisibility(View.VISIBLE);
        if (box != null) box.setVisibility(View.GONE);
    }

    private void fail(int message) {
        main.post(() -> {
            resetDownloadUi();
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
