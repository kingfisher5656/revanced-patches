package app.revanced.extension.dcinside.patches;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;

import app.morphe.extension.shared.Utils;
import okhttp3.FormBody;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * The DCCon picker loads every owned pack and all of its DCCons with one api/dccon.php (type=list) request.
 * Without a page number the server tries to build the whole list, gives up after 30 seconds with many packs and
 * answers {"tab":[],"list":[],"page":1,"totalpage":N}. The app caches that empty answer and the picker shows
 * "사용 가능한 디시콘이 없습니다".
 * Single pages load in about half a second, so this patch requests every page and merges them into one answer.
 */
@SuppressWarnings("unused")
public final class DcconListLoadingPatch {
    private static final String TAG = "DcconListLoading";
    private static final String DCCON_API_PATH = "/api/dccon.php";
    private static final int MAX_PAGES = 100;

    private DcconListLoadingPatch() {
    }

    /**
     * @return If this patch was included during patching.
     */
    public static boolean isPatchIncluded() {
        return false;  // Modified during patching.
    }

    /**
     * Injection point: replaces chain.proceed(request) at the end of the app's OkHttp interceptor.
     */
    public static Response proceed(Interceptor.Chain chain, Request request) throws IOException {
        FormBody form = unpagedListForm(request);
        if (form == null) return chain.proceed(request);

        try {
            return proceedPaged(chain, request, form);
        } catch (JSONException ex) {
            throw new IOException("Unexpected DCCon list page", ex);
        }
    }

    /**
     * @return The form of an owned DCCon list request without a page number, or null for any other request.
     */
    private static FormBody unpagedListForm(Request request) {
        if (!DCCON_API_PATH.equals(request.url().encodedPath()) || !(request.body() instanceof FormBody)) return null;

        FormBody form = (FormBody) request.body();
        boolean isList = false;
        for (int i = 0; i < form.size(); i++) {
            if (form.name(i).equals("page")) return null;
            if (form.name(i).equals("type") && form.value(i).equals("list")) isList = true;
        }
        return isList ? form : null;
    }

    private static Response proceedPaged(Interceptor.Chain chain, Request request, FormBody form)
            throws IOException, JSONException {
        long start = System.currentTimeMillis();
        JSONObject merged = null;
        JSONArray tabs = new JSONArray();
        JSONArray lists = new JSONArray();
        Response first = null;
        int totalPages = 1;

        for (int page = 1; page <= totalPages; page++) {
            Response response = chain.proceed(request.newBuilder().post(withPage(form, page)).build());
            if (!response.isSuccessful()) {
                if (page == 1) return response;  // Let the app handle the error.
                response.close();
                throw new IOException("DCCon list page " + page + " failed: " + response.code());
            }

            JSONObject json;
            try (ResponseBody body = response.body()) {
                json = new JSONObject(body == null ? "" : body.string());
            }
            if (page == 1) {
                first = response;
                merged = json;
                totalPages = Math.max(1, Math.min(json.optInt("totalpage", 1), MAX_PAGES));
            }
            append(tabs, json.optJSONArray("tab"));
            append(lists, json.optJSONArray("list"));
            if (json.optBoolean("hasFav")) merged.put("hasFav", true);
        }

        merged.put("tab", tabs);
        merged.put("list", lists);
        merged.put("page", 1);
        merged.put("totalpage", 1);
        Log.i(TAG, "Loaded " + totalPages + " DCCon list pages, " + tabs.length() + " packs, in "
                + (System.currentTimeMillis() - start) + "ms");

        return first.newBuilder()
                .request(request)
                .body(ResponseBody.create(merged.toString(), MediaType.parse("application/json; charset=utf-8")))
                .build();
    }

    private static FormBody withPage(FormBody form, int page) {
        FormBody.Builder builder = new FormBody.Builder();
        for (int i = 0; i < form.size(); i++) builder.add(form.name(i), form.value(i));
        return builder.add("page", String.valueOf(page)).build();
    }

    private static void append(JSONArray target, JSONArray items) throws JSONException {
        if (items == null) return;
        for (int i = 0; i < items.length(); i++) target.put(items.get(i));
    }

    /**
     * Injection point: the owned DCCon list request failed and the app is about to fall back to an empty list.
     */
    public static void onListFailed(Throwable error) {
        Log.e(TAG, "DCCon list request failed", error);
        try {
            Utils.showToastLong("디시콘 목록을 불러오지 못했습니다: " + describe(error)
                    + "\n피커를 다시 열면 재시도합니다.");
        } catch (Throwable ex) {
            Log.e(TAG, "onListFailed failure", ex);
        }
    }

    private static String describe(Throwable error) {
        if (error == null) return "알 수 없는 오류";
        Throwable root = error;
        while (root.getCause() != null && root.getCause() != root) root = root.getCause();
        String message = root.getMessage();
        return root.getClass().getSimpleName() + (message == null ? "" : " (" + message + ")");
    }
}
