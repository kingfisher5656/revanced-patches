package app.revanced.patches.dcinside.layout.dccon

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patcher.util.getReference
import app.morphe.patcher.util.proxy.mutableTypes.MutableClass
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.util.ResourceGroup
import app.morphe.util.asSequence
import app.morphe.util.copyResources
import app.morphe.util.setExtensionIsPatchIncluded
import app.revanced.patches.dcinside.misc.extension.addExtensionPatch
import app.revanced.patches.dcinside.shared.Constants.COMPATIBILITY_DC_INSIDE
import app.revanced.util.smaliReference
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import org.w3c.dom.Document
import org.w3c.dom.Element

private const val EXTENSION_CLASS =
    "Lapp/revanced/extension/dcinside/patches/DcconSearchPatch;"
private const val SEARCH_BUTTON_ID = "morphe_dccon_search_button"
private const val SEARCH_ICON = "morphe_ic_dccon_search"
private const val LOAD_IMAGE_HELPER = "morpheLoadDcconImage"

private val dcconSearchResourcesPatch = resourcePatch {
    compatibleWith(COMPATIBILITY_DC_INSIDE)

    execute {
        copyResources(
            "dcinside/dccon-search",
            ResourceGroup("drawable", "$SEARCH_ICON.xml"),
        )

        document("res/values/ids.xml").use { document ->
            val exists = document.getElementsByTagName("item")
                .asSequence()
                .any { it.attributes?.getNamedItem("name")?.nodeValue == SEARCH_BUTTON_ID }

            if (!exists) {
                document.documentElement.appendChild(
                    document.createElement("item").apply {
                        setAttribute("type", "id")
                        setAttribute("name", SEARCH_BUTTON_ID)
                    }
                )
            }
        }

        document("res/layout/view_dccon_picker.xml").use { document ->
            document.addSearchButton()
        }
    }
}

/**
 * Puts a search button at the start of the picker's bottom bar, before the pack tabs.
 */
private fun Document.addSearchButton() {
    val elements = getElementsByTagName("*").asSequence().mapNotNull { it as? Element }.toList()
    if (elements.any { it.getAttribute("android:id").endsWith("/$SEARCH_BUTTON_ID") }) return

    val tabs = elements.firstOrNull { it.getAttribute("android:id").endsWith("/dccon_picker_new_tab") }
        ?: throw PatchException("Could not find dccon_picker_new_tab in view_dccon_picker.xml")

    val button = createElement("ImageView").apply {
        setAttribute("android:id", "@id/$SEARCH_BUTTON_ID")
        setAttribute("android:layout_width", "40.0dp")
        setAttribute("android:layout_height", "0.0dp")
        setAttribute("android:padding", "10.0dp")
        setAttribute("android:src", "@drawable/$SEARCH_ICON")
        setAttribute("android:scaleType", "fitCenter")
        setAttribute("android:contentDescription", "디시콘 검색")
        setAttribute("app:layout_constraintTop_toTopOf", "parent")
        setAttribute("app:layout_constraintBottom_toBottomOf", "parent")
        setAttribute("app:layout_constraintStart_toStartOf", "parent")
        setAttribute("app:tint", "?colorAccent")
    }
    tabs.parentNode.insertBefore(button, tabs)

    tabs.removeAttribute("app:layout_constraintStart_toStartOf")
    tabs.setAttribute("app:layout_constraintStart_toEndOf", "@id/$SEARCH_BUTTON_ID")
}

@Suppress("unused")
val dcconSearchPatch = bytecodePatch(
    name = "DCCon search",
    description = "Adds a search button to the DCCon picker. DCCons are found by AI tags " +
        "(text, emotion, situation) synced from the DCCon Tagger Nextcloud folder, and tags can be edited by long-pressing a result.",
) {
    compatibleWith(COMPATIBILITY_DC_INSIDE)
    dependsOn(addExtensionPatch, dcconSearchResourcesPatch)

    execute {
        setExtensionIsPatchIncluded(EXTENSION_CLASS)
        val extension = mutableClassDefBy(EXTENSION_CLASS)

        // region Bridges to the obfuscated DCCon model classes.

        val iconType = DcconIconToStringFingerprint.method.definingClass
        val (iconDetail, iconPackage, iconTitle, iconImage) = DcconIconToStringFingerprint.method
            .toStringFields(listOf("J", "J", "Ljava/lang/String;", "Ljava/lang/String;"))

        val packageType = DcconPackageToStringFingerprint.method.definingClass
        val (packageId, packageTitle, packageImage) = DcconPackageToStringFingerprint.method
            .toStringFields(listOf("J", "Ljava/lang/String;", "Ljava/lang/String;"))

        val coreDataType = CoreDcconDataConstructorFingerprint.method.definingClass
        val (packageListField, iconListField) = CoreDcconDataConstructorFingerprint.method.listFields()

        // The extension reads these fields directly, and ART refuses access to private fields from another class.
        listOf(iconDetail, iconPackage, iconTitle, iconImage, packageId, packageTitle, packageImage, packageListField, iconListField)
            .forEach { makePublic(it) }

        extension.replaceBody("packageList", 2, getObject(coreDataType, packageListField))
        extension.replaceBody("iconList", 2, getObject(coreDataType, iconListField))
        extension.replaceBody("packageId", 3, getWide(packageType, packageId))
        extension.replaceBody("packageTitle", 2, getObject(packageType, packageTitle))
        extension.replaceBody("packageImage", 2, getObject(packageType, packageImage))
        extension.replaceBody("iconDetail", 3, getWide(iconType, iconDetail))
        extension.replaceBody("iconPackage", 3, getWide(iconType, iconPackage))
        extension.replaceBody("iconTitle", 2, getObject(iconType, iconTitle))
        extension.replaceBody("iconImage", 2, getObject(iconType, iconImage))

        // endregion

        // region Image loading: expose the picker's private tab icon loader.

        val pickerClass = DcconPickerLoadImageFingerprint.classDef
        val loadImage = DcconPickerLoadImageFingerprint.method
        pickerClass.methods.add(
            ImmutableMethod(
                pickerClass.type,
                LOAD_IMAGE_HELPER,
                listOf(
                    ImmutableMethodParameter(pickerClass.type, null, null),
                    ImmutableMethodParameter("Landroid/widget/ImageView;", null, null),
                    ImmutableMethodParameter("Ljava/lang/String;", null, null),
                ),
                "V",
                AccessFlags.PUBLIC.value or AccessFlags.STATIC.value,
                null,
                null,
                MutableMethodImplementation(3),
            ).toMutable().apply {
                addInstructions(
                    0,
                    """
                        invoke-direct {p0, p1, p2}, ${loadImage.smaliReference}
                        return-void
                    """.trimIndent(),
                )
            }
        )

        extension.replaceBody(
            "loadImage",
            3,
            """
                check-cast p0, ${pickerClass.type}
                invoke-static {p0, p1, p2}, ${pickerClass.type}->$LOAD_IMAGE_HELPER(${pickerClass.type}Landroid/widget/ImageView;Ljava/lang/String;)V
                return-void
            """.trimIndent(),
        )

        // endregion

        // region Selecting a DCCon: post the same event as a tap in the picker grid.

        val gridLongPressReferences = DcconGridLongPressFingerprint.method.instructions
            .mapNotNull { it.getReference<MethodReference>() }
        val pickEventConstructor = gridLongPressReferences.first { it.isPickEventConstructor }
        val postEvent = gridLongPressReferences.first { it.isEventBusPost }

        if (pickEventConstructor.parameterTypes[0].toString() != iconType) {
            throw PatchException("DCCon pick event does not take $iconType")
        }

        extension.replaceBody(
            "selectIcon",
            4,
            """
                check-cast p1, $iconType
                new-instance v0, ${pickEventConstructor.definingClass}
                const/4 v1, 0x0
                invoke-direct {v0, p1, v1}, ${pickEventConstructor.smaliReference}
                invoke-virtual {p0}, Landroid/view/View;->getContext()Landroid/content/Context;
                move-result-object v1
                invoke-static {v1, v0}, ${postEvent.smaliReference}
                return-void
            """.trimIndent(),
        )

        // endregion

        // region Hooks.

        DcconPickerConstructorFingerprint.method.apply {
            val inflateIndex = instructions.indexOfFirst {
                val reference = it.getReference<MethodReference>()
                reference?.definingClass == "Landroid/view/View;" && reference.name == "inflate"
            }
            if (inflateIndex < 0) throw PatchException("Could not find picker layout inflation")
            val insertIndex = inflateIndex + if (instructions[inflateIndex + 1].opcode == Opcode.MOVE_RESULT_OBJECT) 2 else 1

            addInstruction(
                insertIndex,
                "invoke-static {p0}, $EXTENSION_CLASS->onPickerCreated(Landroid/view/View;)V",
            )
        }

        DcconPagerAdapterSetDataFingerprint.method.addInstruction(
            0,
            "invoke-static {p1}, $EXTENSION_CLASS->onDcconData(Ljava/lang/Object;)V",
        )

        DcconPickerSelectFingerprint.method.apply {
            if (parameterTypes.first().toString() != iconType) {
                throw PatchException("Unexpected DCCon picker select method ${smaliReference}")
            }
            addInstruction(
                0,
                "invoke-static {p1, p2}, $EXTENSION_CLASS->onIconSelected(Ljava/lang/Object;Z)V",
            )
        }

        // endregion
    }
}

private fun BytecodePatchContext.makePublic(reference: FieldReference) {
    val field = mutableClassDefBy(reference.definingClass).fields.singleOrNull { it.name == reference.name }
        ?: throw PatchException("Could not find field ${reference.smaliReference}")
    field.accessFlags = field.accessFlags and
        (AccessFlags.PRIVATE.value or AccessFlags.PROTECTED.value).inv() or
        AccessFlags.PUBLIC.value
}

private fun getObject(type: String, field: FieldReference) =
    """
        check-cast p0, $type
        iget-object v0, p0, ${field.smaliReference}
        return-object v0
    """.trimIndent()

private fun getWide(type: String, field: FieldReference) =
    """
        check-cast p0, $type
        iget-wide v0, p0, ${field.smaliReference}
        return-wide v0
    """.trimIndent()

/**
 * Replaces an extension stub method with new code, with room for [registers] registers (including parameters).
 */
private fun MutableClass.replaceBody(name: String, registers: Int, smali: String) {
    val stub = methods.singleOrNull { it.name == name }
        ?: throw PatchException("Could not find $name in $type")
    methods.remove(stub)
    methods.add(
        ImmutableMethod(
            stub.definingClass,
            stub.name,
            stub.parameterTypes.map { ImmutableMethodParameter(it.toString(), null, null) },
            stub.returnType,
            stub.accessFlags,
            null,
            null,
            MutableMethodImplementation(registers),
        ).toMutable().apply {
            addInstructions(0, smali)
        }
    )
}

/**
 * Fields printed by a Kotlin data class toString, in declaration order.
 * The compiled method reads every field before building the string.
 */
private fun Method.toStringFields(expectedTypes: List<String>): List<FieldReference> {
    val fields = implementation!!.instructions
        .filter { it.opcode == Opcode.IGET || it.opcode == Opcode.IGET_WIDE || it.opcode == Opcode.IGET_OBJECT }
        .mapNotNull { it.getReference<FieldReference>() }
        .filter { it.definingClass == definingClass }

    if (fields.size < expectedTypes.size || fields.take(expectedTypes.size).map { it.type } != expectedTypes) {
        throw PatchException("Unexpected field layout in $definingClass: ${fields.map { it.type }}")
    }
    return fields.take(expectedTypes.size)
}

/**
 * The two List fields a constructor stores, in the order they are assigned.
 */
private fun Method.listFields(): List<FieldReference> {
    val fields = implementation!!.instructions
        .filter { it.opcode == Opcode.IPUT_OBJECT }
        .mapNotNull { it.getReference<FieldReference>() }
        .filter { it.definingClass == definingClass && it.type == "Ljava/util/List;" }

    if (fields.size != 2) throw PatchException("Unexpected list fields in $definingClass: $fields")
    return fields
}
