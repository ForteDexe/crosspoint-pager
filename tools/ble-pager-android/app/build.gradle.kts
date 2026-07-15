import com.android.build.gradle.internal.api.BaseVariantOutputImpl

plugins {
    id("com.android.application")
}

val repositoryRoot = rootProject.projectDir.resolve("../..").canonicalFile

fun gitValue(vararg arguments: String): String = runCatching {
    providers.exec {
        workingDir(repositoryRoot)
        commandLine("git", *arguments)
        isIgnoreExitValue = true
    }.standardOutput.asText.get().trim()
}.getOrDefault("").ifEmpty { "unknown" }

fun quotedBuildConfigValue(value: String): String =
    "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""

val gitBranch = gitValue("rev-parse", "--abbrev-ref", "HEAD")
val gitCommit = gitValue("rev-parse", "--short=8", "HEAD")
val gitStatus = gitValue("status", "--porcelain")
val gitDirty = gitStatus != "unknown" && gitStatus.isNotEmpty()
val pagerBuildId = "0.1.0-dev-$gitBranch-$gitCommit${if (gitDirty) "-dirty" else ""}"

android {
    namespace = "org.crosspointreader.pagerrelay"
    compileSdk = 35

    defaultConfig {
        applicationId = "org.crosspointreader.pagerrelay"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        buildConfigField("String", "PAGER_BUILD_ID", quotedBuildConfigValue(pagerBuildId))
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

android.applicationVariants.all {
    outputs.all {
        (this as BaseVariantOutputImpl).outputFileName = "crosspoint-pager.apk"
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
