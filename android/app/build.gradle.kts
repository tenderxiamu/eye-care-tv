import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.tenderxiamu.eyecaretv"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.tenderxiamu.eyecaretv"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // 内置视频直接复用仓库里 Windows 端的那一份，避免在 Git 里重复存放 34 MB 素材
    sourceSets["main"].res.srcDir("build/generated/video/res")
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

val bundledVideo = rootProject.file("../app/resources/default_eye_exercise.mp4")

val copyBundledVideo by tasks.registering(Copy::class) {
    from(bundledVideo)
    into(layout.buildDirectory.dir("generated/video/res/raw"))
}

tasks.named("preBuild").configure { dependsOn(copyBundledVideo) }

dependencies {
    implementation("androidx.core:core-ktx:1.17.0")
    testImplementation("junit:junit:4.13.2")
}
