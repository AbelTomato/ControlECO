import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

val signingPropertiesFile = rootProject.file("keystore.properties")
val signingProperties = Properties().apply {
    if (signingPropertiesFile.exists()) signingPropertiesFile.inputStream().use { load(it) }
}
fun signingValue(propertyName: String, environmentName: String): String? =
    providers.gradleProperty(propertyName).orNull
        ?: signingProperties.getProperty(propertyName)
        ?: providers.environmentVariable(environmentName).orNull

android {
    namespace = "com.controleco.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.abeltomato.tomatoledger"
        minSdk = 26
        targetSdk = 35
        // 每次带修改的构建把第三位 +1；beta 阶段版本名带 -beta 后缀
        versionCode = 1
        versionName = "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            val storeFileValue = signingValue("storeFile", "RELEASE_STORE_FILE")
            val storePasswordValue = signingValue("storePassword", "RELEASE_STORE_PASSWORD")
            val keyAliasValue = signingValue("keyAlias", "RELEASE_KEY_ALIAS")
            val keyPasswordValue = signingValue("keyPassword", "RELEASE_KEY_PASSWORD")
            if (listOf(storeFileValue, storePasswordValue, keyAliasValue, keyPasswordValue).all { !it.isNullOrBlank() }) {
                signingConfig = signingConfigs.create("release") {
                    storeFile = rootProject.file(storeFileValue!!)
                    storePassword = storePasswordValue!!
                    keyAlias = keyAliasValue!!
                    keyPassword = keyPasswordValue!!
                }
            } else {
                signingConfig = null
                if (gradle.startParameter.taskNames.any { it.contains("Release", ignoreCase = true) }) {
                    throw GradleException(
                        "Release signing is not configured. Set RELEASE_STORE_FILE, " +
                            "RELEASE_STORE_PASSWORD, RELEASE_KEY_ALIAS and RELEASE_KEY_PASSWORD, " +
                            "or add them to the untracked keystore.properties file.",
                    )
                }
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
    testOptions {
        // 单元测试中 android.util.Log 等框架方法返回默认值而非抛异常
        unitTests.isReturnDefaultValues = true
    }
}

ksp {
    // 导出 Room schema，用于校验迁移 SQL（app/schemas/）
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    debugImplementation(libs.androidx.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.androidx.room.testing)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)

    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
}
