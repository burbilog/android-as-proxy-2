// Version overrides are intentionally non-secret; signing is local and separate.
val aap2VersionCodeOverride = System.getenv("AAP2_VERSION_CODE")
val aap2VersionCode = aap2VersionCodeOverride?.toIntOrNull() ?: 20000
require(aap2VersionCodeOverride == null ||
    (aap2VersionCodeOverride.toIntOrNull() != null && aap2VersionCode > 0)) {
    "AAP2_VERSION_CODE must be a positive integer"
}
val aap2VersionName = System.getenv("AAP2_VERSION_NAME") ?: "2.0.0-dev"
require(aap2VersionName.isNotBlank()) { "AAP2_VERSION_NAME must not be blank" }

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "net.isaeff.android.asproxy"
    compileSdk = 35

    defaultConfig {
        applicationId = "net.isaeff.android.asproxy2"
        minSdk = 24
        targetSdk = 35
        versionCode = aap2VersionCode
        versionName = aap2VersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        compose = true
    }
    // Resolve duplicate NOTICE.md from JavaMail jars
    packaging {
        resources {
            pickFirsts += setOf(
                "META-INF/NOTICE.md",
                "META-INF/LICENSE.md"
            )
        }
    }
}

dependencies {
 
 
 
 
     implementation(libs.jsch)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}
