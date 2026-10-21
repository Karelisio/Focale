import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.room)
    alias(libs.plugins.roborazzi)
}

// Signature release : variables d'environnement FOCALE_* (CI) ou keystore.properties local.
// Sans configuration, l'APK release est signé avec la clé de debug pour rester installable.
val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use(::load)
}

fun signingValue(key: String): String? =
    System.getenv("FOCALE_$key")?.takeIf { it.isNotBlank() } ?: keystoreProperties.getProperty(key)

val hasReleaseSigning = signingValue("STORE_FILE") != null

// Version : lancement manuel de la CI (FOCALE_VERSION) ou tag « vX.Y.Z » d'une version
// GitHub, à défaut la version de développement. Le code de version en découle
// (1.2.3 → 1002003) et croît d'une version à l'autre, ce qu'exigent les mises à jour.
val appVersionName: String = (System.getenv("FOCALE_VERSION")?.takeIf { it.isNotBlank() } ?: System.getenv("GITHUB_REF_NAME"))
    ?.removePrefix("v")
    ?.takeIf { Regex("""\d+\.\d+\.\d+""").matches(it) }
    ?: "1.6.0"
val appVersionCode: Int = appVersionName.split('.').map(String::toInt)
    .let { (major, minor, patch) -> major * 1_000_000 + minor * 1_000 + patch }

// Journal des versions (CHANGELOG.md à la racine) embarqué dans les assets : l'application
// en affiche les nouveautés au premier lancement après une mise à jour.
abstract class ChangelogAssetTask : DefaultTask() {
    @get:InputFile
    abstract val changelog: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun copy() {
        val dir = outputDir.get().asFile
        dir.deleteRecursively()
        dir.mkdirs()
        changelog.get().asFile.copyTo(dir.resolve("CHANGELOG.md"))
    }
}

val changelogAssets = tasks.register<ChangelogAssetTask>("changelogAssets") {
    changelog.set(rootProject.layout.projectDirectory.file("CHANGELOG.md"))
}

// Dépôt GitHub interrogé par les mises à jour intégrées : celui de la CI, sinon l'original.
val updateRepository: String = System.getenv("GITHUB_REPOSITORY")?.takeIf { it.isNotBlank() } ?: "Karelisio/Focale"

android {
    namespace = "app.focale"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.focale"
        minSdk = 29
        targetSdk = 37
        versionCode = appVersionCode
        versionName = appVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(signingValue("STORE_FILE")!!)
                storePassword = signingValue("STORE_PASSWORD")
                keyAlias = signingValue("KEY_ALIAS")
                keyPassword = signingValue("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = if (hasReleaseSigning) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    // « standard » : fond de carte OpenStreetMap activable et mises à jour depuis GitHub.
    // « strict »   : aucune permission INTERNET, carte hors ligne, mises à jour manuelles.
    flavorDimensions += "network"
    productFlavors {
        create("standard") {
            dimension = "network"
            isDefault = true
            buildConfigField("boolean", "ONLINE_MAP_AVAILABLE", "true")
            buildConfigField("String", "UPDATE_REPOSITORY", "\"$updateRepository\"")
        }
        create("strict") {
            dimension = "network"
            applicationIdSuffix = ".strict"
            versionNameSuffix = "-strict"
            buildConfigField("boolean", "ONLINE_MAP_AVAILABLE", "false")
            buildConfigField("String", "UPDATE_REPOSITORY", "\"\"")
        }
    }

    // Un APK par architecture (ML Kit embarque ~10 Mo de code natif par ABI et par modèle) + un universel.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = true
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

    androidResources {
        // Génère locales_config.xml (FR/EN) pour le choix de langue par application (Android 13+).
        generateLocaleConfig = true
        // Modèles ONNX non compressés dans l'APK : projetés en mémoire au chargement.
        noCompress += "onnx"
    }

    packaging {
        resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "/META-INF/LICENSE*",
            "/META-INF/NOTICE*",
        )
        jniLibs {
            // Bibliothèques natives (ML Kit, ONNX Runtime) compressées dans l'APK : téléchargement
            // et mises à jour bien plus légers ; Android les extrait à l'installation.
            useLegacyPackaging = true
            // x86 32 bits : anciens émulateurs uniquement.
            excludes += "/lib/x86/**"
        }
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.isReturnDefaultValues = true
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = true
        warningsAsErrors = false
        disable += setOf("MissingTranslation", "ObsoleteLintCustomCheck", "GradleDependency", "NewerVersionAvailable", "AndroidGradlePluginVersion")
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        optIn.addAll(
            "androidx.compose.material3.ExperimentalMaterial3Api",
            "androidx.compose.material3.ExperimentalMaterial3ExpressiveApi",
            "androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi",
            "androidx.compose.foundation.ExperimentalFoundationApi",
            "androidx.compose.foundation.layout.ExperimentalLayoutApi",
            "androidx.compose.animation.ExperimentalSharedTransitionApi",
            "androidx.compose.animation.ExperimentalAnimationApi",
            "androidx.compose.ui.ExperimentalComposeUiApi",
            "kotlinx.coroutines.ExperimentalCoroutinesApi",
            "kotlinx.coroutines.FlowPreview",
            "me.saket.telephoto.ExperimentalTelephotoApi",
        )
    }
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(changelogAssets, ChangelogAssetTask::outputDir)
    }
}

room {
    schemaDirectory("$projectDir/schemas")
}

// Captures de référence des tests d'interface (Roborazzi), versionnées avec le code :
// recordRoborazziStandardDebug les régénère, la CI les compare (roborazzi.test.verify).
roborazzi {
    outputDir.set(file("src/test/screenshots"))
}

ksp {
    arg("room.generateKotlin", "true")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.navigation.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.foundation)
    implementation(libs.compose.animation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material3.navigation.suite)
    implementation(libs.compose.material3.window.size)
    implementation(libs.compose.material3.adaptive)
    implementation(libs.compose.material3.adaptive.layout)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.androidx.graphics.shapes)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.viewmodel.compose)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    implementation(libs.room.paging)
    ksp(libs.room.compiler)
    implementation(libs.paging.runtime)
    implementation(libs.paging.compose)
    implementation(libs.work.runtime)
    implementation(libs.datastore.preferences)

    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui.compose)
    implementation(libs.media3.common)
    implementation(libs.media3.datasource)
    implementation(libs.media3.transformer)

    implementation(libs.glance.appwidget)
    implementation(libs.glance.material3)
    implementation(libs.biometric)
    implementation(libs.exifinterface)
    implementation(libs.profileinstaller)

    implementation(libs.coil.compose)
    implementation(libs.coil.gif)
    implementation(libs.coil.video)
    implementation(libs.telephoto.zoomable.image.coil3)
    implementation(libs.telephoto.flick)
    implementation(libs.materialkolor)
    implementation(libs.osmdroid)
    implementation(libs.tink.android)
    implementation(libs.mlkit.image.labeling)
    implementation(libs.mlkit.text.recognition)
    implementation(libs.mlkit.face.detection)
    implementation(libs.onnxruntime.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
    testImplementation(libs.androidx.test.junit)
    testImplementation(libs.room.testing)
    testImplementation(libs.paging.testing)
    testImplementation(libs.onnxruntime.jvm)
}
