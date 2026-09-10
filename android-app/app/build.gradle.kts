plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android { namespace="com.ludomadagascar"; compileSdk=35
 compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
 kotlinOptions { jvmTarget = "17" }
 defaultConfig { applicationId="com.ludomadagascar"; minSdk=24; targetSdk=35; versionCode=1; versionName="1.0.0" }
}
