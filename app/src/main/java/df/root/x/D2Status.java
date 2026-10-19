package df.root;

import android.app.KeyguardManager;
import android.content.Context;
import android.os.Build;
import android.provider.Settings;

import java.util.Locale;

/**
 * Whether this firmware will let the device into Odin (download) mode, or answer
 * a download-mode request with Samsung's D2 error.
 *
 * Galaxy firmwares from around April 2026 refuse download mode when the device is
 * locked in the everyday sense of the word. Samsung's system_server runs a
 * com.android.server.DmcService that writes three booleans - the DMC vault - into
 * a vaultkeeper trustlet in the TEE, and the bootloader reads them back before
 * deciding whether to start Odin or print the D2 error. The vault is
 * [lock, maintenance, at_command]: lock follows the lock-screen credential,
 * maintenance follows Samsung's maintenance mode, at_command is set by the
 * AT+SUDDLMOD / AT+FUS service escape hatch and cleared by the next boot. The
 * bootloader refuses Odin on [1, 0, 0].
 *
 * The vault itself is unreadable from an app: it lives in a trustlet behind
 * android.permission.ACCESS_VAULTKEEPER_SERVICE, which is signature|privileged,
 * and DirtyFrag is deliberately not a root client - it is the tool that gets you
 * root, so it has to work on a device that has none. Reading the vault is what
 * the first attempt at this feature did, which is why it could only ever answer
 * "unsupported" on a real Galaxy.
 *
 * So instead of reading the vault this reads the two inputs the vault is built
 * from, both of them public API: the lock-screen credential through
 * KeyguardManager, and maintenance mode through the Settings provider. That is
 * the same pair of signals DmcService turns into [lock, maintenance], so the
 * answer is computed from the same facts rather than copied out of a vault we
 * cannot open.
 *
 * The one input that cannot be read is at_command. It is assumed to be 0, which
 * is what it is after every boot unless a service tool has just authorised the
 * device, and it fails in the safe direction: a wrong guess can only make the dot
 * red when Odin would in fact have worked, never green when it would not.
 *
 * The dot is a verdict, and a verdict you cannot argue with is just a coloured
 * dot, so the pill also hands back the three facts behind it - lock screen,
 * maintenance mode, and whether this firmware gates download mode at all. The
 * toolbar submenu shows them as three lines, which means a dot the owner
 * disagrees with can be read straight off the screen instead of having to be
 * dug out of logcat.
 */
final class D2Status {

    /** What the toolbar dot is painted. */
    enum Dot {
        /** Nothing to indicate: not Samsung firmware, or older than the lockdown. */
        GREY,
        /** Odin will be let in: no credential, or maintenance mode holding the door. */
        GREEN,
        /** The D2 error: download mode refused while the device is locked. */
        RED
    }

    static final class Result {
        /** Does this firmware gate download mode at all? */
        final boolean supported;
        /** Is a PIN, pattern or password set? That is the vault's lock. */
        final boolean lockscreen;
        /** Is Samsung's maintenance mode on? */
        final boolean maintenance;
        final Dot dot;
        /** One line naming every input, including the ones the rows leave out. */
        final String detail;

        Result(boolean supported, boolean lockscreen, boolean maintenance,
               Dot dot, String detail) {
            this.supported = supported;
            this.lockscreen = lockscreen;
            this.maintenance = maintenance;
            this.dot = dot;
            this.detail = detail;
        }

        /** The three submenu rows, worded the way the pill means them. */
        String lockscreenLabel() {
            return lockscreen ? "Yes" : "No";
        }

        String maintenanceLabel() {
            return maintenance ? "On" : "Off";
        }

        String deviceLabel() {
            return supported ? "Supported" : "Unsupported";
        }
    }

    /**
     * Settings provider keys Samsung has been seen using for maintenance mode,
     * Global first then Secure. An absent key simply reads back null, so a wrong
     * guess costs a pessimistic red dot rather than a crash - the submenu row
     * then reads Off, and the detail line names the key that answered, so if
     * Samsung renames it in some future firmware a wrong dot is traceable from one
     * log line instead of failing silently the way the vault read did.
     */
    private static final String[] MAINTENANCE_KEYS = {
            "maintenance_mode",
            "maintenance_mode_status",
            "sec_maintenance_mode",
    };

    /**
     * First security patch level that carries the lockdown. Older firmware has no
     * DmcService and no vault, so a red dot there would be a lie and it gets a
     * grey one instead. The patch level is only a proxy for the firmware
     * generation, which is why it can decide grey-versus-red but never green.
     */
    private static final String FIRST_LOCKDOWN_PATCH = "2026-04-01";

    /**
     * Reads the state. Cheap, but call it off the UI thread anyway: probing the
     * Settings provider is a binder round trip per key.
     */
    static Result read(Context ctx) {
        // A device only "supports" the indicator when its firmware gates download
        // mode at all: no non-Samsung firmware does, and neither does Samsung
        // firmware older than the lockdown. Those get a grey dot rather than a
        // pill that quietly vanishes, and the submenu says why in one word.
        boolean supported = isSamsung() && !predatesLockdown();
        boolean secure = isLockScreenSecured(ctx);
        String maintenanceKey = maintenanceModeKey(ctx);
        boolean maintenance = maintenanceKey != null;

        // The bootloader refuses Odin on [1, 0, 0] - a credential, no maintenance
        // mode and no service authorisation - so a free lock screen or an open
        // maintenance mode both mean green. at_command is assumed cleared, which
        // can only ever make the dot too pessimistic, never too optimistic.
        Dot dot = !supported ? Dot.GREY
                : (secure && !maintenance) ? Dot.RED : Dot.GREEN;

        StringBuilder d = new StringBuilder();
        d.append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
                .append(", patch ").append(Build.VERSION.SECURITY_PATCH)
                .append(": lockscreen=").append(secure ? "credential" : "none")
                .append(" maintenance=").append(maintenanceKey != null ? maintenanceKey : "off or unreadable")
                .append(" at_command=0 assumed -> ");
        switch (dot) {
            case RED:
                d.append("red, D2 error expected: download mode refused");
                break;
            case GREEN:
                d.append("green, Odin allowed");
                break;
            default:
                d.append("grey, this device does not gate download mode");
                break;
        }
        return new Result(supported, secure, maintenance, dot, d.toString());
    }

    private static boolean isSamsung() {
        return contains(Build.MANUFACTURER, "samsung") || contains(Build.BRAND, "samsung");
    }

    /** Does the user have a PIN, pattern or password? That is the vault's lock. */
    private static boolean isLockScreenSecured(Context ctx) {
        KeyguardManager km = (KeyguardManager) ctx.getSystemService(Context.KEYGUARD_SERVICE);
        return km != null && km.isDeviceSecure();
    }

    /** The Settings key that says maintenance mode is on, or null if none does. */
    private static String maintenanceModeKey(Context ctx) {
        for (String key : MAINTENANCE_KEYS) {
            try {
                if (isOn(Settings.Global.getString(ctx.getContentResolver(), key))
                        || isOn(Settings.Secure.getString(ctx.getContentResolver(), key))) {
                    return key;
                }
            } catch (Exception e) {
                // A key we are not allowed to read is indistinguishable from one
                // that is not there, which the detail line already admits.
            }
        }
        return null;
    }

    private static boolean isOn(String value) {
        if (value == null) return false;
        String v = value.trim();
        return v.equals("1") || v.equalsIgnoreCase("true") || v.equalsIgnoreCase("on");
    }

    /** SECURITY_PATCH is a "yyyy-MM-dd" string, so it compares as written. */
    private static boolean predatesLockdown() {
        String patch = Build.VERSION.SECURITY_PATCH;
        if (patch == null || patch.length() < 10) return true;
        return patch.compareTo(FIRST_LOCKDOWN_PATCH) < 0;
    }

    private static boolean contains(String haystack, String needle) {
        return haystack != null && haystack.toLowerCase(Locale.ROOT).contains(needle);
    }

    private D2Status() {}
}
