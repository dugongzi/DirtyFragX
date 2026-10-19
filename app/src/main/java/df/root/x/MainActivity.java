package df.root;

import android.content.Context;
import android.content.ComponentName;
import android.content.ContentValues;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.text.SpannableString;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.util.Log;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.widget.TextView;
import androidx.core.graphics.ColorUtils;
import androidx.appcompat.app.AppCompatActivity;

import df.root.databinding.ActivityMainBinding;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity implements IReporter {

    private static final String TAG = "dfroot";

    /** Stage indices of binding.twoStep - see initStepper(). */
    private static final int STAGE_VERIFIED = 5;

    private ActivityMainBinding binding;
    /** The two pages live in their own layouts now, so their controls are reached
     *  through the fragment view that currently holds them. Null until that page
     *  has been created by the pager. */
    private View homeView;
    private View settingsView;
    private final Handler mMain = new Handler(Looper.getMainLooper());
    private final Executor mExec = Executors.newSingleThreadExecutor();
    private final StringBuilder logBuffer = new StringBuilder();
    private File lastLogFile;
    private boolean running;
    private boolean runArmed;
    private boolean advancedLog;
    private boolean expertMode;
    private String exploitPhase = "";
    private int cleanupSteps;
    private float seg1;
    private int selectedNav = R.id.nav_home;
    private boolean moduleRefresh;
    private boolean mBootReceiverEnabled;
    /** Stage chain state restored from the previous run, applied when the home
     *  view appears: STAGE_VERIFIED for a finished run, -1 for none. */
    private int mRestoredStage = -1;
    private boolean mLastFailed;

    /** Device-protected storage context: bootstrap.c reads its prefs from
     *  /data/user_de/0/df.root/, so every pref the exploit depends on must live
     *  there, not in credential-protected storage. */
    private Context mDeCtx;
    /** Package name of the SU manager whose libksud.so gets staged, or null. */
    private String suManagerPkg;
    private CharSequence suManagerLabel;
    /** A failed run locks the Run button until reboot (page-cache patch). */
    private boolean failedRun;
    /** Raw "***FAILED***: ..." text from the exploit, used for the failure log. */
    private String lastFailReason;

    private static final long LOG_FADE_MS = 180L;
    private boolean logAllowed;

    /** Called by HomeFragment once its layout exists. */
    @SuppressWarnings("unchecked")
    <T extends View> T home(int id) {
        return homeView == null ? null : (T) homeView.findViewById(id);
    }

    /** Called by SettingsFragment once its layout exists. */
    @SuppressWarnings("unchecked")
    <T extends View> T settings(int id) {
        return settingsView == null ? null : (T) settingsView.findViewById(id);
    }

    /** HomeFragment's layout is live: bind what belongs to the home page. */
    void onHomeViewReady(View v) {
        homeView = v;
        initHomeControls();
        applyRestoredHomeState();
    }

    /** SettingsFragment's layout is live: bind what belongs to settings. */
    void onSettingsViewReady(View v) {
        settingsView = v;
        initSettingsControls();
    }

    @Override
    public void report(String msg) {
        Log.i(TAG, msg.trim());
        mMain.post(() -> {
            // Drive the two-step progress bar from the raw (unfiltered) lines.
            for (String line : msg.split("\n", -1)) {
                driveProgress(line.trim());
            }
            for (String line : msg.split("\n", -1)) {
                String t = line.trim();
                // Skip empty lines to keep the log compact.
                if (t.isEmpty()) {
                    continue;
                }
                // Drop byte-progress counters ("0 ?", "512 ?", ...).
                if (t.matches("\\d+\\s*(\\u2026|\\.{3})?")) {
                    continue;
                }
                // Drop internal patch/hook details and raw result markers: the
                // app renders its own SETUP/EXPLOIT/INIT/CLEANUP headers and
                // synthesizes a single failure line instead of the raw one.
                if (t.contains("hook=") || t.equals("ksud start: SUCCESS")
                        || t.contains("ERROR - ")) {
                    continue;
                }
                // Strip hex file offsets: ".../libc.so+0x6e8b0" -> ".../libc.so"
                t = t.replaceAll("\\+0x[0-9a-fA-F]+$", "");
                t = stripHeader(t);
                appendLog(t);
            }
            android.widget.ScrollView scroll = home(R.id.outputScroll);
            if (scroll != null) scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
        });
    }

    private void appendLog(String line) {
        TextView out = home(R.id.outputView);
        if (out == null) return;
        out.append(styleLogLine(line));
        out.append("\n");
        logBuffer.append(line).append('\n');
        saveLog();
    }

    /** Translates raw native log lines into two-step progress-bar states.
     *  STRICTLY LINEAR: seg 1 (the exploit run) climbs to 100% and only then does seg 2
     *  (verification) start climbing. The two bars never move at the same time.
     *  Seg 1 tracks setup + file patching + the hook firing; seg 2 tracks the bootstrap,
     *  which reports progress by touching /dev/dfm* nodes that exp.c polls for 70 seconds. */
    private void driveProgress(String t) {
        if (t.isEmpty()) return;
        // v4.0 marker table: a failure is reported as "<stage>: ERROR - <reason>".
        int err = t.indexOf("ERROR - ");
        if (err >= 0) {
            lastFailReason = t.substring(err + "ERROR - ".length()).trim();
        }
        switch (t) {
            case "=== setup ===":
                exploitPhase = "setup";
                setSeg1(0.05f);
                break;
            case "=== exploit (patching files) ===":
                exploitPhase = "exploit";
                setSeg1(0.15f);
                break;
            case "=== init  ===":
                // The run bar is nearly done; the verification bar stays EMPTY until the
                // module is actually in and the exploit bar has read 100%.
                exploitPhase = "init";
                setSeg1(0.98f);
                break;
            case "=== cleanup ===":
                exploitPhase = "cleanup";
                cleanupSteps = 0;
                break;
            default:
                break;
        }
        if (exploitPhase.equals("setup") && t.startsWith("found ko_target:")) {
            setSeg1(0.10f);
        }
        if (exploitPhase.equals("exploit")) {
            if (t.startsWith("patch: crash_dump64")) {
                setSeg1(0.30f);
            } else if (t.contains("<- dfroot.ko")) {
                setSeg1(0.50f);
            } else if (t.startsWith("Finding symbol offsets")) {
                setSeg1(0.60f);
            } else if (t.contains("<- shellcode")) {
                setSeg1(0.75f);
            } else if (t.contains("<- trampoline")) {
                setSeg1(0.90f);
            } else if (t.startsWith("Triggering hook")) {
                setSeg1(0.95f);
            }
        }
        // Run bar finishes here - the module is in, nothing left to patch.
        if (t.startsWith("libc++: loading custom module")) {
            setSeg1(1f);
        }
        // Verification bar: one step per bootstrap marker (see exp.c markers[]).
        // v4.0 renamed every marker and dropped the "env adopted" / "partitions set
        // ro" confirmations, so these steps are re-keyed to the new table.
        if (t.startsWith("kernel module: launching bootstrap")) {
            setSeg2(0.14f, R.string.verification);
        }
        if (t.startsWith("bootstrap: loading app preferences file")) {
            setSeg2(0.28f, R.string.verification);
        }
        if (t.startsWith("bootstrap: cloning zygote env")
                || t.startsWith("bootstrap: WARNING - clone zygote env failed")) {
            setSeg2(0.42f, R.string.verification);
        }
        if (t.startsWith("bootstrap: setting partitions ro")
                || t.startsWith("bootstrap: WARNING - set partitions ro failed")) {
            setSeg2(0.57f, R.string.verification);
        }
        if (t.startsWith("bootstrap: disabling ksu modules")) {
            setSeg2(0.71f, R.string.verification);
        }
        if (t.startsWith("bootstrap: starting SU daemon")) {
            setSeg2(0.85f, R.string.verification);
        }
        if (t.equals("ksud start: SUCCESS")) {
            setSeg1(1f);
            setSeg2(1f, R.string.verified);
        }
        if (exploitPhase.equals("cleanup") && t.startsWith("restoring")) {
            cleanupSteps++;
        }
    }

    private void initStepper() {
        StageStepperView stepper = home(R.id.twoStep);
        if (stepper == null) return;
        stepper.setStages(
                getString(R.string.stage_setup),
                getString(R.string.stage_patch),
                getString(R.string.stage_init),
                getString(R.string.stage_bootstrap),
                getString(R.string.stage_daemon),
                getString(R.string.stage_verified));
    }

    private void setSeg1(float p) {
        seg1 = p;
        // The home view appears after the pager inflates the page; the restored
        // stage is replayed in applyRestoredHomeState.
        mRestoredStage = p <= 0f ? -1 : p < 0.10f ? 0 : p < 0.95f ? 1 : 2;
        StageStepperView stepper = home(R.id.twoStep);
        if (mRestoredStage < 0 || stepper == null) return;
        stepper.setStage(mRestoredStage);
    }

    private void setSeg2(float p, int label) {
        if (p >= 1f) {
            mRestoredStage = STAGE_VERIFIED;
        } else {
            // Six bootstrap markers (0.14 .. 0.85) map onto the last three stages.
            float q = Math.max(0f, Math.min(1f, p));
            mRestoredStage = q < 0.28f ? 3 : q < 0.71f ? 4 : 5;
        }
        StageStepperView stepper = home(R.id.twoStep);
        if (stepper != null) stepper.setStage(mRestoredStage);
    }

    /** Turn the advanced log on or off from anywhere - the switch, a card being
     *  lifted, the saved setting at startup.
     *  OneUiSwitch is drawn from scratch and only reports a change when a finger
     *  clicks it: quietly calling setChecked() moves the thumb and tells nobody,
     *  which left the pill showing off while the log went on behaving as if it
     *  were still on. Everything therefore comes through here. */
    private void setAdvancedLog(boolean on) {
        advancedLog = on;
        android.widget.CompoundButton sw = settings(R.id.switchAdvancedLog);
        if (sw != null) sw.setChecked(on);
        createDeviceProtectedStorageContext()
                .getSharedPreferences("dfroot", MODE_PRIVATE)
                .edit().putBoolean("advanced_log", on).apply();
        updateLogVisibility();
    }

    /** Visibility of log / share button / progress bar, composed from the
     *  advanced-log setting and whether any run data exists. The log block
     *  needs the switch; the share circle does not - a finished run can always
     *  be saved. */
    private void updateLogVisibility() {
        boolean hasRun = logBuffer.length() > 0
                || (lastLogFile != null && lastLogFile.exists());
        logAllowed = advancedLog && hasRun;
        fadeLog(logAllowed);
        // Share circle: any FINISHED run, advanced log on or off. Still hidden
        // while a run is in flight (the log is being written) and shown for a
        // saved log from a previous boot, which is finished by definition.
        setShareButtonVisible(hasRun && !running);
        View stepper = home(R.id.twoStep);
        if (stepper != null) stepper.setVisibility(View.VISIBLE);
    }

    /**
     * Ease the log box in or away (VIS2.1, VIS4). The log has half a dozen
     * reasons to appear or vanish - the switch, a run starting, a card being
     * lifted, that card being collapsed - and every one of them used to flip the
     * views between GONE and VISIBLE like a light switch, which read as a flash.
     * It now moves on one alpha, and a log that is fading out only leaves the
     * layout once there is nothing left of it to see.
     * One alpha for one box: the text and the gradient over it are the same view
     * since VIS4, so there is nothing left that could fall a frame behind.
     */
    private void fadeLog(boolean show) {
        final View box = home(R.id.logWindow);
        if (box == null) return;
        boolean visible = box.getVisibility() == View.VISIBLE;
        float target = show ? 1f : 0f;
        if (visible == show && box.getAlpha() == target) return;
        box.animate().cancel();
        if (show) box.setVisibility(View.VISIBLE);
        box.animate().alpha(target).setDuration(LOG_FADE_MS)
                .withEndAction(() -> {
                    if (box.getAlpha() <= 0f) box.setVisibility(View.GONE);
                })
                .start();
    }

    /** Pop the share circle in/out with the same animation the KernelSU
     *  launcher circle uses (fade + 0.6x -> 1x scale, 150ms delay). */
    private void setShareButtonVisible(boolean show) {
        android.widget.ImageButton b = home(R.id.btnShareLog);
        if (b == null) return;
        boolean wasVisible = b.getVisibility() == View.VISIBLE
                && b.getAlpha() > 0.99f;
        if (show && !wasVisible) {
            b.setVisibility(View.VISIBLE);
            b.setAlpha(0f);
            b.setScaleX(0.6f);
            b.setScaleY(0.6f);
            b.postDelayed(() -> b.animate()
                    .alpha(1f).scaleX(1f).scaleY(1f).setDuration(180).start(), 150);
        } else if (show) {
            b.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(120).start();
        } else if (wasVisible) {
            b.animate().alpha(0f).scaleX(0.6f).scaleY(0.6f).setDuration(200)
                    .withEndAction(() -> b.setVisibility(View.GONE)).start();
        }
    }

    /** "=== setup ===" -> "SETUP"; "=== exploit (patching files) ===" ->
     *  "PATCHING"; "=== exploit failed: x ===" -> "EXPLOIT FAILED: X". */
    private static String stripHeader(String t) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("^===\\s*(.*?)\\s*===$").matcher(t);
        if (!m.matches()) return t;
        String h = m.group(1).toUpperCase();
        // The native binary still prints "=== exploit (patching files) ===";
        // the UI shows the short form.
        if (h.equals("EXPLOIT (PATCHING FILES)")) return "PATCHING";
        return h;
    }

    /** The log's first line. Deliberately generic: the device banner that
     *  ExploitRunner prints immediately below it already carries manufacturer,
     *  model, Android version, patch level and kernel version, so the old
     *  model-prefixed firmware token ("S931BXXU1AYB2") was redundant noise. */
    private static final String LOG_HEADER = "SYSTEM";

    /** Header lines render big, white and bold; the rest is dimmed. */
    private boolean isHeader(String line) {
        return line.equals("SETUP") || line.equals("PATCHING")
                || line.equals("EXPLOIT (PATCHING FILES)")
                || line.equals("INIT") || line.equals("CLEANUP")
                || line.startsWith("EXPLOIT FAILED")
                || line.equals(LOG_HEADER);
    }

    /** Header lines render big, white and bold; the rest is dimmed. */
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
            out.write(logBuffer.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (IOException ignored) {
        }
        // Remember which boot this log came from - it is invalidated on reboot.
        createDeviceProtectedStorageContext()
                .getSharedPreferences("dfroot", MODE_PRIVATE)
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
        try (java.io.FileInputStream in = new java.io.FileInputStream(lastLogFile);
             java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            for (int n; (n = in.read(buf)) != -1; ) bos.write(buf, 0, n);
            String log = bos.toString(java.nio.charset.StandardCharsets.UTF_8.name()).trim();
            // Normalize exploit-result headers to lowercase (older runs saved
            // them uppercase); the file gets rewritten on next save.
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("(===\\s*exploit\\s+(?:success|failed[^=]*)\\s*===)",
                            java.util.regex.Pattern.CASE_INSENSITIVE)
                    .matcher(log);
            StringBuilder sb = new StringBuilder();
            int last = 0;
            while (m.find()) {
                sb.append(log, last, m.start())
                        .append(m.group().toLowerCase(java.util.Locale.ROOT));
                last = m.end();
            }
            sb.append(log, last, log.length());
            return sb.toString();
        } catch (IOException e) {
            return "";
        }
    }

    /**
     * Screen zoom and Font size are locked for the duration of the activity:
     * the pill, toolbar and card geometry were all measured at one screen
     * width in dp and stop agreeing with each other as soon as it moves. See
     * {@link ScaleFix} for why the anchor is a width and not a dpi value.
     */
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
        applyThemeAndLanguage();
        super.onCreate(savedInstanceState);
        mDeCtx = createDeviceProtectedStorageContext();
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        // Setup ViewPager2 with fragments
        MainPagerAdapter pagerAdapter = new MainPagerAdapter(this);
        binding.viewPager.setAdapter(pagerAdapter);
        binding.viewPager.setUserInputEnabled(true);

        failedRun = savedInstanceState != null && savedInstanceState.getBoolean("failed_run", false);

        // The header is the app's name and nothing else. The version number that
        // used to ride next to it is still recorded, just not in the one line of
        // chrome the user has to look past to reach the cards: on every start here,
        // and in the run log as "app version: ..." (ExploitRunner). What takes the
        // headline instead is the update pill, but only once the check below has
        // something to say - see showUpdatePill.
        Log.i(TAG, "version " + BuildConfig.VERSION_NAME);
        // NOTE: no app bar - the home page carries its own headline, so there is
        // no toolbar title, update pill or header controls to keep in sync.

        // Update check: removed update pill UI

        // SU Manager card: upstream 3.2 deleted the bundled assets/ksud, so the
        // exploit stages libksud.so out of the chosen manager's
        // nativeLibraryDir and refuses to run without one. Only apps that
        // actually ship that library are offered.
        loadSuManagerPref();

        if (new File("/dev/df").exists()) {
            setRootedState();
        }

        // Show the log from the last run, if any. No run yet -> log hidden.
        // A log saved before the current boot is stale: delete it so a reboot
        // always starts with a clean screen (log + share icon hidden).
        lastLogFile = new File(createDeviceProtectedStorageContext().getFilesDir(), "last_run.log");
        int savedBoot = createDeviceProtectedStorageContext()
                .getSharedPreferences("dfroot", MODE_PRIVATE).getInt("log_boot_count", -1);
        if (lastLogFile.exists() && savedBoot != bootCount()) {
            lastLogFile.delete();
            createDeviceProtectedStorageContext()
                    .getSharedPreferences("dfroot", MODE_PRIVATE)
                    .edit().putBoolean("last_run_success", false).apply();
        }
        String last = readLastLog();
        boolean hasLastLog = !last.isEmpty();
        if (hasLastLog) {
            appendLog(LOG_HEADER);
            for (String l : last.split("\n")) {
                String t = stripHeader(l.trim());
                // Skip stale headers, old result lines and duplicate header lines.
                if (t.isEmpty()
                        || t.equals("LAST RUN")
                        || t.equals("EXPLOIT SUCCESS")
                        || t.equals(LOG_HEADER)) {
                    continue;
                }
                appendLog(t);
            }
        }

        // Advanced log toggle: full log vs. simple status (progress bar only).
        advancedLog = createDeviceProtectedStorageContext()
                .getSharedPreferences("dfroot", MODE_PRIVATE)
                .getBoolean("advanced_log", false);
        updateLogVisibility();

        // Restore the simple status for the current state: rooted device or a
        // successful last run -> verified; failed run -> Failed. Applied once the
        // home view exists (see applyRestoredHomeState).
        boolean rootedNow = new File("/dev/df").exists();
        boolean lastSuccess = hasLastLog
                && createDeviceProtectedStorageContext()
                        .getSharedPreferences("dfroot", MODE_PRIVATE)
                        .getBoolean("last_run_success", false);
        mLastFailed = hasLastLog && last.toLowerCase().contains("=== exploit failed");
        mRestoredStage = (rootedNow || lastSuccess) ? STAGE_VERIFIED : -1;

        ComponentName bootReceiver = new ComponentName(this, BootReceiver.class);
        mBootReceiverEnabled = getPackageManager().getComponentEnabledSetting(bootReceiver)
                == PackageManager.COMPONENT_ENABLED_STATE_ENABLED;

        // Expert mode gates the boot options in settings.
        expertMode = createDeviceProtectedStorageContext()
                .getSharedPreferences("dfroot", MODE_PRIVATE)
                .getBoolean("expert_mode", false);

        selectedNav = savedInstanceState == null ? R.id.nav_home
                : savedInstanceState.getInt("selected_nav", R.id.nav_home);
        
        // Sync BottomNavigation with ViewPager2
        binding.viewPager.registerOnPageChangeCallback(new androidx.viewpager2.widget.ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                selectedNav = position == 0 ? R.id.nav_home : R.id.nav_settings;
                binding.bottomNavigation.setSelectedItemId(selectedNav);
            }
        });
        
        binding.bottomNavigation.setOnItemSelectedListener(item -> {
            if (item.getItemId() == R.id.nav_home) {
                binding.viewPager.setCurrentItem(0, true);
                selectedNav = R.id.nav_home;
                return true;
            } else if (item.getItemId() == R.id.nav_settings) {
                binding.viewPager.setCurrentItem(1, true);
                selectedNav = R.id.nav_settings;
                return true;
            }
            return false;
        });
        
        // Set initial page
        binding.viewPager.setCurrentItem(selectedNav == R.id.nav_home ? 0 : 1, false);
        binding.bottomNavigation.setSelectedItemId(selectedNav);

        // Run stays locked until an SU manager is selected (and, after a failed
        // run, until the device has been rebooted).
        updateRunButton();
    }

    /** Home page bindings. Runs once HomeFragment's view exists. */
    private void initHomeControls() {
        View row = home(R.id.rowSuManager);
        if (row != null) {
            row.setOnClickListener(v -> {
                v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                showSuManagerPopup();
            });
        }

        View run = home(R.id.btnRun);
        if (run != null) {
            run.setOnClickListener(v -> {
                if (running || !canRun()) return;
                // Two-tap confirmation: first tap arms, second runs.
                if (!runArmed) {
                    runArmed = true;
                    v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                    showRunningBadge();
                    if (v instanceof TextView) ((TextView) v).setText(R.string.confirm_run);
                    return;
                }
                startRun();
            });
            // Subtle push-in
            run.setOnTouchListener((v, ev) -> {
                switch (ev.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                        v.animate().scaleX(0.96f).scaleY(0.96f).setDuration(80).start();
                        break;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        v.animate().scaleX(1f).scaleY(1f).setDuration(120).start();
                        break;
                    default:
                        break;
                }
                return false;
            });
        }

        View ksu = home(R.id.btnKsu);
        if (ksu != null) {
            ksu.setOnClickListener(v -> {
                v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                openKsu();
            });
        }

        View share = home(R.id.btnShareLog);
        if (share != null) share.setOnClickListener(v -> shareLog());

        initStepper();
        updateLogVisibility();
        updateRunButton();
    }

    /** Applies the state of the previous run to the stage chain and status card
     *  once the home view exists. */
    private void applyRestoredHomeState() {
        StageStepperView stepper = home(R.id.twoStep);
        if (stepper == null) return;
        if (mRestoredStage >= 0) {
            stepper.setStage(mRestoredStage);
            updateRunButton();
        } else if (mLastFailed) {
            setFailedState();
        }
    }

    /** Settings page bindings. Runs once SettingsFragment's view exists. */
    private void initSettingsControls() {
        // KSU modules toggle: since 3.2 this is a plain preference, not a root
        // operation. bootstrap.c reads disable_modules and touches a `disable`
        // flag file in every installed module before ksud runs, so the toggle
        // works before the device is rooted and applies on the next root.
        android.widget.CompoundButton modules = settings(R.id.switchModules);
        if (modules != null) {
            moduleRefresh = true;
            modules.setChecked(!mDeCtx.getSharedPreferences("dfroot", MODE_PRIVATE)
                    .getBoolean("disable_modules", false));
            moduleRefresh = false;
            View subtitle = settings(R.id.modulesSubtitle);
            if (subtitle instanceof TextView) {
                ((TextView) subtitle).setText(modules.isChecked()
                        ? R.string.modules_enabled : R.string.modules_disabled);
            }
            modules.setOnCheckedChangeListener((btn, on) -> {
                if (moduleRefresh) return;
                btn.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                applyModuleState(on);
            });
        }

        android.widget.CompoundButton expert = settings(R.id.switchExpertMode);
        if (expert != null) {
            expert.setChecked(expertMode);
            expert.setOnCheckedChangeListener((btn, checked) -> {
                expertMode = checked;
                mDeCtx.getSharedPreferences("dfroot", MODE_PRIVATE).edit()
                        .putBoolean("expert_mode", checked).apply();
                applyExpertMode();
            });
        }

        ComponentName bootReceiver = new ComponentName(this, BootReceiver.class);
        android.widget.CompoundButton boot = settings(R.id.switchBootStart);
        if (boot != null) {
            boot.setChecked(mBootReceiverEnabled);
            boot.setOnCheckedChangeListener((btn, checked) -> {
                getPackageManager().setComponentEnabledSetting(bootReceiver,
                        checked ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                                : PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                        PackageManager.DONT_KILL_APP);
                android.widget.CompoundButton soft = settings(R.id.switchAutoSoftReboot);
                if (soft != null) soft.setEnabled(expertMode && checked);
            });
        }

        boolean autoSoftReboot = createDeviceProtectedStorageContext()
                .getSharedPreferences("dfroot", MODE_PRIVATE)
                .getBoolean("auto_soft_reboot", false);
        android.widget.CompoundButton soft = settings(R.id.switchAutoSoftReboot);
        if (soft != null) {
            soft.setChecked(autoSoftReboot);
            soft.setEnabled(mBootReceiverEnabled);
            soft.setOnCheckedChangeListener((btn, checked) ->
                    createDeviceProtectedStorageContext()
                            .getSharedPreferences("dfroot", MODE_PRIVATE)
                            .edit().putBoolean("auto_soft_reboot", checked).apply());
        }

        applyExpertMode();
        setupAppearanceSelectors();

        View[] links = {
                settings(R.id.repoOriginalLabel), settings(R.id.repoOriginalUrl)};
        for (View link : links) {
            if (link != null) link.setOnClickListener(v ->
                    openUrl(getString(R.string.repo_original_url)));
        }
        View[] fork = {
                settings(R.id.repoForkLabel), settings(R.id.repoForkUrl)};
        for (View link : fork) {
            if (link != null) link.setOnClickListener(v ->
                    openUrl(getString(R.string.repo_fork_url)));
        }

        setAdvancedLog(advancedLog);

        android.widget.CompoundButton adv = settings(R.id.switchAdvancedLog);
        if (adv != null) {
            adv.setOnCheckedChangeListener((btn, checked) -> setAdvancedLog(checked));
        }
    }

    /** Kick off the exploit: lock the UI, clear the old log, hand off to the worker. */
    private void startRun() {
        running = true;
        runArmed = false;
        setAppearanceSelectorsEnabled(false);
        View run = home(R.id.btnRun);
        if (run != null) {
            run.setEnabled(false);
            run.setAlpha(0.4f);
            if (run instanceof TextView) ((TextView) run).setText(R.string.running);
        }
        setStatusCard(R.color.accent_cyan, R.drawable.ic_play, R.string.running);
        TextView out = home(R.id.outputView);
        if (out != null) out.setText("");
        logBuffer.setLength(0);
        if (lastLogFile != null && lastLogFile.exists()) lastLogFile.delete();
        createDeviceProtectedStorageContext()
                .getSharedPreferences("dfroot", MODE_PRIVATE)
                .edit().putBoolean("last_run_success", false).apply();
        appendLog(LOG_HEADER);
        StageStepperView stepper = home(R.id.twoStep);
        if (stepper != null) stepper.reset();
        updateLogVisibility();
        mExec.execute(this::runExploit);
    }

    /** Expert mode off: the autorun card is inaccessible - greyed text and
     *  disabled toggles, titles suffixed with (Expert). */
    private void applyExpertMode() {
        boolean ok = expertMode;
        TextView autorunTitle = settings(R.id.tvAutorunTitle);
        TextView rebootTitle = settings(R.id.tvRebootTitle);
        TextView autorunDesc = settings(R.id.tvAutorunDesc);
        TextView rebootDesc = settings(R.id.tvRebootDesc);
        android.widget.CompoundButton boot = settings(R.id.switchBootStart);
        android.widget.CompoundButton soft = settings(R.id.switchAutoSoftReboot);
        if (autorunTitle != null) {
            autorunTitle.setText(ok ? R.string.autorun : R.string.autorun_expert);
            autorunTitle.setTextColor(getColor(ok ? R.color.text_primary : R.color.text_secondary));
        }
        if (rebootTitle != null) {
            rebootTitle.setText(ok ? R.string.auto_reboot : R.string.auto_reboot_expert);
            rebootTitle.setTextColor(getColor(ok ? R.color.text_primary : R.color.text_secondary));
        }
        if (autorunDesc != null) autorunDesc.setTextColor(getColor(R.color.text_secondary));
        if (rebootDesc != null) rebootDesc.setTextColor(getColor(R.color.text_secondary));
        if (boot != null) boot.setEnabled(ok);
        if (soft != null) soft.setEnabled(ok && boot != null && boot.isChecked());
    }

    /** Re-read the SU manager selection when returning to the app: the user may
     *  have installed or uninstalled a manager while we were in the background,
     *  and the Run button has to reflect that immediately. */
    @Override
    protected void onResume() {
        super.onResume();
        loadSuManagerPref();
        updateRunButton();
    }

    private void setRootedState() {
        setRootedState(true);
    }

    /** Applies the Modules card. Since 3.2 this is a preference write, not a
     *  root operation: bootstrap.c reads disable_modules and touches a
     *  `disable` flag file in every installed module before it starts ksud
     *  (a broken module otherwise bootloops the device). OFF = modules stay
     *  disabled on the next root, ON = modules load. No su required. */
    private void applyModuleState(boolean on) {
        SharedPreferences sp = mDeCtx.getSharedPreferences("dfroot", MODE_PRIVATE);
        SharedPreferences.Editor ed = sp.edit();
        if (on) ed.remove("disable_modules");
        else ed.putBoolean("disable_modules", true);
        ed.apply();
        // The subtitle is the whole message. Nothing is announced over the bottom
        // of the screen for a switch the user can already see move.
        View subtitle = settings(R.id.modulesSubtitle);
        if (subtitle instanceof TextView) {
            ((TextView) subtitle).setText(on
                    ? R.string.modules_enabled : R.string.modules_disabled);
        }
    }

    // ---- SU manager picker --------------------------------------------------

    /** One candidate: an app that actually ships libksud.so. */
    private static final class SuManagerEntry {
        final String packageName;
        final CharSequence label;

        SuManagerEntry(String pkg, CharSequence label) {
            this.packageName = pkg;
            this.label = label;
        }
    }

    /** Every installed app carrying libksud.so in its native lib dir - exactly
     *  the set ExploitRunner.stageAssets() can use, so anything listed here is
     *  guaranteed to pass the runtime check too. */
    private List<SuManagerEntry> scanSuManagers() {
        List<SuManagerEntry> out = new ArrayList<>();
        PackageManager pm = getPackageManager();
        for (ApplicationInfo ai : pm.getInstalledApplications(0)) {
            if (ai.packageName.equals(getPackageName())) continue;
            if (ai.nativeLibraryDir == null) continue;
            if (!new File(ai.nativeLibraryDir, "libksud.so").exists()) continue;
            out.add(new SuManagerEntry(ai.packageName, pm.getApplicationLabel(ai)));
        }
        out.sort((a, b) -> a.label.toString().compareToIgnoreCase(b.label.toString()));
        return out;
    }

    /** Restores the saved selection, dropping it if that app is gone or no
     *  longer ships libksud.so. */
    private void loadSuManagerPref() {
        SharedPreferences sp = mDeCtx.getSharedPreferences("dfroot", MODE_PRIVATE);
        String saved = sp.getString("su_manager", null);
        suManagerPkg = null;
        suManagerLabel = null;
        if (saved != null) {
            for (SuManagerEntry e : scanSuManagers()) {
                if (e.packageName.equals(saved)) {
                    suManagerPkg = e.packageName;
                    suManagerLabel = e.label;
                    break;
                }
            }
            if (suManagerPkg == null) sp.edit().remove("su_manager").apply();
        }
        TextView sub = home(R.id.suManagerSubtitle);
        if (sub != null) {
            sub.setText(suManagerPkg == null
                    ? getString(R.string.su_required)
                    : suManagerLabel + "  (" + suManagerPkg + ")");
            sub.setTextColor(getColor(suManagerPkg == null
                    ? R.color.error : R.color.text_secondary));
        }
    }

    /** OneUI-style rounded popup list over the dim overlay - same look and feel
     *  as the overflow menu, so the picker does not look like a stock spinner. */
    private void showSuManagerPopup() {
        float md = getResources().getDisplayMetrics().density;
        List<SuManagerEntry> apps = scanSuManagers();
        final android.widget.PopupWindow[] pwRef = {null};

        android.widget.LinearLayout box = new android.widget.LinearLayout(this);
        box.setOrientation(android.widget.LinearLayout.VERTICAL);
        box.setBackgroundResource(R.drawable.popup_bg);
        box.setPadding(0, (int) (6 * md), 0, (int) (6 * md));

        if (apps.isEmpty()) {
            TextView none = new TextView(this);
            none.setText(R.string.su_none);
            none.setGravity(android.view.Gravity.CENTER_VERTICAL | android.view.Gravity.START);
            none.setPadding((int) (20 * md), (int) (12 * md), (int) (20 * md), (int) (12 * md));
            none.setTextColor(getColor(R.color.text_secondary));
            none.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15);
            box.addView(none);
        }

        for (SuManagerEntry e : apps) {
            android.widget.LinearLayout col = new android.widget.LinearLayout(this);
            col.setOrientation(android.widget.LinearLayout.VERTICAL);
            col.setGravity(android.view.Gravity.CENTER_VERTICAL | android.view.Gravity.START);
            col.setBackgroundResource(R.drawable.menu_row_highlight);
            col.setPadding((int) (20 * md), 0, (int) (20 * md), 0);

            android.widget.LinearLayout line = new android.widget.LinearLayout(this);
            line.setOrientation(android.widget.LinearLayout.HORIZONTAL);
            line.setGravity(android.view.Gravity.CENTER_VERTICAL | android.view.Gravity.START);
            TextView t = new TextView(this);
            t.setText(e.label);
            t.setTextColor(getColor(R.color.text_primary));
            t.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15);
            line.addView(t, new android.widget.LinearLayout.LayoutParams(
                    0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            if (e.packageName.equals(suManagerPkg)) {
                android.view.View dot = new android.view.View(this);
                dot.setBackgroundResource(R.drawable.dot_white);
                android.widget.LinearLayout.LayoutParams dotLp =
                        new android.widget.LinearLayout.LayoutParams(
                                (int) (7 * md), (int) (7 * md));
                dotLp.setMargins((int) (7 * md), 0, 0, 0);
                line.addView(dot, dotLp);
            }
            col.addView(line, new android.widget.LinearLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT, (int) (26 * md)));

            TextView sub = new TextView(this);
            sub.setText(e.packageName);
            sub.setTextColor(getColor(R.color.text_secondary));
            sub.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 11);
            col.addView(sub);

            col.setOnClickListener(v2 -> {
                v2.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                selectSuManager(e);
                if (pwRef[0] != null) pwRef[0].dismiss();
            });
            box.addView(col, new android.widget.LinearLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT, (int) (56 * md)));
        }

        box.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        View anchor = home(R.id.cardSuManager);
        int cardW = anchor == null ? 0 : anchor.getWidth();
        int pw_w = cardW > 0 ? cardW : Math.max(box.getMeasuredWidth(), (int) (240 * md));

        final android.widget.PopupWindow pw = new android.widget.PopupWindow(box,
                pw_w, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, true);
        pw.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(
                android.graphics.Color.TRANSPARENT));
        pw.setOutsideTouchable(true);
        pwRef[0] = pw;
        pw.setOnDismissListener(() ->
                binding.dimOverlay.animate().alpha(0f).setDuration(150)
                        .withEndAction(() -> binding.dimOverlay
                                .setVisibility(View.GONE)).start());

        box.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override
            public void getOutline(View view, android.graphics.Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), 26 * md);
            }
        });
        box.setClipToOutline(true);
        box.setElevation(48 * md);

        int[] loc = new int[2];
        if (anchor != null) anchor.getLocationOnScreen(loc);
        // Centered, NOT card-anchored: the popup is exactly card-wide and the
        // card sits 16dp from each edge, so half the leftover IS the card's own
        // margin. Anchoring to the card's left + 16dp pushed the popup 16dp
        // right, which read as off-centre and clipped its right edge.
        int x = (getResources().getDisplayMetrics().widthPixels - pw_w) / 2;
        int y = loc[1] + (anchor == null ? 0 : anchor.getHeight()) + (int) (4 * md);
        binding.dimOverlay.setVisibility(View.VISIBLE);
        binding.dimOverlay.setAlpha(0f);
        binding.dimOverlay.animate().alpha(0.5f).setDuration(150).start();
        box.setPivotX(0f);
        box.setPivotY(0f);
        box.setScaleX(0.9f);
        box.setScaleY(0.9f);
        box.setAlpha(0f);
        pw.showAtLocation(anchor != null ? anchor : binding.getRoot(),
                android.view.Gravity.TOP | android.view.Gravity.START, x, y);
        box.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(150)
                .setInterpolator(new android.view.animation.DecelerateInterpolator())
                .start();
    }

    /** Writes the DE pref that bootstrap.c reads as su_manager, then unlocks Run. */
    private void selectSuManager(SuManagerEntry e) {
        mDeCtx.getSharedPreferences("dfroot", MODE_PRIVATE)
                .edit().putString("su_manager", e.packageName).apply();
        suManagerPkg = e.packageName;
        suManagerLabel = e.label;
        TextView sub = home(R.id.suManagerSubtitle);
        if (sub != null) {
            sub.setText(e.label + "  (" + e.packageName + ")");
            sub.setTextColor(getColor(R.color.text_secondary));
        }
        updateRunButton();
    }

    /** Run needs a manager selected, no failed-run lock and no existing root. */
    private boolean canRun() {
        return suManagerPkg != null && !failedRun && !new File("/dev/df").exists();
    }

    /** Single place that decides the Run pill's look and enabled state. */
    private void updateRunButton() {
        if (running) return;
        if (failedRun) {
            setFailedState();
            return;
        }
        if (new File("/dev/df").exists()) {
            setRootedState(true);
            return;
        }
        boolean ok = suManagerPkg != null;
        View run = home(R.id.btnRun);
        if (run != null) {
            if (run instanceof TextView) ((TextView) run).setText(R.string.run_exploit);
            run.setEnabled(ok);
            run.setAlpha(ok ? 1f : 0.4f);
        }

        // 状态卡：激活青色实底 + 勾，未激活灰色实底 + 叉
        setStatusCard(ok ? R.color.accent_cyan : R.color.status_inactive,
                ok ? R.drawable.ic_check : R.drawable.ic_error,
                ok ? R.string.status_ready : R.string.status_needs_manager);
    }

    /** 状态卡配色/图标/文案，与 JSXposed 的 ActivationCard 规则一致：
     *  激活为主色实底白字，未激活/运行中为灰色实底白字，失败为红色实底白字。 */
    private void setStatusCard(int colorRes, int iconRes, int labelRes) {
        android.widget.ImageView icon = home(R.id.statusIcon);
        TextView label = home(R.id.statusLabel);
        com.google.android.material.card.MaterialCardView card = home(R.id.statusCard);
        if (card != null) card.setCardBackgroundColor(getColor(colorRes));
        if (icon != null) icon.setImageResource(iconRes);
        if (label != null) label.setText(labelRes);
    }

    /** 第一次点击只做确认提示，不改变状态卡底色。 */
    private void showRunningBadge() {
        android.widget.ImageView icon = home(R.id.statusIcon);
        TextView label = home(R.id.statusLabel);
        if (icon != null) icon.setImageResource(R.drawable.ic_play);
        if (label != null) label.setText(R.string.confirm_run);
    }

    /** Failure presentation: left bar label "Failure", right label "Reboot",
     *  bars + labels red, run pill greyed out like the Running/Rooted state.
     *  The pill stays disabled because the vendor patch is page-cache only:
     *  retrying without rebooting would fail the same way. */
    private void setFailedState() {
        failedRun = true;
        runArmed = false;
        StageStepperView stepper = home(R.id.twoStep);
        if (stepper != null) {
            int reached = Math.min(STAGE_VERIFIED, Math.max(0, stepper.currentStage()));
            stepper.setFailed(true);
            stepper.stopAt(reached);
        }

        setStatusCard(R.color.error, R.drawable.ic_error, R.string.failure);

        View run = home(R.id.btnRun);
        if (run != null) {
            run.setEnabled(false);
            run.setAlpha(0.4f);
        }
    }

    private void setRootedState(boolean rooted) {
        runArmed = false;
        // Not rooted does not mean runnable: the manager still has to be picked
        // and a failed run locks the pill until reboot.
        boolean ok = !rooted && canRun();
        if (failedRun) {
            setFailedState();
            return;
        }
        View run = home(R.id.btnRun);
        if (run != null) {
            if (run instanceof TextView) {
                ((TextView) run).setText(rooted ? R.string.rooted : R.string.run_exploit);
            }
            run.setEnabled(ok);
            run.setAlpha(ok ? 1f : 0.4f);
        }

        setStatusCard(rooted || ok ? R.color.accent_cyan : R.color.status_inactive,
                rooted || ok ? R.drawable.ic_check : R.drawable.ic_error,
                rooted ? R.string.rooted
                        : ok ? R.string.status_ready : R.string.status_needs_manager);
    }

    /** Opens the SU manager that is actually selected - pre-3.2 this walked a
     *  hardcoded package list that no longer matches reality. */
    private void openKsu() {
        if (suManagerPkg == null) {
            // Nothing to say: the launcher circle is only lit once a manager is
            // picked, so getting here is a no-op rather than an error.
            return;
        }
        Intent launch = getPackageManager().getLaunchIntentForPackage(suManagerPkg);
        if (launch == null) {
            Log.i(TAG, "no launch intent for " + suManagerPkg);
            return;
        }
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(launch);
    }

    /** Saves the current log to Downloads and opens the Downloads screen. */
    private void shareLog() {
        String log = logBuffer.length() > 0 ? logBuffer.toString() : readLastLog();
        if (log.trim().isEmpty()) {
            return;
        }
        try {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Downloads.DISPLAY_NAME, "dirtyfrag_log.txt");
            values.put(MediaStore.Downloads.MIME_TYPE, "text/plain");
            Uri uri = getContentResolver().insert(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (uri != null) {
                try (java.io.OutputStream out = getContentResolver().openOutputStream(uri)) {
                    out.write(log.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "save log failed", e);
        }
        try {
            startActivity(new Intent(android.app.DownloadManager.ACTION_VIEW_DOWNLOADS));
        } catch (Exception e) {
            Log.e(TAG, "open downloads failed", e);
        }
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
                        : rc == 2 ? "check logcat & dmesg"
                        : "failed to patch files";
                report("\n=== exploit failed: " + why + " ===\n");
            }
            mDeCtx.getSharedPreferences("dfroot", MODE_PRIVATE)
                    .edit().putBoolean("last_run_success", rc == 0).apply();
        } catch (Exception e) {
            Log.e(TAG, "exploit exception", e);
            report("\nexception: " + e + "\n");
        } finally {
            mMain.post(() -> {
                running = false;
                setAppearanceSelectorsEnabled(true);
                boolean rooted = new File("/dev/df").exists();
                if (rooted) {
                    setRootedState(true);
                    setSeg2(1f, R.string.verified);
                } else {
                    setFailedState();
                }
                updateLogVisibility();
            });
        }
    }

    private void openUrl(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception e) {
            Log.i(TAG, "no browser for " + url);
        }
    }

    private void applyThemeAndLanguage() {
        SharedPreferences prefs = createDeviceProtectedStorageContext()
                .getSharedPreferences("dfroot", MODE_PRIVATE);
        androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(
                prefs.getBoolean("dark_mode", false)
                        ? androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES
                        : androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO);
    }

    private void setupAppearanceSelectors() {
        SharedPreferences prefs = mDeCtx.getSharedPreferences("dfroot", MODE_PRIVATE);
        com.google.android.material.button.MaterialButtonToggleGroup theme =
                settings(R.id.themeSelector);
        com.google.android.material.button.MaterialButtonToggleGroup language =
                settings(R.id.languageSelector);
        if (theme == null || language == null) return;
        theme.check(prefs.getBoolean("dark_mode", false)
                ? R.id.themeDark : R.id.themeLight);
        language.check("en".equals(prefs.getString("language", "zh"))
                ? R.id.languageEn : R.id.languageZh);
        theme.addOnButtonCheckedListener((group, id, checked) -> {
            if (!checked || running) return;
            boolean dark = id == R.id.themeDark;
            if (prefs.getBoolean("dark_mode", false) == dark) return;
            prefs.edit().putBoolean("dark_mode", dark).apply();
            applyThemeAndLanguage();
        });
        language.addOnButtonCheckedListener((group, id, checked) -> {
            if (!checked || running) return;
            String lang = id == R.id.languageEn ? "en" : "zh";
            if (lang.equals(prefs.getString("language", "zh"))) return;
            prefs.edit().putString("language", lang).apply();
            recreate();
        });
    }

    private void setAppearanceSelectorsEnabled(boolean enabled) {
        int[] ids = {R.id.themeSelector, R.id.themeLight, R.id.themeDark,
                R.id.languageSelector, R.id.languageZh, R.id.languageEn};
        for (int id : ids) {
            View v = settings(id);
            if (v != null) v.setEnabled(enabled);
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        outState.putInt("selected_nav", selectedNav);
        outState.putBoolean("failed_run", failedRun);
        super.onSaveInstanceState(outState);
    }
}
