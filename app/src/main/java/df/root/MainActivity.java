package df.root;

import android.content.Context;
import android.content.ComponentName;
import android.content.ContentValues;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.widget.TextView;
import androidx.core.graphics.ColorUtils;
import androidx.appcompat.app.AppCompatActivity;

import df.root.BuildConfig;
import df.root.databinding.ActivityMainBinding;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "dfroot";

    private ActivityMainBinding binding;
    /** Each page lives in its own layout, so its controls are reached through
     *  the fragment view that currently holds them. Null until that page has
     *  been created by the pager. */
    private View homeView;
    private View settingsView;
    private final Handler mMain = new Handler(Looper.getMainLooper());
    private final Executor mExec = Executors.newSingleThreadExecutor();
    private File lastLogFile;
    private boolean running;
    private boolean expertMode;
    private int selectedNav = R.id.nav_home;
    private boolean moduleRefresh;
    private boolean mBootReceiverEnabled;
    private boolean mLastFailed;

    /** Device-protected storage context: bootstrap.c reads its prefs from
     *  /data/user_de/0/df.root/, so every pref the exploit depends on must live
     *  there, not in credential-protected storage. */
    private Context mDeCtx;
    /** Package name of the SU manager whose libksud.so gets staged, or null. */
    private String suManagerPkg;
    private CharSequence suManagerLabel;
    private android.graphics.drawable.Drawable suManagerIcon;
    /** A failed run locks the Run button until reboot (page-cache patch). */
    private boolean failedRun;
    private UpdateChecker updateChecker;

    /** Called by HomeFragment once its layout exists. */
    @SuppressWarnings("unchecked")
    <T extends View> T home(int id) {
        View v = homeView == null ? null : homeView.findViewById(id);
        return v == null ? null : (T) v;
    }

    /** Called by SettingsFragment once its layout exists. */
    @SuppressWarnings("unchecked")
    <T extends View> T settings(int id) {
        return settingsView == null ? null : (T) settingsView.findViewById(id);
    }

    /** HomeFragment's layout is live: bind what belongs to the home page. */
    void onHomeViewReady(View v) {
        homeView = v;
        // onCreate ran loadSuManagerPref() before this view existed, so its
        // label/icon writes went nowhere. Re-run it now that the card is there.
        loadSuManagerPref();
        initHomeControls();
        applyRestoredHomeState();
    }

    /** Pager positions: 0 = Home, 1 = Settings. */
    private static int pageForNav(int navId) {
        return navId == R.id.nav_settings ? 1 : 0;
    }

    private static int navForPage(int position) {
        return position == 1 ? R.id.nav_settings : R.id.nav_home;
    }

    /** SettingsFragment's layout is live: bind what belongs to settings. */
    void onSettingsViewReady(View v) {
        settingsView = v;
        initSettingsControls();
    }

    private int bootCount() {
        try {
            return android.provider.Settings.Global.getInt(getContentResolver(),
                    android.provider.Settings.Global.BOOT_COUNT, -1);
        } catch (Exception e) {
            return -1;
        }
    }

    /**
     * Screen zoom and Font size are locked for the duration of the activity:
     * the pill, toolbar and card geometry were all measured at one screen
     * width in dp and stop agreeing with each other as soon as it moves. See
     * {@link ScaleFix} for why the anchor is a width and not a dpi value.
     */
    static java.util.Locale localeFor(String lang) {
        if ("en".equals(lang)) return java.util.Locale.ENGLISH;
        if ("zh-TW".equals(lang) || "zh_TW".equals(lang) || "zh-Hant".equals(lang))
            return java.util.Locale.TRADITIONAL_CHINESE;
        return java.util.Locale.SIMPLIFIED_CHINESE;
    }

    @Override
    protected void attachBaseContext(Context newBase) {
        SharedPreferences prefs = newBase.createDeviceProtectedStorageContext()
                .getSharedPreferences("dfroot", MODE_PRIVATE);
        java.util.Locale locale = localeFor(prefs.getString("language", "zh-TW"));
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
        applyAccentTone();
        applyUiFont();
        mDeCtx = createDeviceProtectedStorageContext();
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        // Setup ViewPager2 with fragments: Home / Settings.
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

        // Startup update check. Gitee and GitHub are queried side by side and
        // the faster answer wins, so mainland users hit Gitee and everyone else
        // hits GitHub without an unreliable IP/region lookup. A hit downloads
        // the APK in-app and hands it to the system installer.
        updateChecker = new UpdateChecker(this);
        // Theme, accent and language switches call recreate(), which re-runs
        // onCreate. Those are not launches, so only the first create of this
        // activity (savedInstanceState == null) starts the network checks.
        if (savedInstanceState == null) {
            updateChecker.checkOnStart();
            // The announcement is fetched from every mirror on each launch and
            // has to be acknowledged with its button before it goes away.
            updateChecker.checkAnnouncement();
        }

        // SU Manager card: upstream 3.2 deleted the bundled assets/ksud, so the
        // exploit stages libksud.so out of the chosen manager's
        // nativeLibraryDir and refuses to run without one. Only apps that
        // actually ship that library are offered.
        loadSuManagerPref();

        if (isRooted()) {
            setRootedState();
        }

        // A log saved before the current boot is stale: delete it so a reboot
        // always starts with a clean run screen; RunActivity replays whatever
        // is left.
        lastLogFile = new File(mDeCtx.getFilesDir(), "last_run.log");
        int savedBoot = mDeCtx.getSharedPreferences("dfroot", MODE_PRIVATE)
                .getInt("log_boot_count", -1);
        if (lastLogFile.exists() && savedBoot != bootCount()) {
            lastLogFile.delete();
            mDeCtx.getSharedPreferences("dfroot", MODE_PRIVATE)
                    .edit().putBoolean("last_run_success", false).apply();
        }

        // A failed run since the last boot paints the home status card red.
        mLastFailed = lastLogFile.exists() && !mDeCtx
                .getSharedPreferences("dfroot", MODE_PRIVATE)
                .getBoolean("last_run_success", true);

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
                selectedNav = navForPage(position);
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
        binding.viewPager.setCurrentItem(pageForNav(selectedNav), false);
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
            // The button opens the dedicated run screen and starts immediately;
            // once rooted it only opens the saved log.
            run.setOnClickListener(v -> openRun(true));
            // Subtle push-in
            run.setOnTouchListener((v, ev) -> {
                switch (ev.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                        v.animate().scaleX(0.94f).scaleY(0.94f).setDuration(80).start();
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

        View ksu = home(R.id.rowSuManager);
        if (ksu != null) ksu.setOnLongClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            openKsu();
            return true;
        });

        fillDeviceInfo();
        View copyInfo = home(R.id.btnCopyDeviceInfo);
        if (copyInfo != null) {
            copyInfo.setOnClickListener(v -> {
                v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                copyDeviceInfo();
            });
        }

        View softReboot = home(R.id.btnSoftReboot);
        if (softReboot != null) {
            softReboot.setOnClickListener(v -> {
                v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                showSoftRebootMenu(v);
            });
        }
        updateRunButton();
    }

    /** Static device facts shown at the bottom of the home page. */
    private void fillDeviceInfo() {
        TextView model = home(R.id.deviceModel);
        if (model != null) {
            String maker = Build.MANUFACTURER == null ? "" : Build.MANUFACTURER.trim();
            if (!maker.isEmpty()) {
                maker = Character.toUpperCase(maker.charAt(0)) + maker.substring(1);
            }
            model.setText((maker + " " + Build.MODEL).trim());
        }
        TextView system = home(R.id.deviceSystem);
        if (system != null) {
            system.setText(Build.VERSION.RELEASE + " · API " + Build.VERSION.SDK_INT);
        }
        TextView kernel = home(R.id.deviceKernel);
        if (kernel != null) {
            kernel.setText(System.getProperty("os.version", "unknown"));
        }
        TextView arch = home(R.id.deviceArch);
        if (arch != null) {
            String[] abis = Build.SUPPORTED_ABIS;
            arch.setText(abis != null && abis.length > 0 ? abis[0] : "unknown");
        }
        TextView fingerprint = home(R.id.deviceFingerprint);
        if (fingerprint != null) {
            fingerprint.setText(Build.FINGERPRINT == null ? "unknown" : Build.FINGERPRINT);
        }
    }

    /** Copies the whole info block as plain "label: value" lines. */
    private void copyDeviceInfo() {
        StringBuilder sb = new StringBuilder();
        appendInfoLine(sb, R.string.info_device, R.id.deviceModel);
        appendInfoLine(sb, R.string.info_system, R.id.deviceSystem);
        appendInfoLine(sb, R.string.info_kernel, R.id.deviceKernel);
        appendInfoLine(sb, R.string.info_arch, R.id.deviceArch);
        appendInfoLine(sb, R.string.info_fingerprint, R.id.deviceFingerprint);
        android.content.ClipboardManager cm =
                (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (cm == null) return;
        cm.setPrimaryClip(android.content.ClipData.newPlainText(
                getString(R.string.device_info), sb.toString()));
        android.widget.Toast.makeText(this, R.string.device_info_copied,
                android.widget.Toast.LENGTH_SHORT).show();
    }

    private void appendInfoLine(StringBuilder sb, int label, int valueId) {
        TextView value = home(valueId);
        if (value == null) return;
        if (sb.length() > 0) sb.append('\n');
        sb.append(getString(label)).append(": ").append(value.getText());
    }

    /** Applies the current state to the status card. The log itself is
     *  replayed when the run page is created. */
    private void applyRestoredHomeState() {
        if (mLastFailed && !isRooted()) {
            setFailedState();
        } else {
            updateRunButton();
        }
    }

    /** Settings page bindings. Runs once SettingsFragment's view exists. */
    private void initSettingsControls() {
        // KSU modules toggle: since 3.2 this is a plain preference, not a root
        // operation. bootstrap.c reads disable_modules and touches a `disable`
        // flag file in every installed module before ksud runs, so the toggle
        // works before the device is rooted and applies on the next root.
        com.google.android.material.materialswitch.MaterialSwitch modules =
                settings(R.id.switchModules);
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

        com.google.android.material.materialswitch.MaterialSwitch expert =
                settings(R.id.switchExpertMode);
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
        com.google.android.material.materialswitch.MaterialSwitch boot =
                settings(R.id.switchBootStart);
        if (boot != null) {
            boot.setChecked(mBootReceiverEnabled);
            boot.setOnCheckedChangeListener((btn, checked) -> {
                getPackageManager().setComponentEnabledSetting(bootReceiver,
                        checked ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                                : PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                        PackageManager.DONT_KILL_APP);
            });
        }

        boolean autoSoftReboot = createDeviceProtectedStorageContext()
                .getSharedPreferences("dfroot", MODE_PRIVATE)
                .getBoolean("auto_soft_reboot", false);
        com.google.android.material.materialswitch.MaterialSwitch soft =
                settings(R.id.switchAutoSoftReboot);
        if (soft != null) {
            soft.setChecked(autoSoftReboot);
            soft.setOnCheckedChangeListener((btn, checked) ->
                    createDeviceProtectedStorageContext()
                            .getSharedPreferences("dfroot", MODE_PRIVATE)
                            .edit().putBoolean("auto_soft_reboot", checked).apply());
        }

        applyExpertMode();
        setupAppearanceSelectors();

        View[] links = {
                settings(R.id.rowOriginal), settings(R.id.repoOriginalLabel),
                settings(R.id.repoOriginalUrl)};
        for (View link : links) {
            if (link != null) link.setOnClickListener(v ->
                    openUrl(getString(R.string.repo_original_url)));
        }
        View[] fork = {
                settings(R.id.rowFork), settings(R.id.repoForkLabel),
                settings(R.id.repoForkUrl)};
        for (View link : fork) {
            if (link != null) link.setOnClickListener(v ->
                    openUrl(getString(R.string.repo_fork_url)));
        }
        View[] community = {
                settings(R.id.rowCommunity), settings(R.id.communityLabel),
                settings(R.id.communityDesc)};
        for (View link : community) {
            if (link != null) link.setOnClickListener(v ->
                    openUrl(getString(R.string.community_url)));
        }

        TextView updateVersion = settings(R.id.updateCheckVersion);
        if (updateVersion != null) {
            updateVersion.setText(getString(R.string.update_current_version,
                    BuildConfig.VERSION_NAME));
        }
        View updateCheck = settings(R.id.rowUpdateCheck);
        if (updateCheck != null) {
            updateCheck.setOnClickListener(v -> {
                if (updateChecker != null) updateChecker.checkManually();
            });
        }
    }

    /** Opens the dedicated run screen. autoStart is ignored once root is
     *  already present: the screen then only shows the saved log. */
    private void openRun(boolean autoStart) {
        Intent intent = new Intent(this, RunActivity.class);
        intent.putExtra(RunActivity.EXTRA_AUTO_RUN, autoStart && canRun());
        startActivity(intent);
    }

    /** Expert mode off: the autorun / auto-reboot cards are simply not shown.
     *  The two switches are independent: autorun only decides whether a run
     *  starts at boot, auto soft reboot only decides what happens after a
     *  successful run (manual or boot). */
    private void applyExpertMode() {
        int vis = expertMode ? View.VISIBLE : View.GONE;
        View autorun = settings(R.id.cardAutorun);
        View reboot = settings(R.id.cardAutoReboot);
        if (autorun != null) autorun.setVisibility(vis);
        if (reboot != null) reboot.setVisibility(vis);
    }

    /** Re-read the SU manager selection when returning to the app: the user may
     *  have installed or uninstalled a manager while we were in the background,
     *  and the Run button has to reflect that immediately. The run screen is a
     *  separate activity, so its result is picked up from the saved prefs. */
    @Override
    protected void onResume() {
        super.onResume();
        loadSuManagerPref();
        mLastFailed = lastLogFile != null && lastLogFile.exists() && !mDeCtx
                .getSharedPreferences("dfroot", MODE_PRIVATE)
                .getBoolean("last_run_success", true);
        if (mLastFailed && !isRooted()) {
            setFailedState();
        } else {
            updateRunButton();
        }
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
        final android.graphics.drawable.Drawable icon;

        SuManagerEntry(String pkg, CharSequence label, android.graphics.drawable.Drawable icon) {
            this.packageName = pkg;
            this.label = label;
            this.icon = icon;
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
            if (!ExploitRunner.hasKsud(ai)) continue;
            out.add(new SuManagerEntry(ai.packageName, pm.getApplicationLabel(ai),
                    pm.getApplicationIcon(ai)));
        }
        out.sort((a, b) -> a.label.toString().compareToIgnoreCase(b.label.toString()));
        return out;
    }

    /** Well-known SU manager packages. Only used to preselect a default, never
     *  to filter the picker list. */
    private static final String[] KNOWN_MANAGERS = {
            "com.sukisu.ultra",
            "me.weishu.kernelsu",
            "com.rifsxd.ksunext",
            "com.rifsxd.kernelsu",
            "me.bmax.apatch",
            "me.garfieldhan.apatch.next",
            "io.github.huskydg.magisk",
            "com.topjohnwu.magisk",
            "io.github.vvb2060.magisk",
            "com.dergoogler.mmrl",
    };

    /** Picks a default when nothing was saved yet. The ksud scan is the first
     *  source, but it is not authoritative - some managers ship the library
     *  under a different name/dir, so the known package list is tried next.
     *  A single candidate from either source is the default. */
    private SuManagerEntry findDefaultSuManager() {
        List<SuManagerEntry> found = scanSuManagers();
        if (found.isEmpty()) {
            PackageManager pm = getPackageManager();
            for (String pkg : KNOWN_MANAGERS) {
                try {
                    ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
                    found.add(new SuManagerEntry(pkg, pm.getApplicationLabel(ai),
                            pm.getApplicationIcon(ai)));
                } catch (PackageManager.NameNotFoundException ignored) {
                    // not installed
                }
            }
        }
        if (found.isEmpty()) return null;
        // Several managers installed: rank them. One manager: take it.
        found.sort((a, b) -> {
            int ra = managerRank(a.packageName);
            int rb = managerRank(b.packageName);
            if (ra != rb) return ra - rb;
            return a.label.toString().compareToIgnoreCase(b.label.toString());
        });
        return found.get(0);
    }

    /** Index in KNOWN_MANAGERS, or a large value for anything unknown. */
    private static int managerRank(String pkg) {
        for (int i = 0; i < KNOWN_MANAGERS.length; i++) {
            if (KNOWN_MANAGERS[i].equals(pkg)) return i;
        }
        return KNOWN_MANAGERS.length;
    }

    /** Restores the saved selection, or picks a default when none was saved.
     *  Safe to call before the home view exists: the fields are the source of
     *  truth and the card is rendered from them by onHomeViewReady(). */
    private void loadSuManagerPref() {
        SharedPreferences sp = mDeCtx.getSharedPreferences("dfroot", MODE_PRIVATE);
        String saved = sp.getString("su_manager", null);
        suManagerPkg = null;
        suManagerLabel = null;
        suManagerIcon = null;
        if (saved != null) {
            PackageManager pm = getPackageManager();
            try {
                ApplicationInfo ai = pm.getApplicationInfo(saved, 0);
                suManagerPkg = saved;
                suManagerLabel = pm.getApplicationLabel(ai);
                suManagerIcon = pm.getApplicationIcon(ai);
            } catch (PackageManager.NameNotFoundException e) {
                sp.edit().remove("su_manager").apply();
            }
        }
        // Nothing picked yet: default to the best-ranked installed manager and
        // persist it, so the very first launch is already selected.
        if (suManagerPkg == null) {
            SuManagerEntry pick = findDefaultSuManager();
            if (pick != null) {
                sp.edit().putString("su_manager", pick.packageName).apply();
                suManagerPkg = pick.packageName;
                suManagerLabel = pick.label;
                suManagerIcon = pick.icon;
            }
        }
        renderSuManagerCard();
    }

    /** Writes the selection into the home card (no-op until that view exists). */
    private void renderSuManagerCard() {
        TextView sub = home(R.id.suManagerSubtitle);
        if (sub != null) {
            sub.setText(suManagerPkg == null
                    ? getString(R.string.su_required)
                    : suManagerLabel + "  (" + suManagerPkg + ")");
            sub.setTextColor(getColor(suManagerPkg == null
                    ? R.color.error : R.color.text_secondary));
        }
        renderSuManagerIcon();
    }

    /** Puts the chosen manager's launcher icon on the home card. */
    private void renderSuManagerIcon() {
        android.widget.ImageView icon = home(R.id.imgSuManager);
        if (icon == null) return;
        icon.setImageDrawable(suManagerIcon);
        icon.setVisibility(suManagerIcon == null ? View.GONE : View.VISIBLE);
    }

    /** Root-manager picker: stock single-choice dialog with each app's own icon. */
    private void showSuManagerPopup() {
        List<SuManagerEntry> apps = scanSuManagers();
        if (apps.isEmpty()) {
            new androidx.appcompat.app.AlertDialog.Builder(this)
                    .setMessage(R.string.su_none)
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
            return;
        }

        float md = getResources().getDisplayMetrics().density;
        int pad = (int) (20 * md);
        int iconSize = (int) (32 * md);
        int gap = (int) (16 * md);

        android.widget.LinearLayout list = new android.widget.LinearLayout(this);
        list.setOrientation(android.widget.LinearLayout.VERTICAL);
        list.setPadding(0, (int) (8 * md), 0, (int) (8 * md));

        final androidx.appcompat.app.AlertDialog[] ref = {null};
        int checkedIndex = -1;
        for (int i = 0; i < apps.size(); i++) {
            if (apps.get(i).packageName.equals(suManagerPkg)) checkedIndex = i;
        }

        for (int i = 0; i < apps.size(); i++) {
            SuManagerEntry e = apps.get(i);
            android.widget.LinearLayout row = new android.widget.LinearLayout(this);
            row.setOrientation(android.widget.LinearLayout.HORIZONTAL);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            row.setBackgroundResource(R.drawable.menu_row_highlight);
            row.setPadding(pad, 0, pad, 0);
            row.setMinimumHeight((int) (56 * md));

            android.widget.ImageView ic = new android.widget.ImageView(this);
            ic.setImageDrawable(e.icon);
            ic.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
            row.addView(ic, new android.widget.LinearLayout.LayoutParams(iconSize, iconSize));

            android.widget.LinearLayout text = new android.widget.LinearLayout(this);
            text.setOrientation(android.widget.LinearLayout.VERTICAL);
            android.widget.LinearLayout.LayoutParams textLp =
                    new android.widget.LinearLayout.LayoutParams(0,
                            android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            textLp.setMargins(gap, 0, 0, 0);
            row.addView(text, textLp);

            TextView name = new TextView(this);
            name.setText(e.label);
            name.setTextColor(getColor(R.color.text_primary));
            name.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15);
            text.addView(name);

            TextView pkg = new TextView(this);
            pkg.setText(e.packageName);
            pkg.setTextColor(getColor(R.color.text_secondary));
            pkg.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12);
            text.addView(pkg);

            android.widget.RadioButton mark = new android.widget.RadioButton(this);
            mark.setChecked(i == checkedIndex);
            mark.setClickable(false);
            mark.setFocusable(false);
            mark.setButtonTintList(android.content.res.ColorStateList.valueOf(
                    resolveThemeColor(R.attr.accentInk)));
            row.addView(mark);

            final int index = i;
            row.setOnClickListener(v -> {
                v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                selectSuManager(apps.get(index));
                if (ref[0] != null) ref[0].dismiss();
            });
            list.addView(row, new android.widget.LinearLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.addView(list);

        ref[0] = new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(R.string.su_manager)
                .setView(scroll)
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** Writes the DE pref that bootstrap.c reads as su_manager, then unlocks Run. */
    private void selectSuManager(SuManagerEntry e) {
        mDeCtx.getSharedPreferences("dfroot", MODE_PRIVATE)
                .edit().putString("su_manager", e.packageName).apply();
        suManagerPkg = e.packageName;
        suManagerLabel = e.label;
        suManagerIcon = e.icon;
        renderSuManagerCard();
        updateRunButton();
    }

    /** /dev/df is created by the shellcode with mode 0000, so the app uid can
     *  never read or write it - permission probes always failed. /dev/dfm6 is
     *  touched by bootstrap.c only after `ksud late-load` succeeded, which is
     *  the actual root marker; /dev/df alone can be left over from a failed
     *  run. Both live in /dev, which is recreated on a real reboot. */
    private static boolean isRooted() {
        return new File("/dev/df").exists() && new File("/dev/dfm6").exists();
    }

    /** Dumps every input that feeds the "already rooted" decision, so a wrong
     *  status card can be traced to the exact condition. Logged only when the
     *  snapshot changes, to keep logcat readable. */
    private String rootEvidenceSeen;

    private void logRootEvidence(String from) {
        File dev = new File("/dev/df");
        boolean devExists = dev.exists();
        boolean daemonMarker = new File("/dev/dfm6").exists();
        String uid = "n/a";
        try {
            java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.FileReader("/proc/self/status"));
            for (String l; (l = r.readLine()) != null; ) {
                if (l.startsWith("Uid:")) {
                    uid = l.substring(4).trim();
                    break;
                }
            }
            r.close();
        } catch (IOException ignored) {
        }
        String evidence = "from=" + from
                + " /dev/df.exists=" + devExists
                + " /dev/dfm6.exists=" + daemonMarker
                + " readable=" + dev.canRead()
                + " writable=" + dev.canWrite()
                + " uid=" + uid
                + " suManagerPkg=" + suManagerPkg
                + " failedRun=" + failedRun
                + " mLastFailed=" + mLastFailed
                + " canRun=" + canRun();
        if (evidence.equals(rootEvidenceSeen)) return;
        rootEvidenceSeen = evidence;
        Log.i(TAG, "root-evidence " + evidence);
    }

    /** Run needs a manager selected and no existing root. A failed run is not a
     *  lock: the user can retry right away. */
    private boolean canRun() {
        return suManagerPkg != null && !isRooted();
    }

    /** Single place that decides the Run pill's look and enabled state. */
    private void updateRunButton() {
        logRootEvidence("updateRunButton");
        updateSoftRebootButton();
        if (running) return;
        if (isRooted()) {
            setRootedState(true);
            return;
        }
        boolean ok = suManagerPkg != null;
        View run = home(R.id.btnRun);
        if (run != null) {
            run.setEnabled(ok);
            run.setAlpha(ok ? 1f : 0.4f);
        }

        // 状态卡：激活为绿色，未激活沿用主题色
        setStatusCard(ok ? R.drawable.ic_play : R.drawable.ic_error,
                ok ? R.string.status_ready : R.string.status_needs_manager,
                ok ? getColor(R.color.status_active_container)
                        : resolveThemeColor(R.attr.accentContainer),
                ok ? getColor(R.color.status_active_ink)
                        : resolveThemeColor(R.attr.accentInk));
    }

    /** Soft reboot is only offered while root is up and nothing else runs. */
    private void updateSoftRebootButton() {
        View btn = home(R.id.btnSoftReboot);
        if (btn == null) return;
        btn.setVisibility(isRooted() && !running ? View.VISIBLE : View.GONE);
    }

    /** First tap opens the menu; the menu item is the second tap and reboots. */
    private void showSoftRebootMenu(View anchor) {
        if (running || !isRooted()) return;
        androidx.appcompat.widget.PopupMenu menu =
                new androidx.appcompat.widget.PopupMenu(this, anchor);
        menu.getMenu().add(0, 1, 0, R.string.soft_reboot);
        menu.setOnMenuItemClickListener(item -> {
            softReboot();
            return true;
        });
        menu.show();
    }

    /** `ksud soft-reboot` needs the ksu domain, so it is handed to the SU
     *  manager the user picked instead of being exec'd by the app uid. */
    private void softReboot() {
        mExec.execute(() -> {
            String failure = null;
            try {
                Process p = new ProcessBuilder("su", "-c", "/data/adb/ksud soft-reboot")
                        .redirectErrorStream(true).start();
                java.io.BufferedReader r = new java.io.BufferedReader(
                        new java.io.InputStreamReader(p.getInputStream()));
                StringBuilder out = new StringBuilder();
                for (String l; (l = r.readLine()) != null; ) out.append(l).append('\n');
                r.close();
                if (p.waitFor() != 0) {
                    failure = out.toString().trim();
                    if (failure.isEmpty()) failure = "exit " + p.exitValue();
                }
            } catch (Exception e) {
                failure = e.getMessage() != null ? e.getMessage() : e.toString();
            }
            final String error = failure;
            mMain.post(() -> {
                if (error == null) {
                    android.widget.Toast.makeText(this, R.string.soft_reboot_requested,
                            android.widget.Toast.LENGTH_SHORT).show();
                } else {
                    android.widget.Toast.makeText(this,
                            getString(R.string.soft_reboot_failed, error),
                            android.widget.Toast.LENGTH_LONG).show();
                }
            });
        });
    }

    /** 状态卡的图标/文案/配色随状态变化。 */
    private void setStatusCard(int iconRes, int labelRes, int bgColor, int inkColor) {
        com.google.android.material.card.MaterialCardView card = home(R.id.statusCard);
        android.widget.ImageView icon = home(R.id.statusIcon);
        TextView caption = home(R.id.statusCaption);
        TextView label = home(R.id.statusLabel);
        if (card != null) card.setCardBackgroundColor(bgColor);
        if (icon != null) {
            icon.setImageResource(iconRes);
            icon.setImageTintList(android.content.res.ColorStateList.valueOf(inkColor));
        }
        if (caption != null) caption.setTextColor(inkColor);
        if (label != null) {
            label.setText(labelRes);
            label.setTextColor(inkColor);
        }
    }

    /** Failure presentation: the status card turns red. The run button
     *  stays enabled - retrying is allowed. */
    private void setFailedState() {
        failedRun = true;
        setStatusCard(R.drawable.ic_error, R.string.failure,
                getColor(R.color.status_error_container),
                getColor(R.color.status_error_ink));

        View run = home(R.id.btnRun);
        if (run != null) {
            boolean ok = canRun();
            run.setEnabled(ok);
            run.setAlpha(ok ? 1f : 0.4f);
        }
    }

    private void setRootedState(boolean rooted) {
        logRootEvidence("setRootedState(" + rooted + ")");
        updateSoftRebootButton();
        // Not rooted does not mean runnable: the manager still has to be picked.
        boolean ok = !rooted && canRun();
        boolean active = rooted || ok;
        // A previous failed run must not mask a successful one.
        if (failedRun && !rooted) {
            setFailedState();
            return;
        }
        View run = home(R.id.btnRun);
        if (run != null) {
            // Rooted: the button stays clickable but only opens the saved log.
            boolean enabled = rooted || ok;
            run.setEnabled(enabled);
            run.setAlpha(enabled ? 1f : 0.4f);
        }

        setStatusCard(rooted ? R.drawable.ic_check
                        : ok ? R.drawable.ic_play : R.drawable.ic_error,
                rooted ? R.string.rooted
                        : ok ? R.string.status_ready : R.string.status_needs_manager,
                active ? getColor(R.color.status_active_container)
                        : resolveThemeColor(R.attr.accentContainer),
                active ? getColor(R.color.status_active_ink)
                        : resolveThemeColor(R.attr.accentInk));
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

    /** Overlays the chosen accent tone on the base theme. Must run after
     *  super.onCreate and before setContentView so every ?attr/accent*
     *  reference in the inflated layouts and drawables resolves to it. */
    private void applyAccentTone() {
        String tone = createDeviceProtectedStorageContext()
                .getSharedPreferences("dfroot", MODE_PRIVATE)
                .getString("accent_tone", "teal");
        getTheme().applyStyle(toneOverlay(tone), true);
    }

    private static int toneOverlay(String tone) {
        switch (tone) {
            case "blue": return R.style.ThemeOverlay_DirtyFrag_Tone_Blue;
            case "purple": return R.style.ThemeOverlay_DirtyFrag_Tone_Purple;
            case "amber": return R.style.ThemeOverlay_DirtyFrag_Tone_Amber;
            default: return R.style.ThemeOverlay_DirtyFrag_Tone_Teal;
        }
    }

    /** Opt-in Fang Yuan face; default theme already uses system sans-serif. */
    private void applyUiFont() {
        String font = createDeviceProtectedStorageContext()
                .getSharedPreferences("dfroot", MODE_PRIVATE)
                .getString("ui_font", "system");
        if ("fangyuan".equals(font)) {
            getTheme().applyStyle(R.style.ThemeOverlay_DirtyFrag_Font_FangYuan, true);
        }
    }

    private static int toneButton(String tone) {
        switch (tone) {
            case "blue": return R.id.toneBlue;
            case "purple": return R.id.tonePurple;
            case "amber": return R.id.toneAmber;
            default: return R.id.toneTeal;
        }
    }

    private static String toneValue(int buttonId) {
        if (buttonId == R.id.toneBlue) return "blue";
        if (buttonId == R.id.tonePurple) return "purple";
        if (buttonId == R.id.toneAmber) return "amber";
        return "teal";
    }

    private int resolveThemeColor(int attr) {
        android.util.TypedValue value = new android.util.TypedValue();
        getTheme().resolveAttribute(attr, value, true);
        return value.data;
    }

    private void setupAppearanceSelectors() {
        SharedPreferences prefs = mDeCtx.getSharedPreferences("dfroot", MODE_PRIVATE);
        com.google.android.material.button.MaterialButtonToggleGroup theme =
                settings(R.id.themeSelector);
        com.google.android.material.button.MaterialButtonToggleGroup language =
                settings(R.id.languageSelector);
        com.google.android.material.button.MaterialButtonToggleGroup tone =
                settings(R.id.toneSelector);
        if (theme == null || language == null) return;
        theme.check(prefs.getBoolean("dark_mode", false)
                ? R.id.themeDark : R.id.themeLight);
        String langPref = prefs.getString("language", "zh-TW");
        if ("en".equals(langPref)) language.check(R.id.languageEn);
        else if ("zh-TW".equals(langPref) || "zh_TW".equals(langPref) || "zh-Hant".equals(langPref))
            language.check(R.id.languageZhTw);
        else language.check(R.id.languageZh);
        theme.addOnButtonCheckedListener((group, id, checked) -> {
            if (!checked || running) return;
            boolean dark = id == R.id.themeDark;
            if (prefs.getBoolean("dark_mode", false) == dark) return;
            prefs.edit().putBoolean("dark_mode", dark).apply();
            applyThemeAndLanguage();
        });
        language.addOnButtonCheckedListener((group, id, checked) -> {
            if (!checked || running) return;
            String lang = id == R.id.languageEn ? "en"
                    : (id == R.id.languageZhTw ? "zh-TW" : "zh");
            if (lang.equals(prefs.getString("language", "zh-TW"))) return;
            prefs.edit().putString("language", lang).apply();
            recreate();
        });
        if (tone != null) {
            tone.check(toneButton(prefs.getString("accent_tone", "teal")));
            tone.addOnButtonCheckedListener((group, id, checked) -> {
                if (!checked || running) return;
                String value = toneValue(id);
                if (value.equals(prefs.getString("accent_tone", "teal"))) return;
                prefs.edit().putString("accent_tone", value).apply();
                recreate();
            });
        }
        com.google.android.material.button.MaterialButtonToggleGroup font =
                settings(R.id.fontSelector);
        if (font == null) return;
        font.check("fangyuan".equals(prefs.getString("ui_font", "system"))
                ? R.id.fontFangYuan : R.id.fontSystem);
        font.addOnButtonCheckedListener((group, id, checked) -> {
            if (!checked || running) return;
            String value = id == R.id.fontFangYuan ? "fangyuan" : "system";
            if (value.equals(prefs.getString("ui_font", "system"))) return;
            prefs.edit().putString("ui_font", value).apply();
            recreate();
        });
    }

    private void setAppearanceSelectorsEnabled(boolean enabled) {
        int[] ids = {R.id.themeSelector, R.id.themeLight, R.id.themeDark,
                R.id.toneSelector, R.id.toneTeal, R.id.toneBlue,
                R.id.tonePurple, R.id.toneAmber,
                R.id.fontSelector, R.id.fontSystem, R.id.fontFangYuan,
                R.id.languageSelector, R.id.languageZh, R.id.languageZhTw, R.id.languageEn};
        for (int id : ids) {
            View v = settings(id);
            if (v != null) v.setEnabled(enabled);
        }
    }

    @Override
    protected void onDestroy() {
        if (updateChecker != null) updateChecker.cancel();
        super.onDestroy();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        outState.putInt("selected_nav", selectedNav);
        outState.putBoolean("failed_run", failedRun);
        super.onSaveInstanceState(outState);
    }
}
