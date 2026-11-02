package app.revanced.extension.dcinside.dcconsearch;

import android.text.TextUtils;

import org.json.JSONObject;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Search over the owned DCCons, using AI tags and manual edits. Same scoring as the userscript.
 */
public final class SearchIndex {
    public static final class Item {
        public final Catalog.Dccon dccon;
        public final String key;
        public final String alt;
        public final String ocr;
        public final List<String> tags;
        public final String desc;
        public final String packTitle;
        public final String packImage;
        public final boolean tagged;
        public final boolean edited;
        public final String model;

        final String nOcr;
        final List<String> nTags = new ArrayList<>();
        final String nAlt;
        final String nDesc;
        final String nTitle;
        final String cho;

        Item(Catalog.Dccon dccon, String alt, String ocr, List<String> tags, String desc, String packTitle,
             String packImage, boolean tagged, boolean edited, String model) {
            this.dccon = dccon;
            this.key = dccon.key();
            this.alt = alt;
            this.ocr = ocr;
            this.tags = tags;
            this.desc = desc;
            this.packTitle = packTitle;
            this.packImage = packImage;
            this.tagged = tagged;
            this.edited = edited;
            this.model = model;
            nOcr = norm(ocr);
            for (String t : tags) nTags.add(norm(t));
            nAlt = norm(alt);
            nDesc = norm(desc);
            nTitle = norm(packTitle);
            cho = cho(ocr + " " + TextUtils.join(" ", tags) + " " + alt);
        }
    }

    private static final String[][] SYNONYMS = {
            {"기쁨", "웃음", "행복", "좋아", "happy", "smile"},
            {"슬픔", "울음", "눈물", "ㅠㅠ", "sad", "cry"},
            {"화남", "분노", "짜증", "빡침", "angry"},
            {"놀람", "깜짝", "헉", "surprised"},
            {"감사", "고마워", "고맙", "땡큐", "thanks", "thank"},
            {"사과", "미안", "죄송", "sorry"},
            {"인사", "안녕", "하이", "hi", "hello"},
            {"동의", "알겠", "ㅇㅋ", "오케이", "ok", "okay"},
            {"거절", "싫어", "ㄴㄴ", "no"},
            {"당황", "어리둥절", "혼란", "confused"},
            {"졸림", "잠", "피곤", "sleep", "tired"},
            {"사랑", "하트", "love", "heart"},
    };
    private static final String CHO = "ㄱㄲㄴㄷㄸㄹㅁㅂㅃㅅㅆㅇㅈㅉㅊㅋㅌㅍㅎ";
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final Pattern CHO_ONLY = Pattern.compile("^[ㄱ-ㅎ]+$");
    /** SYNONYMS, normalized once instead of on every comparison. */
    private static final List<List<String>> NORM_SYNONYMS = new ArrayList<>();

    static {
        for (String[] group : SYNONYMS) {
            List<String> normalized = new ArrayList<>();
            for (String g : group) normalized.add(norm(g));
            NORM_SYNONYMS.add(normalized);
        }
    }

    private static List<Item> items = Collections.emptyList();
    private static Map<String, Item> byKey = Collections.emptyMap();
    private static int builtCatalog = -1;
    private static int builtStore = -1;

    private SearchIndex() {
    }

    /** Rebuilds if the catalog or tag data changed. Call off the main thread. */
    public static synchronized List<Item> items() {
        if (builtCatalog != Catalog.version() || builtStore != TagStore.version()) {
            builtCatalog = Catalog.version();
            builtStore = TagStore.version();
            build();
        }
        return items;
    }

    public static synchronized Item find(String key) {
        items();
        return byKey.get(key);
    }

    private static void build() {
        JSONObject edits = TagStore.edits();
        List<Item> out = new ArrayList<>();
        Map<String, Item> keys = new HashMap<>();
        Map<Long, JSONObject> packs = new HashMap<>();
        for (Catalog.Dccon d : Catalog.dccons()) {
            JSONObject ai = packs.get(d.pack);
            if (ai == null && !packs.containsKey(d.pack)) {
                ai = TagStore.ai(d.pack);
                packs.put(d.pack, ai);
            }
            JSONObject s = ai == null ? null : ai.optJSONObject("s");
            JSONObject a = s == null ? null : s.optJSONObject(String.valueOf(d.detail));
            if (a == null) a = new JSONObject();
            JSONObject ed = edits.optJSONObject(d.key());
            if (ed != null && ed.optBoolean("del")) ed = null;
            boolean aiOk = a.has("at") && !a.has("err");

            String ocr = ed != null && ed.has("t") ? ed.optString("t") : aiOk ? a.optString("t") : "";
            List<String> tags;
            if (ed != null) {
                tags = TagStore.strings(ed.optJSONArray("k"));
            } else if (aiOk) {
                Set<String> set = new LinkedHashSet<>(TagStore.strings(a.optJSONArray("e")));
                set.addAll(TagStore.strings(a.optJSONArray("k")));
                tags = new ArrayList<>(set);
            } else {
                tags = new ArrayList<>();
            }
            Catalog.Pack pack = Catalog.pack(d.pack);
            String packTitle = pack != null ? pack.title : ai != null ? ai.optString("title") : "";
            String packImage = pack != null ? pack.image : ai != null ? ai.optString("icon") : "";
            String alt = !d.title.isEmpty() ? d.title : a.optString("alt");

            Item item = new Item(d, alt, ocr, tags, aiOk ? a.optString("d") : "", packTitle, packImage,
                    aiOk || ed != null, ed != null, aiOk ? a.optString("m") : "");
            out.add(item);
            keys.put(item.key, item);
        }
        items = out;
        byKey = keys;
    }

    public static List<Item> search(String query) {
        List<String> terms = new ArrayList<>();
        for (String t : query.trim().split("\\s+")) {
            String n = norm(t);
            if (!n.isEmpty()) terms.add(n);
        }
        if (terms.isEmpty()) return new ArrayList<>();
        // Synonyms depend only on the term, so look them up once per query rather than once per DCCon.
        List<List<String>> termVariants = new ArrayList<>();
        for (String t : terms) termVariants.add(variants(t));
        List<String> recent = TagStore.recent();
        List<Object[]> scored = new ArrayList<>();
        for (Item it : items()) {
            double total = 0;
            for (int i = 0; i < terms.size(); i++) {
                double s = termScore(it, terms.get(i), termVariants.get(i));
                if (s == 0) {
                    total = 0;
                    break;
                }
                total += s;
            }
            if (total > 0) scored.add(new Object[]{total + (recent.contains(it.key) ? 0.5 : 0), it});
        }
        // Stable sort keeps the picker's order for equal scores.
        Collections.sort(scored, (a, b) -> Double.compare((double) b[0], (double) a[0]));
        List<Item> out = new ArrayList<>(scored.size());
        for (Object[] s : scored) out.add((Item) s[1]);
        return out;
    }

    public static List<Item> recent() {
        List<Item> out = new ArrayList<>();
        for (String key : TagStore.recent()) {
            Item it = find(key);
            if (it != null) out.add(it);
        }
        return out;
    }

    private static double termScore(Item it, String t, List<String> variants) {
        if (isCho(t)) return it.cho.contains(t) ? 4 : 0;
        double best = 0;
        for (String v : variants) {
            double s = 0;
            if (it.nTags.contains(v)) {
                s = 10;
            } else {
                for (String x : it.nTags) {
                    if (x.contains(v)) {
                        s = 6;
                        break;
                    }
                }
            }
            if (!it.nOcr.isEmpty() && it.nOcr.contains(v)) s = Math.max(s, it.nOcr.equals(v) ? 10 : 7);
            if (it.nAlt.contains(v)) s = Math.max(s, 5);
            if (s == 0 && it.nDesc.contains(v)) s = 2;
            if (s == 0 && it.nTitle.contains(v)) s = 1;
            best = Math.max(best, v.equals(t) ? s : s * 0.7);
        }
        return best;
    }

    private static List<String> variants(String t) {
        List<String> out = new ArrayList<>();
        out.add(t);
        for (List<String> group : NORM_SYNONYMS) {
            if (!group.contains(t)) continue;
            for (String n : group) {
                if (!out.contains(n)) out.add(n);
            }
        }
        return out;
    }

    static String norm(String s) {
        if (s == null) return "";
        return WHITESPACE.matcher(Normalizer.normalize(s, Normalizer.Form.NFC).toLowerCase(Locale.ROOT)).replaceAll("");
    }

    private static String cho(String s) {
        StringBuilder out = new StringBuilder();
        for (char ch : norm(s).toCharArray()) {
            out.append(ch >= 0xAC00 && ch <= 0xD7A3 ? CHO.charAt((ch - 0xAC00) / 588) : ch);
        }
        return out.toString();
    }

    private static boolean isCho(String t) {
        return CHO_ONLY.matcher(t).matches();
    }
}
