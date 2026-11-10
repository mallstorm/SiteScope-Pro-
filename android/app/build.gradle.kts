plugins { id("com.android.application"); id("org.jetbrains.kotlin.android"); id("org.jetbrains.kotlin.plugin.compose") }
android {
 namespace="pro.sitescope.app"; compileSdk=35
 defaultConfig { applicationId="pro.sitescope.app"; minSdk=28; targetSdk=36; versionCode=300028; versionName="0.30.0-alpha28" }
 buildFeatures { compose=true; buildConfig=true }
 buildTypes { release { isMinifyEnabled=true; proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"),"proguard-rules.pro") } }
}
dependencies {
 implementation(platform("androidx.compose:compose-bom:2025.02.00"))
 implementation("androidx.activity:activity-compose:1.10.1")
 implementation("androidx.compose.material3:material3")
 implementation("androidx.navigation:navigation-compose:2.8.7")
 implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
 implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
 implementation("com.squareup.retrofit2:retrofit:2.11.0")
 implementation("com.squareup.retrofit2:converter-moshi:2.11.0")
 implementation("com.squareup.moshi:moshi-kotlin:1.15.2")
}
