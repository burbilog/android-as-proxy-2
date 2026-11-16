import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// The checked-in version is shared by GitHub and F-Droid builds.
// Environment overrides are for local throwaway test APKs only.
val releaseVersion = Properties().apply {
    rootProject.file("version.properties").inputStream().use { load(it) }
}
val publishedCode = releaseVersion.getProperty("versionCode").toInt()
val publishedName = releaseVersion.getProperty("versionName").trim()
require(publishedCode > 0 && publishedName.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+"))) {
    "Invalid version.properties"
}
val aap2VersionCodeOverride = System.getenv("AAP2_VERSION_CODE")
val aap2VersionCode = aap2VersionCodeOverride?.toIntOrNull() ?: publishedCode
require(aap2VersionCode > 0) { "AAP2_VERSION_CODE must be a positive integer" }
val aap2VersionName = System.getenv("AAP2_VERSION_NAME") ?: publishedName
require(aap2VersionName.isNotBlank()) { "AAP2_VERSION_NAME must not be blank" }

android {
    namespace = "net.isaeff.android.asproxy"
    compileSdk = 35

    flavorDimensions += "distribution"
    productFlavors {
        create("github") {
            dimension = "distribution"
            resValue("bool", "aap2_builtin_update", "true")
            resValue("string", "aap2_distribution", "GitHub")
        }
        create("fdroid") {
            dimension = "distribution"
            resValue("bool", "aap2_builtin_update", "false")
            resValue("string", "aap2_distribution", "F-Droid")
        }
    }

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
