package app.revanced.extension.dcinside.dcconsearch;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The DCCons the user owns, as last loaded by the app's DCCon picker.
 */
public final class Catalog {
    public static final class Pack {
        public final long id;
        public final String title;
        public final String image;

        public Pack(long id, String title, String image) {
            this.id = id;
            this.title = title == null ? "" : title;
            this.image = image == null ? "" : image;
        }
    }

    public static final class Dccon {
        /** The app's own DCCon object, handed back to the app to send it. */
        public final Object icon;
        public final long pack;
        public final long detail;
        public final String title;
        public final String image;

        public Dccon(Object icon, long pack, long detail, String title, String image) {
            this.icon = icon;
            this.pack = pack;
            this.detail = detail;
            this.title = title == null ? "" : title;
            this.image = image == null ? "" : image;
        }

        /** Same key format as the userscript and edits.json: "<package>:<detail>". */
        public String key() {
            return pack + ":" + detail;
        }
    }

    private static volatile List<Pack> packs = Collections.emptyList();
    private static volatile List<Dccon> dccons = Collections.emptyList();
    private static volatile Map<Long, Pack> packById = Collections.emptyMap();
    private static volatile int version;

    private Catalog() {
    }

    public static void set(List<Pack> newPacks, List<Dccon> newDccons) {
        Map<Long, Pack> byId = new HashMap<>();
        for (Pack p : newPacks) byId.put(p.id, p);
        packs = Collections.unmodifiableList(newPacks);
        dccons = Collections.unmodifiableList(newDccons);
        packById = byId;
        version++;
    }

    public static List<Pack> packs() {
        return packs;
    }

    public static List<Dccon> dccons() {
        return dccons;
    }

    public static Pack pack(long id) {
        return packById.get(id);
    }

    public static int version() {
        return version;
    }

    /**
     * The owned packs and their DCCons for catalog.json, which agent taggers read:
     * [{id, title, icon, dccons: [{d, alt, img}]}] in the picker's order.
     */
    public static JSONArray toJson() throws JSONException {
        Map<Long, JSONArray> byPack = new LinkedHashMap<>();
        for (Dccon d : dccons) {
            JSONArray list = byPack.get(d.pack);
            if (list == null) byPack.put(d.pack, list = new JSONArray());
            list.put(new JSONObject()
                    .put("d", String.valueOf(d.detail))
                    .put("alt", d.title)
                    .put("img", d.image));
        }
        JSONArray out = new JSONArray();
        for (Pack p : packs) {
            JSONArray list = byPack.get(p.id);
            out.put(new JSONObject()
                    .put("id", String.valueOf(p.id))
                    .put("title", p.title)
                    .put("icon", p.image)
                    .put("dccons", list == null ? new JSONArray() : list));
        }
        return out;
    }
}
