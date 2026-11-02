package app.revanced.extension.dcinside.dcconsearch;

import android.app.AlertDialog;
import android.content.Context;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.LinkedHashSet;
import java.util.Set;

import app.revanced.extension.dcinside.patches.DcconSearchPatch;

/**
 * Edits one DCCon's text and tags. Edits sync to other devices and are never overwritten by the AI.
 */
final class TagEditorDialog {
    interface Callback {
        void onChanged();

        void onSend();
    }

    private TagEditorDialog() {
    }

    static void show(Context ctx, View picker, SearchIndex.Item it, Callback callback) {
        int pad = Ui.dp(ctx, 18);
        LinearLayout body = new LinearLayout(ctx);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(pad, Ui.dp(ctx, 14), pad, 0);

        LinearLayout head = new LinearLayout(ctx);
        head.setGravity(Gravity.CENTER_VERTICAL);
        ImageView image = new ImageView(ctx);
        image.setBackground(Ui.rounded(Ui.faint(ctx), Ui.dp(ctx, 8)));
        DcconSearchPatch.loadImage(picker, image, it.dccon.image);
        head.addView(image, new LinearLayout.LayoutParams(Ui.dp(ctx, 88), Ui.dp(ctx, 88)));
        LinearLayout names = new LinearLayout(ctx);
        names.setOrientation(LinearLayout.VERTICAL);
        names.setPadding(Ui.dp(ctx, 12), 0, 0, 0);
        TextView alt = new TextView(ctx);
        alt.setText(it.alt.isEmpty() ? "(이름 없음)" : it.alt);
        alt.setTextColor(Ui.text(ctx));
        alt.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        names.addView(alt);
        names.addView(small(ctx, it.packTitle));
        names.addView(small(ctx, it.edited ? "직접 수정한 태그" : it.tagged ? "AI 태그 · " + it.model : "아직 AI 태그 없음"));
        head.addView(names, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        body.addView(head);

        body.addView(Ui.label(ctx, "이미지 속 글자"));
        EditText ocr = Ui.field(ctx, it.ocr, "(없음)", InputType.TYPE_CLASS_TEXT);
        body.addView(ocr);
        body.addView(Ui.label(ctx, "태그 · 쉼표로 구분"));
        EditText tags = Ui.field(ctx, TextUtils.join(", ", it.tags), "예: 감사, 웃음, 엄지척",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        tags.setMaxLines(5);
        body.addView(tags);
        if (!it.desc.isEmpty()) {
            TextView desc = small(ctx, "AI 설명: " + it.desc);
            desc.setPadding(0, Ui.dp(ctx, 8), 0, 0);
            body.addView(desc);
        }
        ScrollView scroll = new ScrollView(ctx);
        scroll.addView(body);

        AlertDialog.Builder builder = new AlertDialog.Builder(ctx)
                .setView(scroll)
                .setNegativeButton("취소", null)
                .setPositiveButton("저장", (d, w) -> {
                    JSONObject edit = new JSONObject();
                    TagStore.put(edit, "k", new JSONArray(splitTags(tags.getText().toString())));
                    TagStore.put(edit, "t", ocr.getText().toString().trim());
                    save(it, edit, callback);
                });
        // AlertDialog has three buttons: reset when edited (as in the userscript), otherwise send.
        if (it.edited) {
            builder.setNeutralButton("AI 태그로 되돌리기", (d, w) -> {
                JSONObject reset = new JSONObject();
                TagStore.put(reset, "del", true);
                save(it, reset, callback);
            });
        } else {
            builder.setNeutralButton("이 콘 보내기", (d, w) -> callback.onSend());
        }
        builder.show();
    }

    private static void save(SearchIndex.Item it, JSONObject value, Callback callback) {
        TagStore.putEdit(it.key, value);
        NextcloudSync.schedulePushEdits();
        callback.onChanged();
    }

    private static Set<String> splitTags(String text) {
        Set<String> out = new LinkedHashSet<>();
        for (String t : text.split("[,，\\n]")) {
            t = t.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    private static TextView small(Context ctx, String text) {
        TextView t = new TextView(ctx);
        t.setText(text);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        t.setTextColor(Ui.secondaryText(ctx));
        t.setPadding(0, Ui.dp(ctx, 2), 0, 0);
        return t;
    }
}
