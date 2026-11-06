import java.security.MessageDigest

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
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
}


val r7GameplayArchiveParts = layout.projectDirectory.dir("asset-sources/r7-parts")
val r7GameplayGeneratedAssets = layout.buildDirectory.dir("generated/assets/ludomada-r7")
val r7GameplayArchive = layout.buildDirectory.file("intermediates/ludomada-r7/ludomada-gameplay-environment-r7.zip")

val prepareR7GameplayEnvironmentAssets = tasks.register("prepareR7GameplayEnvironmentAssets") {
    inputs.dir(r7GameplayArchiveParts)
    outputs.dir(r7GameplayGeneratedAssets)

    doLast {
        val partPattern = Regex("r7-[0-9]{3}\\.bin")
        val parts = r7GameplayArchiveParts.asFile.listFiles()
            ?.filter { it.isFile && partPattern.matches(it.name) }
            ?.sortedBy { it.name }
            ?: emptyList()

        check(parts.size == 35) {
            "LudoMada Blender r7 archive must contain 35 ordered parts; found ${parts.size}"
        }
        parts.forEachIndexed { index, part ->
            val expectedBytes = if (index == 34) 3612L else 13500L
            val expectedName = "r7-${index.toString().padStart(3, '0')}.bin"
            check(part.name == expectedName && part.length() == expectedBytes) {
                "Invalid LudoMada Blender r7 archive segment: ${part.name} (${part.length()} bytes)"
            }
        }

        val archive = r7GameplayArchive.get().asFile
        archive.parentFile.mkdirs()
        archive.outputStream().buffered().use { output ->
            parts.forEach { part ->
                part.inputStream().buffered().use { input -> input.copyTo(output) }
            }
        }

        val generatedRoot = r7GameplayGeneratedAssets.get().asFile
        project.delete(generatedRoot)
        val generated3d = generatedRoot.resolve("3d")
        generated3d.mkdirs()
        project.copy {
            from(project.zipTree(archive))
            into(generated3d)
        }

        val runtimeGlb = generated3d.resolve("ludomada-gameplay-environment-r7.glb")
        check(runtimeGlb.isFile && runtimeGlb.length() == 2011296L) {
            "LudoMada Blender r7 GLB is missing or has an unexpected size"
        }
        val sha256 = MessageDigest.getInstance("SHA-256")
            .digest(runtimeGlb.readBytes())
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
        check(sha256 == "1973b119d4ad02647c9cce9f193a37c25b0ae0ca778507f20b7078276f6d9cba") {
            "LudoMada Blender r7 GLB SHA-256 mismatch: $sha256"
        }
    }
}

android.sourceSets.getByName("main").assets.srcDir(r7GameplayGeneratedAssets)
tasks.named("preBuild").configure {
    dependsOn(prepareR7GameplayEnvironmentAssets)
}
