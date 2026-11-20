// ics-openvpn'in "skeleton" (arayüzsüz) varyantını kütüphane olarak derler.
// Upstream kaynaklarına dokunulmaz; yalnızca buradan referans verilir.
import javax.inject.Inject

plugins {
    alias(libs.plugins.android.library)
}

val upstream = rootProject.file("ics-openvpn/main/src")

android {
    namespace = "de.blinkt.openvpn"
    compileSdk = 37
    // ics-openvpn v0.7.68'in kullandığı NDK; kurulu değilse Gradle indirir
    ndkVersion = "30.0.14904198"

    defaultConfig {
        minSdk = 24

        // Upstream kodu bu alanları ürün çeşitlerinden (flavor) bekliyor
        buildConfigField("String", "FLAVOR", "\"skeleton\"")
        buildConfigField("boolean", "openvpn3", "false")

        externalNativeBuild {
            cmake {
                // OpenVPN3 (SWIG gerektirir) derlenmez; yalnızca klasik OpenVPN 2 çekirdeği
                arguments += "-DCMAKE_MODULE_PATH=${file("cmake").invariantSeparatorsPath}"
                targets += listOf("ovpnutil", "osslutil", "openvpn", "libovpnexec.so")
            }
        }
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    buildFeatures {
        aidl = true
        buildConfig = true
    }

    externalNativeBuild {
        cmake {
            path = File(upstream, "main/cpp/CMakeLists.txt")
        }
    }

    sourceSets {
        getByName("main") {
            manifest.srcFile("src/main/AndroidManifest.xml")
            // Java/Kotlin kaynakları doğrudan değil, yamalı kopyadan gelir (aşağıda)
            aidl.directories += File(upstream, "main/aidl").path
            res.directories += listOf(
                File(upstream, "main/res").path,
                File(upstream, "skeleton/res").path,
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        abortOnError = false
    }
}

/**
 * Upstream Java/Kotlin kaynaklarını build/ altına kopyalar ve küçük yamaları uygular.
 * Upstream klasörü hiç değişmez. Bir yamanın aradığı metin bulunamazsa (ör. ics-openvpn
 * güncellendiyse) derleme hata verir; yama sessizce kaybolmaz.
 */
abstract class PatchUpstreamSources : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sourceDirs: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Inject
    abstract val fs: FileSystemOperations

    // dosya -> (aranan metin, yerine konacak metin)
    private val patches = mapOf(
        "de/blinkt/openvpn/core/OpenVPNService.java" to listOf(
            // Bildirim her durum değişikliğinde başka kanala/kimliğe geçip silinip yeniden
            // eklenmesin (duraklat/devam et'te kaybolup gelmesin): tek kanal, tek kimlik.
            "        int icon = getIconByConnectionStatus(status);" to
                "        if (!channel.equals(NOTIFICATION_CHANNEL_USERREQ_ID))\n" +
                "            channel = NOTIFICATION_CHANNEL_BG_ID;\n" +
                "        int icon = getIconByConnectionStatus(status);",
        ),
    )

    @TaskAction
    fun run() {
        val out = outputDir.get().asFile
        fs.sync {
            from(sourceDirs)
            into(out)
        }
        for ((path, edits) in patches) {
            val file = out.resolve(path)
            var text = file.readText()
            for ((old, new) in edits) {
                check(old in text) { "ics-openvpn yaması uygulanamadı ($path): '${old.trim()}' bulunamadı" }
                text = text.replace(old, new)
            }
            file.writeText(text)
        }
    }
}

val patchUpstream = tasks.register<PatchUpstreamSources>("patchUpstreamSources") {
    sourceDirs.from(File(upstream, "main/java"), File(upstream, "skeleton/java"))
    outputDir.set(layout.buildDirectory.dir("generated/upstream-src"))
}

androidComponents {
    onVariants { variant ->
        // Upstream java klasöründe Kotlin dosyası da var (AppCustomControl.kt)
        variant.sources.java?.addGeneratedSourceDirectory(patchUpstream, PatchUpstreamSources::outputDir)
        variant.sources.kotlin?.addGeneratedSourceDirectory(patchUpstream, PatchUpstreamSources::outputDir)
    }
}

dependencies {
    implementation(libs.androidx.annotation)
    implementation(libs.androidx.core.ktx)
}
