plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
    id("com.google.gms.google-services")
}

// Public client config (publishable key is designed to ship inside apps; RLS protects data).
val supabaseUrl = "https://hktdjfixchzrltlwoxnb.supabase.co"
val supabaseKey = "sb_publishable_XkV2doCdnoAne_AZ-R3V1w_A1ZRGr7t"

val ciBuildNumber = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()

android {
    namespace = "com.neowarungposs.poskasir"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.neowarungposs.poskasir"
        minSdk = 26
        targetSdk = 35
        versionCode = ciBuildNumber
        versionName = "1.2.2"
        buildConfigField("String", "SUPABASE_URL", "\"$supabaseUrl\"")
        buildConfigField("String", "SUPABASE_KEY", "\"$supabaseKey\"")
    }

    flavorDimensions += "channel"
    productFlavors {
        create("tenant") {
            dimension = "channel"
            resValue("string", "app_name", "Kasir Warung")
            buildConfigField("boolean", "SHOW_PRICING", "false")
        }
        create("admin") {
            dimension = "channel"
            applicationIdSuffix = ".admin"
            resValue("string", "app_name", "Kasir Admin")
            buildConfigField("boolean", "SHOW_PRICING", "false")
        }
    }

    // POS code ships only in tenant; admin code only in admin (D3).
    sourceSets {
        getByName("tenant") {
            java.srcDir("src/pos/java")
            manifest.srcFile("src/pos/AndroidManifest.xml")
            res.srcDir("src/pos/res")
        }
    }

    // One stable key for every build so new APKs install over old ones (D30).
    // CI writes app/keystore.jks from the ANDROID_KEYSTORE_BASE64 secret; never commit it.
    val stableKeystore = file("keystore.jks")
    signingConfigs {
        create("stable") {
            if (stableKeystore.exists()) {
                storeFile = stableKeystore
                storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("ANDROID_KEY_ALIAS")
                keyPassword = System.getenv("ANDROID_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            if (stableKeystore.exists()) signingConfig = signingConfigs.getByName("stable")
        }
        release {
            if (stableKeystore.exists()) signingConfig = signingConfigs.getByName("stable")
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
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.09.03"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.fragment:fragment-ktx:1.8.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("com.google.crypto.tink:tink-android:1.13.0")

    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.8.1")
    implementation("io.coil-kt:coil-compose:2.7.0")

    implementation(platform("com.google.firebase:firebase-bom:33.4.0"))
    implementation("com.google.firebase:firebase-messaging")
}
