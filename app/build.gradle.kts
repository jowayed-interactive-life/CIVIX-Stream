import java.io.File

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.example.mobile_streaming"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.example.mobile_streaming"
        minSdk = 24
        targetSdk = 36
        versionCode = 5
        versionName = "1.0.5"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    flavorDimensions += "brand"
    productFlavors {
        create("civix") {
            dimension = "brand"
            applicationIdSuffix = ".civix"
            buildConfigField("String", "ORGANIZATION_ID", "\"65afbb77ecac8e05c82aff5d\"")
            buildConfigField("String", "INTEGRATED_ID", "\"65afbb77ecac8e05c82aff5d\"")
            buildConfigField("String", "API_BRAND", "\"civix\"")
            buildConfigField("String", "PROJECT_NAME", "\"civix\"")
        }
        create("cipher") {
            dimension = "brand"
            applicationIdSuffix = ".cipher"
            buildConfigField("String", "ORGANIZATION_ID", "\"698af675991550fcad337a3f\"")
            buildConfigField("String", "INTEGRATED_ID", "\"698af675991550fcad337a3f\"")
            buildConfigField("String", "API_BRAND", "\"cipher\"")
            buildConfigField("String", "PROJECT_NAME", "\"cipher\"")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

androidComponents {
    onVariants(selector().all()) { variant ->
        val label = when (variant.flavorName) {
            "civix" -> "civix-eye"
            "cipher" -> "cipher-eye"
            else -> "${variant.flavorName}-eye"
        }
        val firstOutput = variant.outputs.firstOrNull()
        val versionName = firstOutput?.versionName?.orNull ?: "0.0.0"
        val versionCode = firstOutput?.versionCode?.orNull ?: 1
        val variantName = variant.name.replaceFirstChar { char ->
            if (char.isLowerCase()) char.titlecase() else char.toString()
        }
        val renameTask = tasks.register("rename${variantName}ApkOutput") {
            doLast {
                val apkDir = layout.buildDirectory
                    .dir("outputs/apk/${variant.flavorName}/${variant.buildType}")
                    .get()
                    .asFile
                if (!apkDir.exists()) return@doLast
                val apkFiles = apkDir.listFiles()?.filter { it.isFile && it.extension == "apk" }.orEmpty()
                apkFiles.forEachIndexed { index, apk ->
                    val suffix = if (index == 0) "" else "-$index"
                    val targetName = "$label-v$versionName-build-$versionCode$suffix.apk"
                    if (apk.name != targetName) {
                        apk.renameTo(File(apkDir, targetName))
                    }
                }
            }
        }
        tasks.matching { it.name == "assemble$variantName" }.configureEach {
            finalizedBy(renameTask)
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.fragment:fragment-ktx:1.8.6")
    implementation("androidx.appcompat:appcompat:1.3.1")
    implementation("androidx.constraintlayout:constraintlayout:2.0.4")
    implementation("com.google.android.material:material:1.3.0")
    implementation("com.elvishew:xlog:1.11.0")
    implementation(libs.rootencoder.library)
    implementation("com.github.jiangdongguo.AndroidUSBCamera:libausbc:3.3.3") {
        isTransitive = false
    }
    implementation(files("libs/libuvc-3.2.9.aar"))
    implementation(files("libs/libnative-3.2.9.aar"))
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
