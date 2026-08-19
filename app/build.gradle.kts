plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.lyon.rhythmictouch"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.lyon.rhythmictouch"
        minSdk = 28
        targetSdk = 36
        versionCode = 5
        versionName = "1.0.4"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    sourceSets {
        getByName("main") {
            assets.srcDir(layout.buildDirectory.dir("generated/daemonAssets"))
        }
    }

    ndkVersion = "28.2.13676358"

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    packagingOptions {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1,ASL2.0,NOTICE,LICENSE,LICENSE.txt,LICENSE.md,NOTICE.txt,NOTICE.md}"
        }
    }

    lint {
        abortOnError = false
        disable += listOf("MissingTranslation", "ExtraTranslation", "GooglePlayPolicyViolation")
    }

    signingConfigs {
        create("release") {
            val ksFile = System.getenv("KEYSTORE_FILE")
            if (ksFile != null && file(ksFile).exists()) {
                storeFile = file(ksFile)
                storePassword = System.getenv("KEYSTORE_PASSWORD") ?: ""
                keyAlias = System.getenv("KEY_ALIAS") ?: ""
                keyPassword = System.getenv("KEY_PASSWORD") ?: ""
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            val ksFile = System.getenv("KEYSTORE_FILE")
            if (ksFile != null && file(ksFile).exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
}

dependencies {
    compileOnly("de.robv.android.xposed:api:82")

    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar", "*.jar"))))
    implementation("top.yukonga.miuix.kmp:miuix-android:0.8.8")
    implementation("top.yukonga.miuix.kmp:miuix-icons:0.8.8")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
}

val packageDaemonBinaries by tasks.registering {
    doLast {
        val daemonAssets = layout.buildDirectory.dir("generated/daemonAssets/bin").get().asFile
        layout.buildDirectory.dir("intermediates/cxx").get().asFile.walkTopDown()
            .filter { it.name == "obj" }
            .forEach { objDir ->
                objDir.listFiles()?.forEach { abiDir ->
                    val abi = abiDir.name
                    if (abi.startsWith(".")) return@forEach
                    val destDir = File(daemonAssets, abi).apply { mkdirs() }
                    listOf("rhythmic_daemon", "librhythmic-hook.so").forEach { name ->
                        val src = File(abiDir, name)
                        if (src.exists()) src.copyTo(File(destDir, name), overwrite = true)
                    }
                }
            }
    }
}

tasks.whenTaskAdded {
    if (name.startsWith("merge") && name.endsWith("Assets")) {
        dependsOn(packageDaemonBinaries)
    }
}