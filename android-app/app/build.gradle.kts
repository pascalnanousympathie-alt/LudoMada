plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android { namespace="com.ludomadagascar"; compileSdk=35
 buildFeatures { buildConfig = true }
 compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
 kotlinOptions { jvmTarget = "17" }
 defaultConfig { applicationId="com.ludomadagascar"; minSdk=24; targetSdk=35; versionCode=2; versionName="1.0.1"; testInstrumentationRunner="androidx.test.runner.AndroidJUnitRunner"
     val apiBaseUrl = providers.gradleProperty("LUDOMADA_API_BASE_URL").orElse(providers.environmentVariable("LUDOMADA_API_BASE_URL")).orElse("https://ludomada-api-staging.onrender.com").get().trim().trimEnd('/')
     require(!apiBaseUrl.contains("railway.app", ignoreCase = true)) {
         "The legacy Railway API URL is retired; set LUDOMADA_API_BASE_URL to the deployed API URL."
     }
     buildConfigField("String", "API_BASE_URL", "\"${apiBaseUrl.replace("\\", "\\\\").replace("\"", "\\\"")}\"")
 }
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
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
}
