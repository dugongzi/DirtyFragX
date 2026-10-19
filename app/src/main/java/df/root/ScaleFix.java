package df.root;

import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.util.DisplayMetrics;
import android.util.Log;

/**
 * Pins the interface scale of the app to the scale the whole UI was laid out
 * against, whatever the user's system sliders say.
 *
 * <p>Every number in this interface was calibrated on a Galaxy S25 (1080 px
 * wide) with One UI screen zoom one step left of default, which puts the panel
 * at 450 dpi instead of its factory 480. That is a width of 1080 / (450 / 160)
 * = 384 dp. The layout then mixes percentages with fixed dp: the Run pill is
 * 48% of the parent minus a fixed 66dp (KSU_GAP_DP + KSU_CIRCLE_DP), and the
 * toolbar, the 54dp circles and the 32dp title margin are fixed dp holding sp
 * text. A percentage and a dp only agree at one screen width in dp, so the
 * moment the width moves the pill allowance is wrong, the toolbar title with
 * its version pill clips, and the SU-manager label gets cut out of its pill.
 * Moving the slider to default makes the panel 360 dp and breaks it; every
 * other position breaks differently, which is exactly what was seen on device.
 *
 * <p>Rather than re-tune the geometry, the app simply lays itself out at the
 * width it was designed for. The density is derived from the real pixel width
 * so this holds on any panel and any slider position, and fontScale is pinned
 * to 1.0 because the Font size slider breaks the same fixed-dp containers on
 * its own, independently of Screen zoom.
 *
 * <p>Two deliberate limits. The anchor is the shorter side of the current app
 * bounds, so rotation cannot turn a 2340 px wide landscape into a bogus
 * density, and in Samsung split-screen or pop-up view the app keeps the width
 * it actually has. And the correction is capped at MAX_CORRECTION away from
 * what the device reports, so an extreme slider position leaves the app
 * slightly off-scale rather than shrinking it to a poster or blowing it up.
 *
 * <p>The cost of this is honest: the user's own accessibility setting no longer
 * applies inside DirtyFrag. It is a lock, not a compromise, so if that ever
 * needs to be relaxed it should be a switch in the Menu sheet rather than a
 * different constant here.
 */
public final class ScaleFix {

    /** The width the UI was calibrated at, in dp: 1080 px at 450 dpi. */
    private static final int CALIBRATION_WIDTH_DP = 384;

    /** How far the lock may move the device's reported density, either way. */
    private static final float MAX_CORRECTION = 1.30f;

    private ScaleFix() { }

    /**
     * Wrap of the activity's base context, to be called from
     * {@code attachBaseContext} before super. Everything the activity resolves
     * afterwards - inflation, dp, sp, theme dimens, vector sizing, popups -
     * goes through the returned context and therefore through the pinned
     * configuration.
     *
     * <p>Returns the base context untouched when the numbers already match, so
     * a device at the calibration scale pays nothing.
     */
    public static Context wrap(Context base) {
        Resources res = base.getResources();
        DisplayMetrics dm = res.getDisplayMetrics();
        Configuration cfg = new Configuration(res.getConfiguration());

        // Shorter side, so rotation and multi-window cannot skew the anchor.
        int sidePx = Math.min(dm.widthPixels, dm.heightPixels);
        int want = Math.round(sidePx * 160f / CALIBRATION_WIDTH_DP);
        int lo = Math.round(dm.densityDpi / MAX_CORRECTION);
        int hi = Math.round(dm.densityDpi * MAX_CORRECTION);
        int dpi = Math.max(lo, Math.min(hi, want));
        boolean densityLocked = cfg.densityDpi != dpi;
        boolean fontLocked = Math.abs(cfg.fontScale - 1f) > 0.001f;
        if (!densityLocked && !fontLocked) return base;

        Log.i("DirtyFrag", "scale lock: " + cfg.densityDpi + "dpi/font " + cfg.fontScale
                + " -> " + dpi + "dpi/font 1.0 (" + sidePx + "px at "
                + CALIBRATION_WIDTH_DP + "dp)");
        cfg.densityDpi = dpi;
        cfg.fontScale = 1f;
        return base.createConfigurationContext(cfg);
    }
}
