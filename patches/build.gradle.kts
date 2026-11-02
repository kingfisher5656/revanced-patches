group = "app.ample"

patches {
    about {
        name = "DCCon Tagger Patches"
        description = "Ample Patches for Morphe, plus DCCon search for DCInside"
        source = "git@github.com:AmpleReVanced/revanced-patches"
        author = "Ample"
        contact = "na"
        website = "na"
        license = "GNU General Public License v3.0"
    }
}

dependencies {
    // Used by JsonGenerator.
    implementation(libs.gson)
    implementation(kotlin("metadata-jvm"))

    // Required due to smali, or build fails. Can be removed once smali is bumped.
    implementation(libs.guava)

    implementation(libs.morphe.patches.library)

    implementation(libs.apksig)

    // ELF symbol lookup for native patches.
    implementation(libs.jelf)

    // Android API stubs defined here.
    compileOnly(project(":patches:stub"))

    // Local APK patching (patchApk task).
    testImplementation("app.morphe:morphe-patcher:${libs.versions.morphe.patcher.get()}")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.10.2")
}

tasks {
    register<JavaExec>("checkStringResources") {
        description = "Checks resource strings for invalid formatting"

        dependsOn(build)

        classpath = sourceSets["main"].runtimeClasspath
        mainClass.set("app.morphe.patches.util.resource.CheckStringResourcesKt")
    }

    // Patches an APK locally: ./gradlew :patches:patchApk --args="<apk> <patches.mpp> <output.apk> [keystore storePass alias keyPass]"
    register<JavaExec>("patchApk") {
        description = "Patches an APK on this machine with the default patch selection"

        classpath = sourceSets["test"].runtimeClasspath
        mainClass.set("app.morphe.tools.LocalPatcherKt")
        maxHeapSize = "4g"
    }

    register<JavaExec>("generatePatchesList") {
        description = "Build patch with patch list"

        dependsOn(build)

        classpath = sourceSets["main"].runtimeClasspath
        mainClass.set("app.morphe.util.PatchListGeneratorKt")
    }
    // Used by gradle-semantic-release-plugin.
    publish {
        dependsOn("generatePatchesList")
    }
}

publishing {
    repositories {
        maven {
            name = "githubPackages"
            url = uri("https://maven.pkg.github.com/amplerevanced/revanced-patches")
            credentials(PasswordCredentials::class)
        }
    }
}

//apply(from = "strings-processing.gradle.kts")
