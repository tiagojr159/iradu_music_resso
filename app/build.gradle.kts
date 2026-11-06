/*
 * SPDX-FileCopyrightText: 2025 NewPipe e.V. <https://newpipe-ev.de>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

import com.android.build.api.dsl.ApplicationExtension
import java.io.File
import java.time.LocalDate

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.jetbrains.kotlin.android)
    alias(libs.plugins.jetbrains.kotlin.kapt)
    alias(libs.plugins.google.ksp)
    alias(libs.plugins.jetbrains.kotlin.parcelize)
    alias(libs.plugins.sonarqube)
    checkstyle
}

val gitWorkingBranch = providers.exec {
    // In some environments the project folder is not a git repository (or git isn't available).
    // Don't fail the Gradle configuration/cache in that case.
    commandLine("git", "rev-parse", "--abbrev-ref", "HEAD")
    isIgnoreExitValue = true
}.standardOutput.asText.map { it.trim() }

val generatedDatedAppNameResDir = file("$buildDir/generated/datedAppName/res")
val useBaseApplicationId = providers.gradleProperty("baseApplicationId")
    .map(String::toBoolean)
    .getOrElse(false)

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}

kotlin {
    compilerOptions {
        // TODO: Drop annotation default target when it is stable
        freeCompilerArgs.addAll(
            "-Xannotation-default-target=param-property"
        )
    }
}

configure<ApplicationExtension> {
    compileSdk = 36
    namespace = "org.schabi.newpipe"

    defaultConfig {
        applicationId = "com.iradu.music"
        minSdk = 23
        targetSdk = 35

        versionCode = System.getProperty("versionCodeOverride")?.toInt() ?: 1010

        versionName = "0.28.4"
        System.getProperty("versionNameSuffix")?.let { versionNameSuffix = it }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            isDebuggable = true

            // suffix the app id and the app name with git branch name
            val defaultBranches = listOf("master", "dev")
            val workingBranch = gitWorkingBranch.getOrElse("")
            val normalizedWorkingBranch = workingBranch
                .replaceFirst("^[^A-Za-z]+".toRegex(), "")
                .replace("[^0-9A-Za-z]+".toRegex(), "")

            if (useBaseApplicationId) {
                // Produces a sideloadable car-test APK using the real application ID.
            } else if (normalizedWorkingBranch.isEmpty() || workingBranch in defaultBranches) {
                // default values when branch name could not be determined or is master or dev
                applicationIdSuffix = ".debug"
            } else {
                applicationIdSuffix = ".debug.$normalizedWorkingBranch"
            }
        }

        release {
            System.getProperty("packageSuffix")?.let { suffix ->
                applicationIdSuffix = suffix
            }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    lint {
        lintConfig = file("lint.xml")
        // Continue the debug build even when errors are found
        abortOnError = false
    }

    compileOptions {
        // Flag to enable support for the new language APIs
        isCoreLibraryDesugaringEnabled = true
        encoding = "utf-8"
    }

    sourceSets {
        getByName("main") {
            res.srcDir(generatedDatedAppNameResDir)
        }

        getByName("androidTest") {
            assets.directories += "$projectDir/schemas"
        }
    }

    androidResources {
        generateLocaleConfig = true
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
        resValues = true
    }

    packaging {
        resources {
            // remove two files which belong to jsoup
            // no idea how they ended up in the META-INF dir...
            excludes += setOf(
                "META-INF/README.md",
                "META-INF/CHANGES",
                "META-INF/COPYRIGHT" // "COPYRIGHT" belongs to RxJava...
            )
        }
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

val generateDatedAppNameRes = tasks.register("generateDatedAppNameRes") {
    val outputDir = generatedDatedAppNameResDir

    outputs.dir(outputDir)
    outputs.upToDateWhen { false }

    doLast {
        val date = LocalDate.now()
        val valuesDir = File(outputDir, "values")
        val appNameFile = File(valuesDir, "app_name.xml")
        valuesDir.mkdirs()
        appNameFile.writeText(
            """
            <?xml version="1.0" encoding="utf-8"?>
            <resources>
                <string name="app_name">iradu music ${date.dayOfMonth}.${date.monthValue}</string>
            </resources>
            """.trimIndent()
        )
    }
}

tasks.matching { it.name.matches(Regex("pre.*Build")) }.configureEach {
    dependsOn(generateDatedAppNameRes)
}

// Custom dependency configuration for ktlint
val ktlint by configurations.creating

// https://checkstyle.org/#JRE_and_JDK
tasks.withType<Checkstyle>().configureEach {
    javaLauncher = javaToolchains.launcherFor {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

checkstyle {
    configDirectory = rootProject.file("checkstyle")
    isIgnoreFailures = false
    isShowViolations = true
    toolVersion = libs.versions.checkstyle.get()
}

tasks.register<Checkstyle>("runCheckstyle") {
    source("src")
    include("**/*.java")
    exclude("**/gen/**")
    exclude("**/R.java")
    exclude("**/BuildConfig.java")
    exclude("main/java/us/shandian/giga/**")

    classpath = configurations.getByName("checkstyle")

    isShowViolations = true

    reports {
        xml.required = true
        html.required = true
    }
}

val outputDir = project.layout.buildDirectory.dir("reports/ktlint/")
val inputFiles = fileTree("src") { include("**/*.kt") }

tasks.register<JavaExec>("runKtlint") {
    inputs.files(inputFiles)
    outputs.dir(outputDir)
    mainClass.set("com.pinterest.ktlint.Main")
    classpath = configurations.getByName("ktlint")
    args = listOf("--editorconfig=../.editorconfig", "src/**/*.kt")
    jvmArgs = listOf("--add-opens", "java.base/java.lang=ALL-UNNAMED")
}

tasks.register<JavaExec>("formatKtlint") {
    inputs.files(inputFiles)
    outputs.dir(outputDir)
    mainClass.set("com.pinterest.ktlint.Main")
    classpath = configurations.getByName("ktlint")
    args = listOf("--editorconfig=../.editorconfig", "-F", "src/**/*.kt")
    jvmArgs = listOf("--add-opens", "java.base/java.lang=ALL-UNNAMED")
}

tasks.register<CheckDependenciesOrder>("checkDependenciesOrder") {
    tomlFile = layout.projectDirectory.file("../gradle/libs.versions.toml")
}

afterEvaluate {
    tasks.named("preDebugBuild").configure {
        if (!System.getProperties().containsKey("skipFormatKtlint")) {
            dependsOn("formatKtlint")
        }
        dependsOn("runCheckstyle", "runKtlint", "checkDependenciesOrder")
    }
}

sonar {
    properties {
        property("sonar.projectKey", "TeamNewPipe_NewPipe")
        property("sonar.organization", "teamnewpipe")
        property("sonar.host.url", "https://sonarcloud.io")
    }
}

dependencies {
    /** Desugaring **/
    coreLibraryDesugaring(libs.android.desugar)

    /** NewPipe libraries **/
    implementation(libs.newpipe.nanojson)
    implementation(libs.newpipe.extractor)
    implementation(libs.newpipe.filepicker)

    /** Checkstyle **/
    checkstyle(libs.puppycrawl.checkstyle)
    ktlint(libs.pinterest.ktlint)

    /** AndroidX **/
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.cardview)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core)
    implementation(libs.androidx.documentfile)
    implementation(libs.androidx.fragment)
    implementation(libs.androidx.lifecycle.livedata)
    implementation(libs.androidx.lifecycle.viewmodel)
    implementation(libs.androidx.localbroadcastmanager)
    implementation(libs.androidx.media)
    implementation(libs.androidx.preference)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.rxjava3)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.swiperefreshlayout)
    implementation(libs.androidx.viewpager2)
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.work.rxjava3)
    implementation(libs.google.android.material)
    implementation(libs.androidx.webkit)

    /** Third-party libraries **/
    implementation(libs.livefront.bridge)
    implementation(libs.evernote.statesaver.core)
    kapt(libs.evernote.statesaver.compiler)

    // HTML parser
    implementation(libs.jsoup)

    // HTTP client
    implementation(libs.squareup.okhttp)

    // Media player
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.exoplayer.dash)
    implementation(libs.androidx.media3.database)
    implementation(libs.androidx.media3.datasource)
    implementation(libs.androidx.media3.exoplayer.hls)
    implementation(libs.androidx.media3.session)
    implementation(libs.androidx.media3.exoplayer.smoothstreaming)
    implementation(libs.androidx.media3.ui)

    // Manager for complex RecyclerView layouts
    implementation(libs.lisawray.groupie.core)
    implementation(libs.lisawray.groupie.viewbinding)

    // Image loading
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)

    // Markdown library for Android
    implementation(libs.noties.markwon.core)
    implementation(libs.noties.markwon.linkify)

    // Crash reporting
    implementation(libs.acra.core)
    compileOnly(libs.google.autoservice.annotations)
    ksp(libs.zacsweers.autoservice.compiler)

    // Properly restarting
    implementation(libs.jakewharton.phoenix)

    // Reactive extensions for Java VM
    implementation(libs.reactivex.rxjava)
    implementation(libs.reactivex.rxandroid)
    // RxJava binding APIs for Android UI widgets
    implementation(libs.jakewharton.rxbinding)

    // Date and time formatting
    implementation(libs.ocpsoft.prettytime)

    /** Debugging **/
    // Memory leak detection
    debugImplementation(libs.squareup.leakcanary.watcher)
    debugImplementation(libs.squareup.leakcanary.plumber)
    debugImplementation(libs.squareup.leakcanary.core)
    // Debug bridge for Android
    debugImplementation(libs.facebook.stetho.core)
    debugImplementation(libs.facebook.stetho.okhttp3)

    /** Testing **/
    testImplementation(libs.junit)
    testImplementation(libs.mockito.core)

    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.runner)
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.assertj.core)
}
