package app.revanced.extension.dcinside.dcconsearch;

import android.app.AlertDialog;
import android.content.Context;
import android.text.InputType;
import android.util.TypedValue;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * Nextcloud settings: the same server, username, app password and folder as the userscript on the PC.
 */
final class SyncSettingsDialog {
    private SyncSettingsDialog() {
    }

    static void show(Context ctx, NextcloudSync.Listener onSynced) {
        int pad = Ui.dp(ctx, 18);
        LinearLayout body = new LinearLayout(ctx);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(pad, Ui.dp(ctx, 4), pad, 0);

        TextView stats = new TextView(ctx);
        stats.setTextColor(Ui.secondaryText(ctx));
        stats.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        body.addView(stats);

        body.addView(Ui.label(ctx, "Nextcloud 주소"));
        EditText url = Ui.field(ctx, NextcloudSync.url(), "https://nextcloud.example.com",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        body.addView(url);
        body.addView(Ui.label(ctx, "사용자 이름"));
        EditText user = Ui.field(ctx, NextcloudSync.user(), "", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        body.addView(user);
        body.addView(Ui.label(ctx, "앱 비밀번호 (Nextcloud 설정 → 보안에서 만들기)"));
        EditText pass = Ui.field(ctx, NextcloudSync.password(), "", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        body.addView(pass);
        body.addView(Ui.label(ctx, "폴더"));
        EditText dir = Ui.field(ctx, NextcloudSync.folder(), NextcloudSync.DEFAULT_DIR, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        body.addView(dir);

        TextView log = new TextView(ctx);
        log.setTextColor(Ui.secondaryText(ctx));
        log.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        log.setPadding(0, Ui.dp(ctx, 10), 0, 0);
        body.addView(log);

        ScrollView scroll = new ScrollView(ctx);
        scroll.addView(body);

        Runnable save = () -> NextcloudSync.saveSettings(url.getText().toString(), user.getText().toString(),
                pass.getText().toString(), dir.getText().toString());
        Runnable showStats = () -> NextcloudSync.EXECUTOR.execute(() -> {
            int total = 0, tagged = 0, edited = 0;
            for (SearchIndex.Item it : SearchIndex.items()) {
                total++;
                if (it.edited) edited++;
                else if (it.tagged) tagged++;
            }
            String text = String.format("디시콘 %,d개 · AI 태그 %,d · 직접 수정 %,d · 팩 %,d개",
                    total, tagged, edited, Catalog.packs().size());
            NextcloudSync.MAIN.post(() -> stats.setText(text));
        });
        showStats.run();

        AlertDialog dialog = new AlertDialog.Builder(ctx)
                .setTitle("DCCon Tagger 설정")
                .setView(scroll)
                .setNegativeButton("닫기", null)
                .setPositiveButton("저장", (d, w) -> {
                    save.run();
                    if (NextcloudSync.enabled()) NextcloudSync.pullAsync(true, onSynced);
                })
                .setNeutralButton("연결 확인 + 지금 동기화", null)
                .create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
            save.run();
            if (!NextcloudSync.enabled()) {
                log.setText("Nextcloud 주소, 사용자 이름, 앱 비밀번호를 모두 입력하세요.");
                return;
            }
            log.setText("동기화 중…");
            NextcloudSync.syncNowAsync((changed, message) -> {
                log.setText(message == null ? "동기화 완료" : "오류: " + message);
                showStats.run();
                onSynced.onSyncDone(changed, message);
            });
        }));
        dialog.show();
    }
}
