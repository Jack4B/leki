plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
}

/** Pilnuje, żeby CHANGELOG miał wpis dla bieżącej wersji (numer wersji jest w gradle.properties). */
tasks.register("checkChangelog") {
    val version = providers.gradleProperty("appVersionName")
    val changelog = layout.projectDirectory.file("CHANGELOG.md")
    doLast {
        val v = version.get()
        if (!changelog.asFile.readText().contains("## $v")) throw GradleException("CHANGELOG.md nie ma wpisu dla wersji $v")
    }
}
