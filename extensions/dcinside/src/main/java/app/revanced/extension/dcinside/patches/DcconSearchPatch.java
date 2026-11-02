package app.revanced.extension.dcinside.patches;

import android.util.Log;
import android.view.View;
import android.widget.ImageView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import app.revanced.extension.dcinside.dcconsearch.Catalog;
import app.revanced.extension.dcinside.dcconsearch.NextcloudSync;
import app.revanced.extension.dcinside.dcconsearch.SearchDialog;
import app.revanced.extension.dcinside.dcconsearch.TagStore;
import app.revanced.extension.dcinside.helper.ResourceHelper;

/**
 * DCCon search with AI tags (the app side of the DCCon Tagger userscript).
 * <p>
 * The patch calls {@link #onPickerCreated} from the DCCon picker constructor,
 * {@link #onDcconData} whenever the picker receives the owned DCCon list and
 * {@link #onIconSelected} when a DCCon is tapped.
 * The bridge methods at the bottom are rewritten during patching to read the app's obfuscated classes.
 */
@SuppressWarnings("unused")
public final class DcconSearchPatch {
    static final String TAG = "DcconSearch";

    private DcconSearchPatch() {
    }

    /**
     * @return If this patch was included during patching.
     */
    public static boolean isPatchIncluded() {
        return false;  // Modified during patching.
    }

    /**
     * Injection point: end of the DCCon picker view constructor.
     */
    public static void onPickerCreated(View picker) {
        try {
            TagStore.init(picker.getContext());
            NextcloudSync.init(picker.getContext());
            int id = ResourceHelper.getResourceId("id", "morphe_dccon_search_button");
            View button = id == 0 ? null : picker.findViewById(id);
            if (button != null) {
                button.setOnClickListener(v -> SearchDialog.show(picker));
            }
        } catch (Throwable ex) {
            Log.e(TAG, "onPickerCreated failure", ex);
        }
    }

    /**
     * Injection point: the picker pager adapter receives the owned packages and their DCCons.
     */
    public static void onDcconData(Object coreData) {
        try {
            List<Catalog.Pack> packs = new ArrayList<>();
            for (Object pack : nonNull(packageList(coreData))) {
                packs.add(new Catalog.Pack(packageId(pack), packageTitle(pack), packageImage(pack)));
            }
            List<Catalog.Dccon> dccons = new ArrayList<>();
            for (Object icons : nonNull(iconList(coreData))) {
                if (!(icons instanceof List)) continue;
                for (Object icon : (List<?>) icons) {
                    dccons.add(new Catalog.Dccon(icon, iconPackage(icon), iconDetail(icon), iconTitle(icon), iconImage(icon)));
                }
            }
            Catalog.set(packs, dccons);
            NextcloudSync.exportCatalogAsync();
        } catch (Throwable ex) {
            Log.e(TAG, "onDcconData failure", ex);
        }
    }

    /**
     * Injection point: the picker handles a DCCon tap (from its grid or from search).
     * Records it for the "recently used" list shown with an empty search box.
     */
    public static void onIconSelected(Object icon, boolean longPress) {
        try {
            if (!longPress && icon != null) TagStore.recordRecent(iconPackage(icon) + ":" + iconDetail(icon));
        } catch (Throwable ex) {
            Log.e(TAG, "onIconSelected failure", ex);
        }
    }

    private static List<?> nonNull(List<?> list) {
        return list == null ? Collections.emptyList() : list;
    }

    // region Bridges. Bodies are replaced during patching.

    public static List<?> packageList(Object coreData) {
        return null;
    }

    public static List<?> iconList(Object coreData) {
        return null;
    }

    public static long packageId(Object pack) {
        return 0;
    }

    public static String packageTitle(Object pack) {
        return null;
    }

    public static String packageImage(Object pack) {
        return null;
    }

    public static long iconPackage(Object icon) {
        return 0;
    }

    public static long iconDetail(Object icon) {
        return 0;
    }

    public static String iconTitle(Object icon) {
        return null;
    }

    public static String iconImage(Object icon) {
        return null;
    }

    /**
     * Loads an image the same way the picker loads its tab icons.
     */
    public static void loadImage(View picker, ImageView view, String url) {
    }

    /**
     * Selects a DCCon as if it was tapped in the picker grid.
     */
    public static void selectIcon(View picker, Object icon) {
    }

    // endregion
}
