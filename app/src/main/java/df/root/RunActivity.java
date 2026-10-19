package df.root;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableString;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.util.Log;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * Dedicated run screen: a plain log console styled after the KernelSU module
 * installer. The exploit starts automatically when the screen is opened and
 * the top-right icon copies the log.
 */
public class RunActivity extends AppCompatActivity implements IReporter {

    /** Set by MainActivity: run as soon as the screen opens. */
    public static final String EXTRA_AUTO_RUN = "auto_run";

    private static final String TAG = "dfroot";

    /** The log's first line. */
    private static final String LOG_HEADER = "SYSTEM";

    private TextView logView;
    private ScrollView logScroll;

    private final Handler mMain = new Handler(Looper.getMainLooper());
    private final Executor mExec = Executors.newSingleThreadExecutor();
    private final StringBuilder logBuffer = new StringBuilder();
    private File lastLogFile;
    private boolean running;
    /** Raw "***FAILED***: ..." text from the exploit, used for the failure log. */
    private String lastFailReason;

    private Context mDeCtx;

    @Override
    protected void attachBaseContext(Context newBase) {
        SharedPreferences prefs = newBase.createDeviceProtectedStorageContext()
                .getSharedPreferences("dfroot", MODE_PRIVATE);
        java.util.Locale locale = new java.util.Locale(prefs.getString("language", "zh"));
        java.util.Locale.setDefault(locale);
        android.content.res.Configuration config = new android.content.res.Configuration(
                newBase.getResources().getConfiguration());
        config.setLocale(locale);
        super.attachBaseContext(ScaleFix.wrap(newBase.createConfigurationContext(config)));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        applyTheme();
        super.onCreate(savedInstanceState);
        mDeCtx = createDeviceProtectedStorageContext();
        setContentView(R.layout.activity_run);
        logView = findViewById(R.id.logView);
        logScroll = findViewById(R.id.logScroll);

        findViewById(R.id.btnBack).setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            finish();
        });
        findViewById(R.id.btnCopyLog).setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            copyLog();
        });

        lastLogFile = new File(mDeCtx.getFilesDir(), "last_run.log");
        replayLastLog();

        boolean autoRun = getIntent().getBooleanExtra(EXTRA_AUTO_RUN, false);
        if (autoRun && canRun()) startRun();
    }

    private void applyTheme() {
        SharedPreferences prefs = createDeviceProtectedStorageContext()
                .getSharedPreferences("dfroot", MODE_PRIVATE);
        androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(
                prefs.getBoolean("dark_mode", false)
                        ? androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES
                        : androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO);
    }

    /** Shows the log from the previous run, unless it belongs to an older boot. */
    private void replayLastLog() {
        if (lastLogFile == null || !lastLogFile.exists()) return;
        int savedBoot = mDeCtx.getSharedPreferences("dfroot", MODE_PRIVATE)
                .getInt("log_boot_count", -1);
        if (savedBoot != bootCount()) {
            lastLogFile.delete();
            return;
        }
        String last = readLastLog();
        for (String raw : last.split("\n")) {
            String t = stripHeader(raw.trim());
            if (t.isEmpty() || t.equals("LAST RUN") || t.equals("EXPLOIT SUCCESS")) continue;
            appendLog(t);
        }
        scrollToBottom();
    }

    private void startRun() {
        if (running) return;
        if (isRooted()) {
            Toast.makeText(this, R.string.rooted, Toast.LENGTH_SHORT).show();
            return;
        }
        if (!canRun()) {
            Toast.makeText(this, R.string.status_needs_manager, Toast.LENGTH_SHORT).show();
            return;
        }
        running = true;
        logView.setText("");
        logBuffer.setLength(0);
        if (lastLogFile.exists()) lastLogFile.delete();
        mDeCtx.getSharedPreferences("dfroot", MODE_PRIVATE)
                .edit().putBoolean("last_run_success", false).apply();
        appendLog(LOG_HEADER);
        mExec.execute(this::runExploit);
    }

    private void runExploit() {
        // bootstrap.c reads soft_reboot straight out of the DE prefs, so mirror
        // the Autorun card's choice into the key it reads right before launch.
        SharedPreferences sp = mDeCtx.getSharedPreferences("dfroot", MODE_PRIVATE);
        boolean autoSoftReboot = sp.getBoolean("auto_soft_reboot", false);
        sp.edit().putBoolean("soft_reboot", autoSoftReboot).apply();
        lastFailReason = null;
        try {
            int rc = ExploitRunner.run(mDeCtx, this);
            if (rc != 0) {
                // 0 ok, 1 ksud/bootstrap error, 2 poll timeout or bad setup,
                // 3 failed to patch files (see exp.c markers[]).
                String why = lastFailReason != null ? lastFailReason
                        : rc == 1 ? "ksud nonzero exit"
                        : rc == 2 ? "bootstrap timeout - check logcat & dmesg"
                        : "failed to patch files";
                report("\n=== exploit failed: " + why + " ===\n");
            }
            sp.edit().putBoolean("last_run_success", rc == 0).apply();
        } catch (Exception e) {
            Log.e(TAG, "exploit exception", e);
            report("\nexception: " + e + "\n");
        } finally {
            mMain.post(() -> running = false);
        }
    }

    @Override
    public void report(String msg) {
        Log.i(TAG, msg.trim());
        mMain.post(() -> {
            for (String line : msg.split("\n", -1)) {
                String t = line.trim();
                // Skip empty lines to keep the log compact.
                if (t.isEmpty()) continue;
                // Drop byte-progress counters ("0 ?", "512 ?", ...).
                if (t.matches("\\d+\\s*(\\u2026|\\.{3})?")) continue;
                // Capture the failure reason from the raw line before it is filtered.
                captureFailure(t);
                // Drop internal patch/hook details and raw result markers.
                if (t.contains("hook=") || t.equals("ksud start: SUCCESS")
                        || t.contains("ERROR - ")) {
                    continue;
                }
                // Strip hex file offsets: ".../libc.so+0x6e8b0" -> ".../libc.so"
                t = t.replaceAll("\\+0x[0-9a-fA-F]+$", "");
                appendLog(stripHeader(t));
            }
            scrollToBottom();
        });
    }

    private void appendLog(String line) {
        logBuffer.append(line).append('\n');
        saveLog();
        if (logView == null) return;
        logView.append(styleLogLine(line));
        logView.append("\n");
    }

    private void scrollToBottom() {
        if (logScroll == null) return;
        logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
    }

    /** Copies everything currently shown in the log console. */
    private void copyLog() {
        String log = logBuffer.length() > 0 ? logBuffer.toString() : readLastLog();
        if (log.trim().isEmpty()) return;
        android.content.ClipboardManager cm =
                (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (cm == null) return;
        cm.setPrimaryClip(android.content.ClipData.newPlainText("DirtyFragX log", log));
        Toast.makeText(this, R.string.log_copied, Toast.LENGTH_SHORT).show();
    }

    /** v4.0 marker table reports failures as "<stage>: ERROR - <reason>"; keep
     *  that reason for the single failure line the UI synthesizes. */
    private void captureFailure(String t) {
        if (t.isEmpty()) return;
        int err = t.indexOf("ERROR - ");
        if (err >= 0) {
            lastFailReason = t.substring(0, err).trim().toLowerCase()
                    + " failed: " + t.substring(err + "ERROR - ".length()).trim();
        }
    }

    /** "=== setup ===" -> "SETUP"; "=== exploit (patching files) ===" ->
     *  "PATCHING"; "=== exploit failed: x ===" -> "EXPLOIT FAILED: X". */
    private static String stripHeader(String t) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("^===\\s*(.*?)\\s*===$").matcher(t);
        if (!m.matches()) return t;
        String h = m.group(1).toUpperCase();
        if (h.equals("EXPLOIT (PATCHING FILES)")) return "PATCHING";
        return h;
    }

    /** Header lines render big, bold and dark; the rest is dimmed. */
    private boolean isHeader(String line) {
        return line.equals("SETUP") || line.equals("PATCHING")
                || line.equals("EXPLOIT (PATCHING FILES)")
                || line.equals("INIT") || line.equals("CLEANUP")
                || line.startsWith("EXPLOIT FAILED")
                || line.equals(LOG_HEADER);
    }

    private CharSequence styleLogLine(String line) {
        SpannableString ss = new SpannableString(line);
        if (isHeader(line)) {
            ss.setSpan(new StyleSpan(Typeface.BOLD), 0, line.length(), 0);
            ss.setSpan(new RelativeSizeSpan(1.3f), 0, line.length(), 0);
            ss.setSpan(new ForegroundColorSpan(getColor(R.color.text_primary)), 0, line.length(), 0);
        } else {
            ss.setSpan(new ForegroundColorSpan(getColor(R.color.text_secondary)), 0, line.length(), 0);
        }
        return ss;
    }

    private void saveLog() {
        try (FileOutputStream out = new FileOutputStream(lastLogFile)) {
            out.write(logBuffer.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) {
        }
        mDeCtx.getSharedPreferences("dfroot", MODE_PRIVATE)
                .edit().putInt("log_boot_count", bootCount()).apply();
    }

    private int bootCount() {
        try {
            return android.provider.Settings.Global.getInt(getContentResolver(),
                    android.provider.Settings.Global.BOOT_COUNT, -1);
        } catch (Exception e) {
            return -1;
        }
    }

    private String readLastLog() {
        if (lastLogFile == null || !lastLogFile.exists()) return "";
        try (FileInputStream in = new FileInputStream(lastLogFile);
             ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            for (int n; (n = in.read(buf)) != -1; ) bos.write(buf, 0, n);
            return bos.toString(StandardCharsets.UTF_8.name()).trim();
        } catch (IOException e) {
            return "";
        }
    }

    /** /dev/df is created with mode 0000; /dev/dfm6 is touched only after
     *  `ksud late-load` succeeded, which is the actual root marker. */
    private static boolean isRooted() {
        return new File("/dev/df").exists() && new File("/dev/dfm6").exists();
    }

    /** Run needs a manager selected and no existing root. */
    private boolean canRun() {
        return mDeCtx.getSharedPreferences("dfroot", MODE_PRIVATE)
                .getString("su_manager", null) != null && !isRooted();
    }
}
