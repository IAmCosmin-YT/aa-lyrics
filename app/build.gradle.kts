plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "dev.lyricdrive"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.lyricdrive"
        minSdk = 29
        targetSdk = 35
        // CI stamps the run number so the installed build can be identified in Settings.
        val build = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionCode = build
        versionName = "1.0.$build"
    }

    // Android Auto reads which app types the APK supports from a static XML, so the two ways of
    // showing lyrics in the car are two variants of the same app (install one of them):
    //  - car:   Car App Library app (animated lyrics beside or instead of Maps). Android Auto only
    //           lists it when installed through Google Play (e.g. Internal App Sharing).
    //  - media: media app (lyrics in Android Auto's own player); works when sideloaded.
    flavorDimensions += "androidAuto"
    productFlavors {
        create("car") {
            dimension = "androidAuto"
            isDefault = true
            versionNameSuffix = "-car"
            manifestPlaceholders["carServiceEnabled"] = "true"
            manifestPlaceholders["mediaServiceEnabled"] = "false"
        }
        create("media") {
            dimension = "androidAuto"
            versionNameSuffix = "-media"
            manifestPlaceholders["carServiceEnabled"] = "false"
            manifestPlaceholders["mediaServiceEnabled"] = "true"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf(
            "-opt-in=kotlinx.coroutines.FlowPreview",
            "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi",
        )
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(project(":core"))

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.car.app)
    implementation(libs.androidx.car.app.projected)
    implementation(libs.androidx.media)
    implementation(libs.androidx.palette)
    implementation(libs.androidx.dynamicanimation)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
}
