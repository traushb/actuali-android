import groovy.json.JsonOutput
import groovy.json.JsonSlurper

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "io.github.traushb.actuali"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "io.github.traushb.actuali"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            optimization {
                enable = true
            }
            // The optimized build is the one to judge speed by: debug builds run
            // Compose unoptimized and without its baseline profiles, several times
            // slower. ponytail: signed with the debug key so it installs for
            // testing and side-loading; set a real upload key here before
            // publishing to a store.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    testOptions {
        // Services log through android.util.Log; the stub jar throws without this.
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    compilerOptions {
        // Material 3 Expressive's components are still marked experimental in 1.5.
        optIn.add("androidx.compose.material3.ExperimentalMaterial3ExpressiveApi")
    }
}

/**
 * Converts the iOS String Catalogs into one flat JSON table per locale, so both
 * the Android port preserves the upstream translations. A value is either the string or,
 * for plural entries, an object keyed by CLDR plural category.
 */
abstract class GenerateStringCatalogs : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val catalogs: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun generate() {
        val out = outputDir.get().asFile.resolve("strings")
        out.deleteRecursively()
        out.mkdirs()
        val tables = sortedMapOf<String, MutableMap<String, Any>>()
        for (file in catalogs.files) {
            @Suppress("UNCHECKED_CAST")
            val strings = (JsonSlurper().parse(file) as Map<String, Any?>)["strings"] as Map<String, Map<String, Any?>>
            for ((key, entry) in strings) {
                @Suppress("UNCHECKED_CAST")
                val localizations = entry["localizations"] as? Map<String, Map<String, Any?>> ?: continue
                for ((locale, localization) in localizations) {
                    val unit = (localization["stringUnit"] as? Map<*, *>)?.get("value")
                    @Suppress("UNCHECKED_CAST")
                    val plural = ((localization["variations"] as? Map<*, *>)?.get("plural") as? Map<String, Map<String, Map<String, Any?>>>)
                        ?.mapValues { it.value["stringUnit"]?.get("value") }
                    val value = unit ?: plural ?: continue
                    tables.getOrPut(locale) { sortedMapOf() }[key] = value
                }
            }
        }
        for ((locale, table) in tables) {
            out.resolve("$locale.json").writeText(JsonOutput.toJson(table))
        }
    }
}

val generateStringCatalogs = tasks.register<GenerateStringCatalogs>("generateStringCatalogs") {
    catalogs.from(
        file("src/main/strings/Localizable.xcstrings"),
        file("src/main/strings/Widgets.xcstrings"),
        // Android-only wording (where iOS says "iOS", platform controls iOS draws itself).
        file("src/main/strings/Android.xcstrings"),
    )
    outputDir.set(layout.buildDirectory.dir("generated/stringCatalogs"))
}

/**
 * The few catalog strings Android can only take as resources (the widget
 * picker's label and description, launcher shortcuts), generated per locale
 * so they translate with the rest of the app.
 */
abstract class GenerateCatalogResources : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val catalogs: ConfigurableFileCollection

    /** Resource name → catalog key. */
    @get:Input
    abstract val keys: MapProperty<String, String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun generate() {
        val out = outputDir.get().asFile
        out.deleteRecursively()
        val values = sortedMapOf<String, MutableMap<String, String>>()
        for (file in catalogs.files) {
            @Suppress("UNCHECKED_CAST")
            val strings = (JsonSlurper().parse(file) as Map<String, Any?>)["strings"] as Map<String, Map<String, Any?>>
            for ((name, key) in keys.get()) {
                @Suppress("UNCHECKED_CAST")
                val localizations = strings[key]?.get("localizations") as? Map<String, Map<String, Any?>> ?: continue
                for ((locale, localization) in localizations) {
                    val value = (localization["stringUnit"] as? Map<*, *>)?.get("value") as? String ?: continue
                    values.getOrPut(locale) { sortedMapOf() }[name] = value
                }
            }
        }
        for ((locale, table) in values) {
            // iOS "pt-BR" is Android's "pt-rBR"; the source language is the default.
            val qualifier = if (locale == "en") "" else "-" + locale.replace("-", "-r")
            val dir = out.resolve("values$qualifier").apply { mkdirs() }
            val body = table.entries.joinToString("\n") { (name, value) ->
                val escaped = value.replace("&", "&amp;").replace("<", "&lt;").replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
                "    <string name=\"$name\" formatted=\"false\">\"$escaped\"</string>"
            }
            dir.resolve("catalog_strings.xml").writeText("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<resources>\n$body\n</resources>\n")
        }
    }
}

val generateCatalogResources = tasks.register<GenerateCatalogResources>("generateCatalogResources") {
    catalogs.from(
        file("src/main/strings/Localizable.xcstrings"),
        file("src/main/strings/Widgets.xcstrings"),
        file("src/main/strings/Android.xcstrings"),
    )
    keys.putAll(
        mapOf(
            "widget_category_balances" to "Category Balances",
            "widget_category_balances_description" to "Remaining available amount for your chosen categories.",
        ),
    )
    outputDir.set(layout.buildDirectory.dir("generated/catalogResources"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(generateStringCatalogs, GenerateStringCatalogs::outputDir)
        variant.sources.res?.addGeneratedSourceDirectory(generateCatalogResources, GenerateCatalogResources::outputDir)
        // JVM unit tests read the same files off the classpath.
        (variant as? com.android.build.api.variant.HasHostTests)?.hostTests?.values?.forEach { test ->
            test.sources.resources?.addGeneratedSourceDirectory(generateStringCatalogs, GenerateStringCatalogs::outputDir)
            test.sources.resources?.addStaticSourceDirectory("src/main/assets")
        }
    }
}

// Unit tests run on the host JVM, which needs the desktop build of the bundled
// SQLite driver (the Android one loads a .so that only exists on device).
configurations.matching { it.name.startsWith("test") && it.name.endsWith("RuntimeClasspath") }.configureEach {
    exclude(group = "androidx.sqlite", module = "sqlite-bundled-android")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.sqlite.bundled)
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.glance.appwidget)
    implementation(libs.androidx.glance.material3)
    implementation(libs.androidx.browser)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation("androidx.sqlite:sqlite-bundled-jvm:${libs.versions.sqlite.get()}")
}
