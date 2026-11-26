import org.jetbrains.kotlin.gradle.dsl.KotlinVersion
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    alias(libs.plugins.android.library)
    alias(kei.plugins.android.base)
    alias(kei.plugins.spotless)
}

val sharedDirectory = layout.buildDirectory.dir("generated/shared-kotlin")
val copySharedSources = tasks.register<Sync>("copySharedSources") {
    from(rootProject.file("core/src/main/kotlin")) {
        include(
            "keiyoushi/annotation/Source.kt",
            "keiyoushi/source/CustomUrlPreferences.kt",
            "keiyoushi/source/FetchableBaseUrl.kt",
            "keiyoushi/source/UrlActivity.kt",
            "keiyoushi/network/RateLimit.kt",
            "keiyoushi/utils/Collections.kt",
            "keiyoushi/utils/Context.kt",
            "keiyoushi/utils/Preferences.kt",
        )
    }
    into(sharedDirectory)
}

android {
    namespace = "keiyoushi.core14"
    sourceSets.named("main") {
        kotlin.directories.add(sharedDirectory.get().asFile.path)
    }
}
tasks.named("preBuild") { dependsOn(copySharedSources) }

dependencies {
    compileOnly(libs.bundles.legacy)
    compileOnly(libs.tachiyomi.lib.v14) { exclude(group = "com.github.inorichi.injekt") }
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions.apiVersion.set(KotlinVersion.KOTLIN_2_0)
}
