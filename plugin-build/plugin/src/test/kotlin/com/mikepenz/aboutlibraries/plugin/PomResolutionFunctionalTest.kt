package com.mikepenz.aboutlibraries.plugin

import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * POM resolution for modules whose POM the batched, attribute-less lookup can't fetch.
 *
 * The fixtures live in a local Maven repository written by each test, so the cases don't depend on
 * how a published library happens to be laid out.
 */
class PomResolutionFunctionalTest {

    @TempDir
    lateinit var projectDir: File

    private val repoDir: File get() = File(projectDir, "repo")

    /**
     * A module that publishes several variants told apart only by an attribute the detached POM
     * lookup doesn't request (here a custom flavor, like the debug and release variants an Android
     * library publishes) fails variant selection there. It must still be listed, with its POM data.
     */
    @Test
    fun `a module whose variants the POM lookup can't choose between is still listed`() {
        writeAmbiguousModule()
        setupProject(
            deps = """implementation("com.example:ambiguous:1.0")""",
            requestedFlavor = "release",
        )

        val result = run("exportLibraryDefinitions")
        assertEquals(TaskOutcome.SUCCESS, result.task(":exportLibraryDefinitions")?.outcome)

        val entry = extractLibraryEntry(readOutput(), "com.example:ambiguous")
            ?: error("com.example:ambiguous is missing from the output: ${readOutput()}")
        assertTrue(entry.contains("\"name\":\"Ambiguous Variants\""), "The entry should carry the POM's name. Got: $entry")
        assertTrue(entry.contains("Apache-2.0"), "The entry should carry the POM's license. Got: $entry")
        assertFalse(result.output.contains("missing from the output"), "No dependency should be reported missing. Output: ${result.output}")
    }

    /**
     * The fallback runs while the configuration cache entry is stored, so it must keep working with
     * the configuration cache and Isolated Projects enabled.
     */
    @Test
    fun `the fallback lookup works with configuration cache and isolated projects`() {
        writeAmbiguousModule()
        setupProject(
            deps = """implementation("com.example:ambiguous:1.0")""",
            requestedFlavor = "release",
        )
        File(projectDir, "gradle.properties").writeText(
            """
            org.gradle.unsafe.isolated-projects=true
            org.gradle.configuration-cache=true
            """.trimIndent()
        )

        val result = run("exportLibraryDefinitions")
        assertEquals(TaskOutcome.SUCCESS, result.task(":exportLibraryDefinitions")?.outcome)
        assertTrue(result.output.contains("Isolated Projects is an incubating feature"), "Isolated Projects must be enabled. Output: ${result.output}")
        assertFalse(result.output.contains("Configuration cache entry discarded"), "The configuration cache entry must be stored. Output: ${result.output}")
        assertTrue(
            extractLibraryEntry(readOutput(), "com.example:ambiguous") != null,
            "com.example:ambiguous is missing from the output: ${readOutput()}"
        )
    }

    /**
     * A dependency with no POM at all can't be listed. That is reported once, by coordinate, rather
     * than leaving the dependency out silently.
     */
    @Test
    fun `a dependency without a POM is reported as missing`() {
        writePomlessModule()
        setupProject(
            deps = """implementation("com.example:pomless:1.0")""",
            gradleMetadataOnly = true,
        )

        val result = run("exportLibraryDefinitions")
        assertEquals(TaskOutcome.SUCCESS, result.task(":exportLibraryDefinitions")?.outcome)

        val warning = result.output.lines().firstOrNull { it.contains("missing from the output") }
            ?: error("The missing dependency was not reported. Output: ${result.output}")
        assertTrue(warning.contains("com.example:pomless:1.0"), "The report should name the dependency. Got: $warning")
    }

    // ----- fixtures -----

    private fun writeAmbiguousModule() {
        val dir = File(repoDir, "com/example/ambiguous/1.0").apply { mkdirs() }
        File(dir, "ambiguous-1.0.pom").writeText(
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <!-- do_not_remove: published-with-gradle-metadata -->
              <modelVersion>4.0.0</modelVersion>
              <groupId>com.example</groupId>
              <artifactId>ambiguous</artifactId>
              <version>1.0</version>
              <name>Ambiguous Variants</name>
              <licenses>
                <license>
                  <name>The Apache Software License, Version 2.0</name>
                  <url>https://www.apache.org/licenses/LICENSE-2.0.txt</url>
                </license>
              </licenses>
            </project>
            """.trimIndent()
        )
        File(dir, "ambiguous-1.0.module").writeText(
            moduleMetadata(
                module = "ambiguous",
                variants = listOf("debug", "release"),
            )
        )
    }

    private fun writePomlessModule() {
        val dir = File(repoDir, "com/example/pomless/1.0").apply { mkdirs() }
        File(dir, "pomless-1.0.module").writeText(
            moduleMetadata(
                module = "pomless",
                variants = listOf("release"),
            )
        )
    }

    /** Gradle module metadata with one runtime variant per flavor in [variants], and no files. */
    private fun moduleMetadata(module: String, variants: List<String>): String {
        val variantJson = variants.joinToString(",\n") { flavor ->
            """
                {
                  "name": "${flavor}RuntimeElements",
                  "attributes": {
                    "org.gradle.category": "library",
                    "org.gradle.dependency.bundling": "external",
                    "org.gradle.usage": "java-runtime",
                    "com.example.flavor": "$flavor"
                  }
                }
            """.trimIndent()
        }
        return """
            {
              "formatVersion": "1.1",
              "component": {
                "group": "com.example",
                "module": "$module",
                "version": "1.0",
                "attributes": { "org.gradle.status": "release" }
              },
              "createdBy": { "gradle": { "version": "9.8.1" } },
              "variants": [
            $variantJson
              ]
            }
        """.trimIndent()
    }

    /**
     * Writes a JVM project resolving from the local [repoDir] only. [requestedFlavor] is set on the
     * project's own resolvable configurations, so they pick one variant, while the plugin's detached
     * POM lookup requests no attributes. [gradleMetadataOnly] makes the repository read Gradle
     * module metadata without a POM.
     */
    private fun setupProject(
        deps: String,
        requestedFlavor: String? = null,
        gradleMetadataOnly: Boolean = false,
    ) {
        File(projectDir, "settings.gradle.kts").writeText(
            """
            rootProject.name = "test-project"
            """.trimIndent()
        )
        val flavorConfig = if (requestedFlavor == null) "" else {
            """
            val flavor = Attribute.of("com.example.flavor", String::class.java)
            configurations.configureEach {
                if (isCanBeResolved) attributes.attribute(flavor, "$requestedFlavor")
            }
            """.trimIndent()
        }
        val metadataSources = if (gradleMetadataOnly) "metadataSources { gradleMetadata() }" else ""
        File(projectDir, "build.gradle.kts").writeText(
            """
            plugins {
                id("java-library")
                id("com.mikepenz.aboutlibraries.plugin")
            }
            repositories {
                maven {
                    url = uri("${repoDir.toURI()}")
                    $metadataSources
                }
            }
            $flavorConfig
            dependencies {
                $deps
            }
            aboutLibraries {
                offlineMode = true
            }
            """.trimIndent()
        )
    }

    // ----- helpers -----

    private fun run(vararg args: String): BuildResult =
        @Suppress("WithPluginClasspathUsage")
        GradleRunner.create()
            .withProjectDir(projectDir)
            .withArguments(*args, "--stacktrace")
            .withPluginClasspath()
            .build()

    private fun readOutput(): String =
        File(projectDir, "build/generated/aboutLibraries/aboutlibraries.json").readText()

    /**
     * Returns the JSON object substring for the library with the given uniqueId, or `null` if it
     * is not present. The plugin emits compact JSON so we walk braces manually.
     */
    private fun extractLibraryEntry(json: String, uniqueId: String): String? {
        val key = "\"uniqueId\":\"$uniqueId\""
        val keyIdx = json.indexOf(key)
        if (keyIdx < 0) return null
        var start = keyIdx
        while (start > 0 && json[start] != '{') start--
        var depth = 0
        var end = start
        while (end < json.length) {
            when (json[end]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return json.substring(start, end + 1)
                }
            }
            end++
        }
        return null
    }
}
