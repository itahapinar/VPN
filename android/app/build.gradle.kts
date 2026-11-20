plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Telefonun kendi istemci sertifikası (masaüstü my.ovpn'i kullanır). Aynı sertifikayla
// iki cihaz aynı anda bağlanırsa sunucu birini düşürür; bu yüzden ayrı dosya.
val ovpnName = providers.gradleProperty("ovpnDosyasi").getOrElse("mobile.ovpn")

android {
    namespace = "com.taha.vpn"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.taha.vpn"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"
        buildConfigField("String", "OVPN_NAME", "\"$ovpnName\"")
        // vpnmotor ile aynı mimariler; aksi halde OpenVPN'siz bir lib/x86 klasörü oluşur
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
            // Kişisel kullanım: ayrı imza anahtarı yoksa debug anahtarıyla imzala
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        jniLibs {
            // libovpnexec.so çalıştırılabilir dosya olarak diske açılmalı
            useLegacyPackaging = true
        }
    }
}

// kaynak/<ovpnName> -> assets/vpn.ovpn
abstract class CopyOvpnTask : DefaultTask() {
    @get:Internal
    abstract val source: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:InputFiles
    val sourceFiles get() = listOf(source.get().asFile).filter { it.exists() }

    @TaskAction
    fun copy() {
        val file = source.get().asFile
        if (!file.exists()) {
            throw GradleException(
                "${file.path} bulunamadı. Sunucuda openvpn-install.sh ile 'mobile' adlı yeni bir " +
                    "istemci oluşturup dosyayı kaynak klasörüne koyun."
            )
        }
        val out = outputDir.get().asFile
        out.mkdirs()
        file.copyTo(out.resolve("vpn.ovpn"), overwrite = true)
    }
}

val copyOvpn = tasks.register<CopyOvpnTask>("copyOvpnConfig") {
    source.set(rootProject.layout.projectDirectory.file("../kaynak/$ovpnName"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(copyOvpn, CopyOvpnTask::outputDir)
    }
}

dependencies {
    implementation(project(":vpnmotor"))

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
