plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android { namespace="com.ludomadagascar"; compileSdk=35
 compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
 kotlinOptions { jvmTarget = "17" }
 defaultConfig { applicationId="com.ludomadagascar"; minSdk=24; targetSdk=35; versionCode=1; versionName="1.0.0"; testInstrumentationRunner="androidx.test.runner.AndroidJUnitRunner" }

 val signingStoreFile = System.getenv("LUDOMADA_SIGNING_STORE_FILE")
 val signingStorePassword = System.getenv("LUDOMADA_SIGNING_STORE_PASSWORD")
 val signingKeyAlias = System.getenv("LUDOMADA_SIGNING_KEY_ALIAS")
 val signingKeyPassword = System.getenv("LUDOMADA_SIGNING_KEY_PASSWORD")
 val signingReady = listOf(signingStoreFile, signingStorePassword, signingKeyAlias, signingKeyPassword).all { !it.isNullOrBlank() }

 signingConfigs {
     if (signingReady) {
         create("release") {
             storeFile = file(signingStoreFile!!)
             storePassword = signingStorePassword
             keyAlias = signingKeyAlias
             keyPassword = signingKeyPassword
         }
     }
 }

 buildTypes {
     getByName("release") {
         if (signingReady) {
             signingConfig = signingConfigs.getByName("release")
         }
         isMinifyEnabled = false
     }
 }
}

dependencies {
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
}
