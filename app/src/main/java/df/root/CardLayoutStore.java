package df.root;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Where the movable card stack keeps its state: a device-protected prefs file
 * of its own, dfroot_ui. It must NOT go into dfroot.xml - bootstrap.c parses
 * that file with a 4 KB buffer and a naive strstr, so nothing that only the UI
 * reads may be added to it.
 *
 * Two keys, both comma separated and both tiny:
 *   card_order   "sumanager,autorun,modules,log"
 *   card_hidden  "modules"
 */
final class CardLayoutStore {

    private static final String PREFS = "dfroot_ui";
    private static final String KEY_ORDER = "card_order";
    private static final String KEY_HIDDEN = "card_hidden";
    private static final String SEP = ",";

    private CardLayoutStore() {
    }

    /** The saved order, or the shipped default if it is unknown/broken. */
    static List<String> loadOrder(Context deCtx, String[] def) {
        List<String> saved = parse(prefs(deCtx).getString(KEY_ORDER, ""));
        List<String> expected = new ArrayList<>(Arrays.asList(def));
        if (saved.size() != expected.size() || !expected.containsAll(saved)) {
            return expected;
        }
        return saved;
    }

    static void saveOrder(Context deCtx, List<String> order) {
        prefs(deCtx).edit().putString(KEY_ORDER, join(order)).apply();
    }

    /** Keys of the cards the user hid; never contains the SU Manager card. */
    static List<String> loadHidden(Context deCtx, String[] def) {
        List<String> expected = new ArrayList<>(Arrays.asList(def));
        List<String> hidden = parse(prefs(deCtx).getString(KEY_HIDDEN, ""));
        hidden.retainAll(expected);
        return hidden;
    }

    static void saveHidden(Context deCtx, List<String> hidden) {
        prefs(deCtx).edit().putString(KEY_HIDDEN, join(hidden)).apply();
    }

    private static SharedPreferences prefs(Context deCtx) {
        return deCtx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static List<String> parse(String raw) {
        List<String> out = new ArrayList<>();
        if (raw == null || raw.isEmpty()) return out;
        for (String part : raw.split(SEP)) {
            String key = part.trim();
            if (!key.isEmpty() && !out.contains(key)) out.add(key);
        }
        return out;
    }

    private static String join(List<String> keys) {
        StringBuilder sb = new StringBuilder();
        for (String key : keys) {
            if (sb.length() > 0) sb.append(SEP);
            sb.append(key);
        }
        return sb.toString();
    }
}
