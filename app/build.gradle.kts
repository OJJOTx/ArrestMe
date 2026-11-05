plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.arrestme.benchmark"
    compileSdk = 35

    val arrestMeApiBaseUrl: String = (
        providers.gradleProperty("ARRESTME_API_BASE_URL").orNull
            ?: System.getenv("ORG_GRADLE_PROJECT_ARRESTME_API_BASE_URL")
            ?: ""
    ).trim()

    defaultConfig {
        applicationId = "com.arrestme.benchmark"
        minSdk = 26
        targetSdk = 35
        versionCode = 6
        versionName = "0.6.0"

        buildConfigField("String", "API_BASE_URL", "\"${arrestMeApiBaseUrl.replace("\"", "\\\"")}\"")

        vectorDrawables {
            useSupportLibrary = true
        }
    }

    val keystorePath = providers.gradleProperty("KEYSTORE_FILE").orNull
        ?: System.getenv("KEYSTORE_FILE")
    val keystoreFile = keystorePath?.let { file(it) }
    val keystorePassword = providers.gradleProperty("KEYSTORE_PASSWORD").orNull
        ?: System.getenv("KEYSTORE_PASSWORD")
    val keyAliasProp = providers.gradleProperty("KEY_ALIAS").orNull
        ?: System.getenv("KEY_ALIAS")
    val keyPasswordProp = providers.gradleProperty("KEY_PASSWORD").orNull
        ?: System.getenv("KEY_PASSWORD")

    val hasSigningConfig = keystoreFile != null && keystoreFile.exists() &&
        !keystorePassword.isNullOrBlank() &&
        !keyAliasProp.isNullOrBlank() &&
        !keyPasswordProp.isNullOrBlank()

    signingConfigs {
        if (hasSigningConfig) {
            create("release") {
                storeFile = keystoreFile
                storePassword = keystorePassword
                keyAlias = keyAliasProp
                keyPassword = keyPasswordProp
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ""
            versionNameSuffix = "-debug"
            isDebuggable = true
            if (hasSigningConfig) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (hasSigningConfig) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.core)

    implementation(libs.androidx.navigation.compose)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.retrofit)
    implementation(libs.retrofit.kotlinx.serialization)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
}
