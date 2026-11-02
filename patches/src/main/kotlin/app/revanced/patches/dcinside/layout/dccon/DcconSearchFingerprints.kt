package app.revanced.patches.dcinside.layout.dccon

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.util.getReference
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

internal const val DCCON_PICKER_CLASS = "Lcom/dcinside/app/view/dccon/DcConPickerView;"

// Kotlin data class toString of the DCCon model: "DcConIcon(detailIndex=…, packageIndex=…, title=…, imageUrl=…, fav=…)"
internal object DcconIconToStringFingerprint : Fingerprint(
    name = "toString",
    parameters = listOf(),
    returnType = "Ljava/lang/String;",
    strings = listOf("DcConIcon(detailIndex="),
)

// "DcConPackage(packageIndex=…, title=…, imageUrl=…)"
internal object DcconPackageToStringFingerprint : Fingerprint(
    name = "toString",
    parameters = listOf(),
    returnType = "Ljava/lang/String;",
    strings = listOf("DcConPackage(packageIndex="),
)

// CoreDcconData(packageList: List<DcConPackage>, iconList: List<List<DcConIcon>>)
internal object CoreDcconDataConstructorFingerprint : Fingerprint(
    name = "<init>",
    parameters = listOf("Ljava/util/List;", "Ljava/util/List;"),
    returnType = "V",
    strings = listOf("packageList", "iconList"),
)

internal object DcconPickerConstructorFingerprint : Fingerprint(
    definingClass = DCCON_PICKER_CLASS,
    name = "<init>",
    parameters = listOf("Landroid/content/Context;", "Landroid/util/AttributeSet;", "I"),
    returnType = "V",
)

// DcconPagerAdapter.setData(dcconData: CoreDcconData, favoriteData: List)
internal object DcconPagerAdapterSetDataFingerprint : Fingerprint(
    definingClass = "Lcom/dcinside/app/view/dccon/",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    parameters = listOf("L", "Ljava/util/List;"),
    returnType = "V",
    strings = listOf("dcconData", "favoriteData"),
)

// Loads a tab icon into an ImageView with the app's image loader.
internal object DcconPickerLoadImageFingerprint : Fingerprint(
    definingClass = DCCON_PICKER_CLASS,
    accessFlags = listOf(AccessFlags.PRIVATE, AccessFlags.FINAL),
    parameters = listOf("Landroid/widget/ImageView;", "Ljava/lang/String;"),
    returnType = "V",
)

// Handles a picked DCCon (dccon, isLongPress).
internal object DcconPickerSelectFingerprint : Fingerprint(
    definingClass = DCCON_PICKER_CLASS,
    accessFlags = listOf(AccessFlags.PRIVATE, AccessFlags.FINAL),
    parameters = listOf("L", "Z"),
    returnType = "V",
)

// Grid item long-press: posts DcconPickEvent(dccon, true) on the context event bus.
internal object DcconGridLongPressFingerprint : Fingerprint(
    definingClass = "Lcom/dcinside/app/view/dccon/",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    parameters = listOf("Landroid/view/View;"),
    returnType = "V",
    custom = custom@{ method, _ ->
        val instructions = method.implementation?.instructions ?: return@custom false
        val references = instructions.mapNotNull { it.getReference<MethodReference>() }

        references.any { it.definingClass == "Landroid/view/View;" && it.name == "getTag" } &&
            references.any { it.isPickEventConstructor } &&
            instructions.any {
                it.opcode == Opcode.INVOKE_STATIC && it.getReference<MethodReference>()?.isEventBusPost == true
            }
    },
)

internal val MethodReference.isPickEventConstructor: Boolean
    get() = name == "<init>" &&
        parameterTypes.size == 2 &&
        parameterTypes[0].startsWith("L") &&
        parameterTypes[1].toString() == "Z"

internal val MethodReference.isEventBusPost: Boolean
    get() = returnType == "V" &&
        parameterTypes.map { it.toString() } == listOf("Landroid/content/Context;", "Ljava/lang/Object;")
