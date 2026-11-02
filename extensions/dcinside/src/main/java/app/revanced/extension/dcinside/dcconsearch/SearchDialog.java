package app.revanced.extension.dcinside.dcconsearch;

import android.app.Dialog;
import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import app.revanced.extension.dcinside.patches.DcconSearchPatch;

/**
 * Bottom sheet with a search box and a results grid, opened from the 🔍 button in the DCCon picker.
 * Tap a result to select it like the normal grid; long-press to edit its tags.
 */
public final class SearchDialog {
    private static String lastQuery = "";
    private static final ExecutorService SEARCH_EXECUTOR = Executors.newSingleThreadExecutor();

    private final View picker;
    private final Context ctx;
    private final Dialog dialog;
    private final EditText input;
    private final LinearLayout chips;
    private final HorizontalScrollView chipScroll;
    private final TextView label;
    private final TextView empty;
    private final TextView status;
    private final ResultAdapter adapter = new ResultAdapter();
    private final Runnable runQuery = this::run;
    private volatile int searchSeq;
    private String statusOverride;

    public static void show(View picker) {
        Context ctx = picker.getContext();
        TagStore.init(ctx);
        NextcloudSync.init(ctx);
        new SearchDialog(picker).open();
    }

    private SearchDialog(View picker) {
        this.picker = picker;
        ctx = picker.getContext();
        dialog = new Dialog(ctx);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        int pad = Ui.dp(ctx, 10);
        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, Ui.dp(ctx, 4));

        // Search bar
        LinearLayout bar = new LinearLayout(ctx);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        input = new EditText(ctx);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        input.setHint("글자·감정·태그 검색 (초성 OK)");
        input.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        input.setTextColor(Ui.text(ctx));
        input.setHintTextColor(Ui.secondaryText(ctx));
        input.setPadding(Ui.dp(ctx, 12), 0, Ui.dp(ctx, 12), 0);
        GradientDrawable box = Ui.rounded(Ui.background(ctx), Ui.dp(ctx, 8));
        box.setStroke(Ui.dp(ctx, 2), Ui.accent(ctx));
        input.setBackground(box);
        bar.addView(input, new LinearLayout.LayoutParams(0, Ui.dp(ctx, 40), 1));
        TextView close = button("✕");
        close.setContentDescription("검색 닫기");
        close.setOnClickListener(v -> dialog.dismiss());
        LinearLayout.LayoutParams closeParams = new LinearLayout.LayoutParams(Ui.dp(ctx, 40), Ui.dp(ctx, 40));
        closeParams.leftMargin = Ui.dp(ctx, 6);
        bar.addView(close, closeParams);
        root.addView(bar);

        // Recent searches
        chipScroll = new HorizontalScrollView(ctx);
        chipScroll.setHorizontalScrollBarEnabled(false);
        chips = new LinearLayout(ctx);
        chips.setPadding(0, Ui.dp(ctx, 6), 0, 0);
        chipScroll.addView(chips);
        root.addView(chipScroll);

        label = Ui.label(ctx, "");
        label.setPadding(Ui.dp(ctx, 2), Ui.dp(ctx, 6), 0, Ui.dp(ctx, 2));
        root.addView(label);

        // Results
        FrameLayout gridWrap = new FrameLayout(ctx);
        GridView grid = new GridView(ctx);
        grid.setNumColumns(GridView.AUTO_FIT);
        grid.setColumnWidth(Ui.dp(ctx, 72));
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        grid.setHorizontalSpacing(Ui.dp(ctx, 4));
        grid.setVerticalSpacing(Ui.dp(ctx, 4));
        grid.setAdapter(adapter);
        grid.setOnItemClickListener((parent, view, position, id) -> send(adapter.getItem(position)));
        grid.setOnItemLongClickListener((parent, view, position, id) -> {
            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            editTags(adapter.getItem(position));
            return true;
        });
        empty = new TextView(ctx);
        empty.setGravity(Gravity.CENTER);
        empty.setTextColor(Ui.secondaryText(ctx));
        empty.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        empty.setLineSpacing(0, 1.4f);
        empty.setPadding(pad, pad, pad, pad);
        gridWrap.addView(grid, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        gridWrap.addView(empty, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        grid.setEmptyView(empty);
        root.addView(gridWrap, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        // Footer
        LinearLayout foot = new LinearLayout(ctx);
        foot.setGravity(Gravity.CENTER_VERTICAL);
        foot.setPadding(0, Ui.dp(ctx, 4), 0, 0);
        status = new TextView(ctx);
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        status.setTextColor(Ui.secondaryText(ctx));
        status.setSingleLine(true);
        status.setEllipsize(TextUtils.TruncateAt.END);
        foot.addView(status, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView gear = button("⚙");
        gear.setContentDescription("설정");
        gear.setOnClickListener(v -> SyncSettingsDialog.show(ctx, this::onSynced));
        foot.addView(gear, new LinearLayout.LayoutParams(Ui.dp(ctx, 32), Ui.dp(ctx, 32)));
        root.addView(foot);

        dialog.setContentView(root);

        input.setText(lastQuery);
        input.setSelection(lastQuery.length());
        input.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                lastQuery = s.toString();
                input.removeCallbacks(runQuery);
                input.postDelayed(runQuery, 120);
            }
        });
        input.setOnEditorActionListener((v, actionId, event) -> {
            boolean enter = event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER;
            if (actionId == EditorInfo.IME_ACTION_SEARCH || enter) {
                TagStore.recordQuery(input.getText().toString());
                return true;
            }
            return false;
        });
    }

    private void open() {
        dialog.show();
        Window w = dialog.getWindow();
        if (w != null) {
            float r = Ui.dp(ctx, 14);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(Ui.background(ctx));
            bg.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
            w.setBackgroundDrawable(bg);
            int height = Math.round(ctx.getResources().getDisplayMetrics().heightPixels * 0.62f);
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, height);
            w.setGravity(Gravity.BOTTOM);
            w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                    | WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
        }
        input.requestFocus();
        run();
        NextcloudSync.autoPull(this::onSynced);
    }

    private void onSynced(boolean changed, String message) {
        statusOverride = message == null ? null : "동기화 실패: " + message;
        if (changed) run();
        else updateStatus();
    }

    /**
     * Runs the query on the search thread, which is separate from sync so a download never delays results.
     * Queries that a newer keystroke replaced before they started are skipped; only the newest result is shown.
     */
    private void run() {
        final int seq = ++searchSeq;
        final String q = input.getText().toString();
        SEARCH_EXECUTOR.execute(() -> {
            if (seq != searchSeq) return;
            List<SearchIndex.Item> items = q.trim().isEmpty() ? SearchIndex.recent() : SearchIndex.search(q);
            List<SearchIndex.Item> all = SearchIndex.items();
            int tagged = 0;
            for (SearchIndex.Item it : all) if (it.tagged) tagged++;
            final int total = all.size(), taggedCount = tagged;
            NextcloudSync.MAIN.post(() -> {
                if (seq != searchSeq || !dialog.isShowing()) return;
                show(q, items, total, taggedCount);
            });
        });
    }

    private void show(String q, List<SearchIndex.Item> items, int total, int tagged) {
        boolean searching = !q.trim().isEmpty();
        chips.removeAllViews();
        if (!searching) {
            for (String recent : TagStore.recentQueries()) {
                TextView chip = new TextView(ctx);
                chip.setText(recent);
                chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
                chip.setTextColor(Ui.accent(ctx));
                chip.setBackground(Ui.rounded(Ui.faint(ctx), Ui.dp(ctx, 12)));
                chip.setPadding(Ui.dp(ctx, 10), Ui.dp(ctx, 4), Ui.dp(ctx, 10), Ui.dp(ctx, 4));
                chip.setOnClickListener(v -> {
                    input.setText(recent);
                    input.setSelection(recent.length());
                });
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.rightMargin = Ui.dp(ctx, 6);
                chips.addView(chip, lp);
            }
        }
        chipScroll.setVisibility(chips.getChildCount() == 0 ? View.GONE : View.VISIBLE);

        label.setText(searching ? "검색 결과 " + items.size() + "개 · 길게 누르면 태그 편집"
                : items.isEmpty() ? "" : "최근 사용");
        label.setVisibility(label.getText().length() == 0 ? View.GONE : View.VISIBLE);
        empty.setText(searching ? "검색 결과가 없습니다."
                : total > 0 ? "이미지 속 글자, 감정(기쁨·슬픔…), 상황(감사·사과…)으로 검색하세요.\n초성 검색도 됩니다 (예: ㅇㄱㅇㅇ).\n결과를 길게 누르면 태그를 고칠 수 있어요."
                : "디시콘 목록을 불러오는 중입니다…\n디시콘 창을 한 번 열어 두면 검색할 수 있어요.");
        adapter.set(items);

        String sync = !NextcloudSync.enabled() ? "동기화 꺼짐"
                : TagStore.lastPull() > 0 ? "동기화 " + DateFormat.getTimeInstance(DateFormat.SHORT).format(new Date(TagStore.lastPull()))
                : "미동기화";
        status.setTag(String.format("AI 태그 %,d/%,d · %s", tagged, total, sync));
        updateStatus();
    }

    private void updateStatus() {
        Object normal = status.getTag();
        status.setText(statusOverride != null ? statusOverride : normal == null ? "" : normal.toString());
    }

    private void send(SearchIndex.Item it) {
        TagStore.recordQuery(input.getText().toString());
        dialog.dismiss();
        // The picker's selection hook records it as recently used.
        DcconSearchPatch.selectIcon(picker, it.dccon.icon);
    }

    private void editTags(SearchIndex.Item it) {
        TagEditorDialog.show(ctx, picker, it, new TagEditorDialog.Callback() {
            @Override
            public void onChanged() {
                run();
            }

            @Override
            public void onSend() {
                send(it);
            }
        });
    }

    private TextView button(String text) {
        TextView b = new TextView(ctx);
        b.setText(text);
        b.setGravity(Gravity.CENTER);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        b.setTextColor(Ui.text(ctx));
        b.setBackground(Ui.rounded(Ui.faint(ctx), Ui.dp(ctx, 8)));
        return b;
    }

    private final class ResultAdapter extends BaseAdapter {
        private List<SearchIndex.Item> items = new ArrayList<>();

        void set(List<SearchIndex.Item> newItems) {
            items = newItems;
            notifyDataSetChanged();
        }

        @Override
        public int getCount() {
            return items.size();
        }

        @Override
        public SearchIndex.Item getItem(int position) {
            return items.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            Cell cell = convertView instanceof Cell ? (Cell) convertView : new Cell(ctx);
            cell.bind(picker, getItem(position));
            return cell;
        }
    }

    /** Square grid cell: the DCCon, its pack icon as a badge, and a dot for untagged/edited. */
    private static final class Cell extends FrameLayout {
        private final ImageView image;
        private final ImageView badge;
        private final View dot;
        private String boundKey;

        Cell(Context ctx) {
            super(ctx);
            int p = Ui.dp(ctx, 2);
            setPadding(p, p, p, p);
            image = new ImageView(ctx);
            image.setScaleType(ImageView.ScaleType.FIT_CENTER);
            addView(image, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            badge = new ImageView(ctx);
            LayoutParams bp = new LayoutParams(Ui.dp(ctx, 18), Ui.dp(ctx, 18), Gravity.BOTTOM | Gravity.START);
            addView(badge, bp);
            dot = new View(ctx);
            LayoutParams dp = new LayoutParams(Ui.dp(ctx, 7), Ui.dp(ctx, 7), Gravity.TOP | Gravity.END);
            dp.topMargin = dp.rightMargin = Ui.dp(ctx, 3);
            addView(dot, dp);
        }

        void bind(View picker, SearchIndex.Item it) {
            setContentDescription(it.alt);
            int dotColor = it.edited ? Ui.NAVY : it.tagged ? 0 : Ui.ORANGE;
            dot.setVisibility(dotColor == 0 ? GONE : VISIBLE);
            if (dotColor != 0) dot.setBackground(Ui.rounded(dotColor, Ui.dp(getContext(), 4)));
            if (it.key.equals(boundKey)) return;
            boundKey = it.key;
            DcconSearchPatch.loadImage(picker, image, it.dccon.image);
            badge.setVisibility(it.packImage.isEmpty() ? GONE : VISIBLE);
            if (!it.packImage.isEmpty()) DcconSearchPatch.loadImage(picker, badge, it.packImage);
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            super.onMeasure(widthMeasureSpec, widthMeasureSpec);
        }
    }
}
