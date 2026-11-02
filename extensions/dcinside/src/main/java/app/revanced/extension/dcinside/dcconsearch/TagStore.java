package app.revanced.extension.dcinside.dcconsearch;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Local copy of the synced tag data, in the same JSON shapes as the Nextcloud folder:
 * <ul>
 * <li>{@code ai_<pack>.json}: one pack's AI tags ({@code p_<pack>.json} on Nextcloud).</li>
 * <li>{@code edits.json}: manual edits keyed by "&lt;package&gt;:&lt;detail&gt;".</li>
 * <li>{@code state.json}: recent DCCons, recent searches and sync bookkeeping.</li>
 * </ul>
 * Search works from this copy, so it keeps working offline.
 */
public final class TagStore {
    private static final String TAG = "DcconSearch";
    private static final int MAX_RECENT = 32;
    private static final int MAX_RECENT_QUERIES = 8;

    private static File dir;
    private static Map<String, JSONObject> ai;
    private static JSONObject edits;
    private static JSONObject state;
    private static volatile int version;

    private TagStore() {
    }

    public static synchronized void init(Context context) {
        if (dir != null) return;
        dir = new File(context.getApplicationContext().getFilesDir(), "dccon_tagger");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
    }

    /** Bumped on every change that affects search results. */
    public static int version() {
        return version;
    }

    // region AI tags

    /** @return The pack's AI data ({id, title, icon, s: {detail: {...}}}), or null. */
    public static synchronized JSONObject ai(long pack) {
        loadAi();
        return ai.get(String.valueOf(pack));
    }

    /** Merges a pack downloaded from Nextcloud; the newest entry per DCCon wins. */
    public static synchronized void mergeAi(String pack, JSONObject remote) {
        loadAi();
        JSONObject local = ai.get(pack);
        JSONObject merged = remote;
        if (local != null) {
            merged = copy(local);
            for (Iterator<String> it = remote.keys(); it.hasNext(); ) {
                String k = it.next();
                if (!k.equals("s")) put(merged, k, remote.opt(k));
            }
            put(merged, "s", mergeMap(local.optJSONObject("s"), remote.optJSONObject("s")));
        }
        ai.put(pack, merged);
        write("ai_" + pack + ".json", merged.toString());
        version++;
    }

    private static void loadAi() {
        if (ai != null) return;
        ai = new HashMap<>();
        File[] files = dir.listFiles((d, name) -> name.startsWith("ai_") && name.endsWith(".json"));
        if (files == null) return;
        for (File f : files) {
            JSONObject o = read(f.getName());
            if (o != null) ai.put(f.getName().substring(3, f.getName().length() - 5), o);
        }
    }

    // endregion

    // region Manual edits

    /** @return All edits; treat as read-only. */
    public static synchronized JSONObject edits() {
        if (edits == null) {
            edits = read("edits.json");
            if (edits == null) edits = new JSONObject();
        }
        return edits;
    }

    public static synchronized String editsJson() {
        return edits().toString();
    }

    public static synchronized void mergeEdits(JSONObject remote) {
        edits = mergeMap(edits(), remote);
        write("edits.json", edits.toString());
        version++;
    }

    /** @param value {k: [tags], t: text} for an edit, or {del: true} to go back to the AI tags. */
    public static synchronized void putEdit(String key, JSONObject value) {
        put(value, "at", System.currentTimeMillis());
        put(edits(), key, value);
        write("edits.json", edits.toString());
        version++;
    }

    // endregion

    // region Recent DCCons, recent searches, sync state

    public static synchronized List<String> recent() {
        return strings(state().optJSONArray("recent"));
    }

    public static synchronized void recordRecent(String key) {
        putFront("recent", key, MAX_RECENT);
    }

    public static synchronized List<String> recentQueries() {
        return strings(state().optJSONArray("recentQ"));
    }

    public static synchronized void recordQuery(String query) {
        query = query.trim();
        if (!query.isEmpty()) putFront("recentQ", query, MAX_RECENT_QUERIES);
    }

    public static synchronized JSONObject etags() {
        JSONObject o = state().optJSONObject("etags");
        return o == null ? new JSONObject() : copy(o);
    }

    public static synchronized void setEtags(JSONObject etags) {
        put(state(), "etags", etags);
        saveState();
    }

    public static synchronized long lastPull() {
        return state().optLong("lastPull", 0);
    }

    public static synchronized void setLastPull(long time) {
        put(state(), "lastPull", time);
        saveState();
    }

    private static JSONObject state() {
        if (state == null) {
            state = read("state.json");
            if (state == null) state = new JSONObject();
        }
        return state;
    }

    private static void saveState() {
        write("state.json", state().toString());
    }

    private static void putFront(String name, String value, int max) {
        List<String> list = strings(state().optJSONArray(name));
        list.remove(value);
        list.add(0, value);
        JSONArray out = new JSONArray();
        for (int i = 0; i < list.size() && i < max; i++) out.put(list.get(i));
        put(state(), name, out);
        saveState();
    }

    // endregion

    // region JSON helpers

    /** Newest {@code at} wins per key. */
    static JSONObject mergeMap(JSONObject a, JSONObject b) {
        JSONObject out = a == null ? new JSONObject() : copy(a);
        if (b == null) return out;
        for (Iterator<String> it = b.keys(); it.hasNext(); ) {
            String k = it.next();
            JSONObject theirs = b.optJSONObject(k);
            JSONObject ours = out.optJSONObject(k);
            if (theirs == null) continue;
            if (ours == null || theirs.optLong("at", 0) > ours.optLong("at", 0)) put(out, k, theirs);
        }
        return out;
    }

    static List<String> strings(JSONArray array) {
        List<String> out = new ArrayList<>();
        if (array == null) return out;
        for (int i = 0; i < array.length(); i++) {
            String s = array.optString(i, "").trim();
            if (!s.isEmpty() && !out.contains(s)) out.add(s);
        }
        return out;
    }

    private static JSONObject copy(JSONObject o) {
        try {
            return new JSONObject(o.toString());
        } catch (Exception ex) {
            return new JSONObject();
        }
    }

    static void put(JSONObject o, String key, Object value) {
        try {
            o.put(key, value);
        } catch (Exception ex) {
            throw new IllegalArgumentException(ex);
        }
    }

    private static JSONObject read(String name) {
        File f = new File(dir, name);
        if (!f.exists()) return null;
        try (FileInputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            for (int n; (n = in.read(buf)) > 0; ) bytes.write(buf, 0, n);
            return new JSONObject(bytes.toString("UTF-8"));
        } catch (Exception ex) {
            Log.e(TAG, "Could not read " + name, ex);
            return null;
        }
    }

    private static void write(String name, String content) {
        File tmp = new File(dir, name + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(content.getBytes(StandardCharsets.UTF_8));
        } catch (Exception ex) {
            Log.e(TAG, "Could not write " + name, ex);
            return;
        }
        //noinspection ResultOfMethodCallIgnored
        tmp.renameTo(new File(dir, name));
    }

    // endregion
}
