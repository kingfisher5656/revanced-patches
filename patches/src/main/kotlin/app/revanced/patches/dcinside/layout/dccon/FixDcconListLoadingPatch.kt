package app.revanced.patches.dcinside.layout.dccon

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.getReference
import app.morphe.util.setExtensionIsPatchIncluded
import app.revanced.patches.dcinside.misc.extension.addExtensionPatch
import app.revanced.patches.dcinside.shared.Constants.COMPATIBILITY_DC_INSIDE
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val EXTENSION_CLASS =
    "Lapp/revanced/extension/dcinside/patches/DcconListLoadingPatch;"

// The app's OkHttp interceptor, which sets per-request headers and timeouts and then proceeds.
internal object ApiInterceptorFingerprint : Fingerprint(
    name = "intercept",
    accessFlags = listOf(AccessFlags.PUBLIC),
    parameters = listOf("Lokhttp3/Interceptor\$Chain;"),
    returnType = "Lokhttp3/Response;",
    strings = listOf("dcinside.app", "/voice/download"),
)

// DcconListUtil: the onErrorReturn of the owned DCCon list request, which returns CoreDcconData(emptyList(), emptyList()).
internal object DcconListFallbackFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PRIVATE, AccessFlags.STATIC, AccessFlags.FINAL),
    parameters = listOf("Ljava/lang/Throwable;"),
    returnType = "Lcom/dcinside/app/read/",
    custom = custom@{ method, _ ->
        method.implementation?.instructions?.any { instruction ->
            val reference = instruction.getReference<MethodReference>() ?: return@any false
            instruction.opcode == Opcode.INVOKE_DIRECT &&
                reference.name == "<init>" &&
                reference.definingClass == method.returnType &&
                reference.parameterTypes.map { it.toString() } == listOf("Ljava/util/List;", "Ljava/util/List;")
        } == true
    },
)

@Suppress("unused")
val fixDcconListLoadingPatch = bytecodePatch(
    name = "Fix DCCon list loading",
    description = "Fixes the DCCon picker showing \"사용 가능한 디시콘이 없습니다\" after a long load when many DCCon packs are owned. " +
        "The owned DCCon list is loaded page by page, and a failed load shows the real error.",
) {
    compatibleWith(COMPATIBILITY_DC_INSIDE)
    dependsOn(addExtensionPatch)

    execute {
        setExtensionIsPatchIncluded(EXTENSION_CLASS)

        ApiInterceptorFingerprint.method.apply {
            val proceedIndex = instructions.indexOfFirst {
                it.opcode == Opcode.INVOKE_INTERFACE && it.getReference<MethodReference>()?.let { reference ->
                    reference.definingClass == "Lokhttp3/Interceptor\$Chain;" && reference.name == "proceed"
                } == true
            }
            if (proceedIndex < 0) throw PatchException("Could not find proceed in the API interceptor")

            // chain.proceed(request) -> DcconListLoadingPatch.proceed(chain, request)
            val proceed = instructions[proceedIndex] as FiveRegisterInstruction
            replaceInstruction(
                proceedIndex,
                "invoke-static {v${proceed.registerC}, v${proceed.registerD}}, " +
                    "$EXTENSION_CLASS->proceed(Lokhttp3/Interceptor\$Chain;Lokhttp3/Request;)Lokhttp3/Response;",
            )
        }

        DcconListFallbackFingerprint.method.apply {
            if (returnType != CoreDcconDataConstructorFingerprint.method.definingClass) {
                throw PatchException("Unexpected DCCon list fallback $definingClass->$name returning $returnType")
            }
            addInstruction(
                0,
                "invoke-static {p0}, $EXTENSION_CLASS->onListFailed(Ljava/lang/Throwable;)V",
            )
        }
    }
}
