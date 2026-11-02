package app.revanced.extension.dcinside.dcconsearch;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.parsers.DocumentBuilderFactory;

import okhttp3.Credentials;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Syncs with the userscript's Nextcloud folder over WebDAV.
 * Folder files: {@code p_<pack>.json} (written by the PC tagger or an agent tagger), {@code edits.json}
 * (manual edits from any device) and {@code catalog.json} (the owned DCCon list, uploaded by the app for agent taggers).
 * The phone downloads AI tags, uploads edits.json after a manual edit and catalog.json when the owned list changes.
 */
public final class NextcloudSync {
    private static final String TAG = "DcconSearch";
    private static final String PREFS = "dccon_tagger";
    public static final String DEFAULT_DIR = "DCConTagger/data";
    private static final long AUTO_PULL_INTERVAL_MS = 10 * 60 * 1000;
    private static final long PUSH_DELAY_MS = 2500;
    private static final Pattern PACK_FILE = Pattern.compile("^p_(\\d+)\\.json$");
    /** The owned DCCon list, uploaded for agent taggers (see tools/agent-tagger). */
    private static final String CATALOG_FILE = "catalog.json";
    private static final MediaType JSON = MediaType.parse("application/json");
    private static final MediaType XML = MediaType.parse("application/xml");

    /** One background thread for all sync and index work, so they never overlap. */
    public static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static OkHttpClient client;
    private static SharedPreferences prefs;
    private static boolean dirReady;
    private static final Runnable pushTask = () -> EXECUTOR.execute(NextcloudSync::pushEditsNow);

    public interface Listener {
        /** Called on the main thread. {@code message} is null on success. */
        void onSyncDone(boolean changed, String message);
    }

    private NextcloudSync() {
    }

    public static synchronized void init(Context context) {
        if (prefs != null) return;
        prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        client = new OkHttpClient.Builder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .build();
    }

    // region Settings

    public static String url() {
        return prefs.getString("ncUrl", "");
    }

    public static String user() {
        return prefs.getString("ncUser", "");
    }

    public static String password() {
        return prefs.getString("ncPass", "");
    }

    public static String folder() {
        return prefs.getString("ncDir", DEFAULT_DIR);
    }

    public static void saveSettings(String url, String user, String password, String folder) {
        prefs.edit()
                .putString("ncUrl", url.trim())
                .putString("ncUser", user.trim())
                .putString("ncPass", password.trim())
                .putString("ncDir", folder.trim().isEmpty() ? DEFAULT_DIR : folder.trim())
                .apply();
        synchronized (NextcloudSync.class) {
            dirReady = false;
        }
    }

    public static boolean enabled() {
        return !url().isEmpty() && !user().isEmpty() && !password().isEmpty();
    }

    // endregion

    // region Public operations

    /** Pulls in the background if the last pull is older than ten minutes. */
    public static void autoPull(Listener listener) {
        if (!enabled() || System.currentTimeMillis() - TagStore.lastPull() < AUTO_PULL_INTERVAL_MS) return;
        pullAsync(false, listener);
    }

    /** @param force Download every file, ignoring cached ETags. */
    public static void pullAsync(boolean force, Listener listener) {
        EXECUTOR.execute(() -> {
            try {
                boolean changed = pull(force);
                MAIN.post(() -> listener.onSyncDone(changed, null));
            } catch (Exception ex) {
                Log.w(TAG, "Pull failed", ex);
                MAIN.post(() -> listener.onSyncDone(false, ex.getMessage()));
            }
        });
    }

    /** Pull, then upload edits.json. Used by the settings dialog's connection check. */
    public static void syncNowAsync(Listener listener) {
        EXECUTOR.execute(() -> {
            try {
                boolean changed = pull(true);
                putFile("edits.json", TagStore.editsJson());
                exportCatalog(true);
                MAIN.post(() -> listener.onSyncDone(changed, null));
            } catch (Exception ex) {
                Log.w(TAG, "Sync failed", ex);
                MAIN.post(() -> listener.onSyncDone(false, ex.getMessage()));
            }
        });
    }

    /** Uploads catalog.json in the background if the owned DCCon list changed since the last upload. */
    public static void exportCatalogAsync() {
        if (prefs == null || !enabled()) return;
        EXECUTOR.execute(() -> {
            try {
                exportCatalog(false);
            } catch (Exception ex) {
                Log.w(TAG, "Uploading the DCCon catalog failed", ex);
            }
        });
    }

    /**
     * Uploads the owned packs and DCCons as catalog.json, for agent taggers that write p_&lt;pack&gt;.json.
     * @param force Upload even if the catalog is unchanged.
     */
    private static void exportCatalog(boolean force) throws IOException, JSONException, NoSuchAlgorithmException {
        JSONArray packs = Catalog.toJson();
        if (packs.length() == 0) return;

        // Remember what was uploaded where, so reopening the picker doesn't upload the same list again.
        String hash = sha1(url() + "\n" + user() + "\n" + folder() + "\n" + packs);
        if (!force && hash.equals(prefs.getString("catalogHash", ""))) return;

        JSONObject catalog = new JSONObject()
                .put("v", 1)
                .put("at", System.currentTimeMillis())
                .put("source", "dcinside-app")
                .put("packs", packs);
        putFile(CATALOG_FILE, catalog.toString());
        prefs.edit().putString("catalogHash", hash).apply();
        Log.i(TAG, "Uploaded " + CATALOG_FILE + " with " + packs.length() + " packs");
    }

    private static String sha1(String text) throws NoSuchAlgorithmException {
        byte[] digest = MessageDigest.getInstance("SHA-1").digest(text.getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder();
        for (byte b : digest) hex.append(String.format("%02x", b));
        return hex.toString();
    }

    /** Uploads edits shortly after the last edit, after picking up other devices' edits first. */
    public static void schedulePushEdits() {
        if (!enabled()) return;
        MAIN.removeCallbacks(pushTask);
        MAIN.postDelayed(pushTask, PUSH_DELAY_MS);
    }

    private static void pushEditsNow() {
        try {
            pull(false);
            putFile("edits.json", TagStore.editsJson());
        } catch (Exception ex) {
            Log.w(TAG, "Uploading edits failed", ex);
        }
    }

    // endregion

    // region WebDAV

    /** Lists the folder with ETags and downloads only the files that changed since the last pull. */
    private static boolean pull(boolean force) throws IOException {
        if (!enabled()) return false;
        String dir = davDir();
        String propfind = "<?xml version=\"1.0\"?><d:propfind xmlns:d=\"DAV:\"><d:prop><d:getetag/></d:prop></d:propfind>";
        Request.Builder listing = request(dir)
                .header("Depth", "1")
                .method("PROPFIND", RequestBody.create(propfind, XML));
        String body;
        try (Response r = execute(listing)) {
            TagStore.setLastPull(System.currentTimeMillis());
            if (r.code() == 404) return false; // The folder is created on the first upload.
            if (r.code() != 207) throw new IOException("Nextcloud " + r.code());
            body = bodyString(r);
        }

        JSONObject etags = force ? new JSONObject() : TagStore.etags();
        JSONObject seen = new JSONObject();
        boolean changed = false;
        NodeList responses = parseXml(body).getElementsByTagNameNS("DAV:", "response");
        for (int i = 0; i < responses.getLength(); i++) {
            Element res = (Element) responses.item(i);
            String href = text(res, "href").replaceAll("/+$", "");
            String name = Uri.decode(href.substring(href.lastIndexOf('/') + 1));
            String etag = text(res, "getetag");
            Matcher pack = PACK_FILE.matcher(name);
            boolean isPack = pack.matches();
            if (!isPack && !name.equals("edits.json")) continue;
            TagStore.put(seen, name, etag);
            if (!etag.isEmpty() && etag.equals(etags.optString(name))) continue;

            JSONObject data;
            try (Response f = execute(request(dir + Uri.encode(name)).get())) {
                if (f.code() != 200) continue;
                data = new JSONObject(bodyString(f));
            } catch (org.json.JSONException ex) {
                continue;
            }
            if (isPack) TagStore.mergeAi(pack.group(1), data);
            else TagStore.mergeEdits(data);
            changed = true;
        }
        TagStore.setEtags(seen);
        return changed;
    }

    private static void putFile(String name, String content) throws IOException {
        if (!enabled()) return;
        ensureDir();
        Request.Builder put = request(davDir() + Uri.encode(name)).put(RequestBody.create(content, JSON));
        try (Response r = execute(put)) {
            if (r.code() >= 300) throw new IOException("Nextcloud 업로드 실패 " + r.code());
            // Remember our own upload so the next pull doesn't download it again.
            JSONObject etags = TagStore.etags();
            String etag = r.header("ETag");
            if (etag != null && !etag.isEmpty()) TagStore.put(etags, name, etag);
            else etags.remove(name);
            TagStore.setEtags(etags);
        }
    }

    /** Creates the sync folder and its parents once. */
    private static void ensureDir() throws IOException {
        synchronized (NextcloudSync.class) {
            if (dirReady) return;
        }
        String url = davRoot();
        for (String part : folder().split("/")) {
            if (part.isEmpty()) continue;
            url += Uri.encode(part) + "/";
            try (Response r = execute(request(url).method("MKCOL", null))) {
                if (r.code() != 201 && r.code() != 405) throw new IOException("Nextcloud 폴더 만들기 실패 " + r.code());
            }
        }
        synchronized (NextcloudSync.class) {
            dirReady = true;
        }
    }

    /** Accepts either the bare server address or one copied from the browser (…/index.php/apps/files etc). */
    private static String davRoot() {
        String base = url().replaceAll("/+$", "").replaceAll("/(index\\.php|remote\\.php|apps)(/.*)?$", "");
        return base + "/remote.php/dav/files/" + Uri.encode(user()) + "/";
    }

    private static String davDir() {
        StringBuilder dir = new StringBuilder(davRoot());
        for (String part : folder().split("/")) {
            if (!part.isEmpty()) dir.append(Uri.encode(part)).append('/');
        }
        return dir.toString();
    }

    private static Request.Builder request(String url) {
        return new Request.Builder()
                .url(url)
                .header("Authorization", Credentials.basic(user(), password()));
    }

    private static Response execute(Request.Builder request) throws IOException {
        Response r;
        try {
            r = client.newCall(request.build()).execute();
        } catch (IOException ex) {
            throw new IOException("Nextcloud에 연결할 수 없습니다. Tailscale이 켜져 있는지 확인하세요.", ex);
        }
        if (r.code() == 401) {
            r.close();
            throw new IOException("Nextcloud 로그인 실패 (사용자 이름/앱 비밀번호 확인)");
        }
        return r;
    }

    private static String bodyString(Response r) throws IOException {
        ResponseBody body = r.body();
        return body == null ? "" : body.string();
    }

    private static Document parseXml(String xml) throws IOException {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            return factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IOException("Nextcloud 응답을 읽을 수 없습니다", ex);
        }
    }

    private static String text(Element parent, String davName) {
        NodeList list = parent.getElementsByTagNameNS("DAV:", davName);
        return list.getLength() == 0 ? "" : list.item(0).getTextContent().trim();
    }

    // endregion
}
