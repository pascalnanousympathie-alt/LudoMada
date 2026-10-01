plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }

android { namespace="com.ludomadagascar"; compileSdk=35; buildToolsVersion="36.0.0"
 buildFeatures { buildConfig = true }
 compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
 kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
 defaultConfig { applicationId="com.ludomadagascar"; minSdk=24; targetSdk=35; versionCode=2; versionName="1.0.1"; testInstrumentationRunner="androidx.test.runner.AndroidJUnitRunner"
     val apiBaseUrl = providers.gradleProperty("LUDOMADA_API_BASE_URL").orElse("https://ludomada-production.up.railway.app").get()
     buildConfigField("String", "API_BASE_URL", "\"${apiBaseUrl.replace("\\", "\\\\").replace("\"", "\\\"")}\"")
     val reownProjectId = providers.gradleProperty("REOWN_PROJECT_ID").orElse("").get().trim()
     buildConfigField("String", "REOWN_PROJECT_ID", "\"$reownProjectId\"")
     buildConfigField("boolean", "REOWN_ENABLED", reownProjectId.isNotBlank().toString())
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
    // Reown stays fully compiled, but is only packaged into APKs when a real Project ID is supplied.
    val reownRuntimeEnabled = providers.gradleProperty("REOWN_PROJECT_ID").orElse("").get().trim().isNotBlank()
    val reownScope = if (reownRuntimeEnabled) configurations.implementation else configurations.compileOnly
    add(reownScope.name, platform("com.reown:android-bom:1.6.17"))
    add(reownScope.name, "com.reown:android-core")
    add(reownScope.name, "com.reown:appkit")
    implementation("androidx.credentials:credentials:1.6.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.6.0")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.1.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
}
