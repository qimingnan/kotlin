/*
 * Copyright 2010-2017 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.kotlin.gradle

import org.jetbrains.kotlin.gradle.util.checkBytecodeContains
import org.jetbrains.kotlin.gradle.util.modify
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals

class BuildCacheIT : BaseGradleIT() {
    override fun defaultBuildOptions(): BuildOptions =
            super.defaultBuildOptions().copy(withBuildCache = true)

    companion object {
        private val GRADLE_VERSION = "4.3.1"
    }

    @Test
    fun noCacheWithGradlePre43() = with(Project("simpleProject", "4.2")) {
        // Check that even with the build cache enabled, the Kotlin tasks are not cacheable with Gradle < 4.3:
        val optionsWithCache = defaultBuildOptions().copy(withBuildCache = true)

        build("assemble", options = optionsWithCache) {
            assertSuccessful()
            assertNotContains("Packing task ':compileKotlin'")
        }
        build("clean", "assemble", options = optionsWithCache) {
            assertSuccessful()
            assertNotContains(":compileKotlin FROM-CACHE")
            assertContains(":compileJava FROM-CACHE")
        }
    }

    @Test
    fun testCacheHitAfterClean() = with(Project("simpleProject", GRADLE_VERSION)) {
        prepareLocalBuildCache()

        build("assemble") {
            assertSuccessful()
            assertContains("Packing task ':compileKotlin'")
        }
        build("clean", "assemble") {
            assertSuccessful()
            assertContains(":compileKotlin FROM-CACHE")
        }
    }

    @Test
    fun testCacheHitAfterCacheHit() = with(Project("simpleProject", GRADLE_VERSION)) {
        prepareLocalBuildCache()

        build("assemble") {
            assertSuccessful()
            // Should store the output into the cache:
            assertContains("Packing task ':compileKotlin'")
        }

        val sourceFile = File(projectDir, "src/main/kotlin/helloWorld.kt")
        val originalSource: String = sourceFile.readText()
        val modifiedSource: String = originalSource.replace(" and ", " + ")
        sourceFile.writeText(modifiedSource)

        build("assemble") {
            assertSuccessful()
            assertContains("Packing task ':compileKotlin'")
        }

        sourceFile.writeText(originalSource)

        build("assemble") {
            assertSuccessful()
            // Should load the output from cache:
            assertContains(":compileKotlin FROM-CACHE")
        }

        sourceFile.writeText(modifiedSource)

        build("assemble") {
            assertSuccessful()
            // And should load the output from cache again, without compilation:
            assertContains(":compileKotlin FROM-CACHE")
        }
    }

    @Test
    fun testCorrectBuildAfterCacheHit() = with(Project("simpleProject", GRADLE_VERSION)) {
        prepareLocalBuildCache()

        val fooKt = File(projectDir, "src/main/kotlin/foo/foo.kt").apply {
            parentFile.mkdirs()
            writeText("package foo; fun foo(i: Int): Int = 1")
        }

        // bar.kt references foo.kt; we expect it to recompile during the first build after a cache hit.
        File(projectDir, "src/main/kotlin/bar/bar.kt").apply {
            parentFile.mkdirs()
            writeText("package bar; import foo.*; fun bar() = foo(1)")
        }

        // First build, should be stored into the build cache:
        build("assemble") {
            assertSuccessful()
            assertContains("Packing task ':compileKotlin'")
        }

        // A cache hit:
        build("clean", "assemble") {
            assertSuccessful()
            assertContains(":compileKotlin FROM-CACHE")
        }

        // Change the return type Int to String, so that bar.kt should be recompiled, and check that:
        fooKt.modify { it.replace("Int = 1", "String = \"abc\"") }
        build("assemble") {
            assertSuccessful()
            checkBytecodeContains(
                    File(projectDir, "build/classes/kotlin/main/bar/BarKt.class"),
                    "INVOKESTATIC foo/FooKt.foo (I)Ljava/lang/String;")
        }
    }

    @Test
    fun testKaptCachingDisabledByDefault() = with(Project("simple", GRADLE_VERSION, directoryPrefix = "kapt2")) {
        prepareLocalBuildCache()

        build("build") {
            assertSuccessful()
            assertContains("Packing task ':kaptGenerateStubsKotlin'")
            assertNotContains("Packing task ':kaptKotlin'")
            assertContains("Caching disabled for task ':kaptKotlin': 'Caching is disabled by default for kapt")
        }

        File(projectDir, "build.gradle").appendText("\n" + """
            afterEvaluate {
                kaptKotlin.useBuildCache = true
            }
            """.trimIndent())

        build("clean", "build") {
            assertSuccessful()
            assertContains(":kaptGenerateStubsKotlin FROM-CACHE")
            assertContains("Packing task ':kaptKotlin'")
        }

        build("clean", "build") {
            assertSuccessful()
            assertContains(":kaptGenerateStubsKotlin FROM-CACHE")
            assertContains(":kaptKotlin FROM-CACHE")
        }
    }

    @Test
    fun testCacheSameFiles() = testSameFiles(listOf(
            Project("simpleProject", GRADLE_VERSION),
            Project("simple", GRADLE_VERSION, directoryPrefix = "kapt2").apply {
                setupWorkingDir()
                File(projectDir, "build.gradle").appendText("\nafterEvaluate { kaptKotlin.useBuildCache = true }")
            }))

    private fun testSameFiles(projects: List<Project>) {
        projects.forEach { project ->
            try {
                project.prepareLocalBuildCache()

                lateinit var nonCacheOutputHashes: Map<File, Int>

                project.build("build") {
                    assertSuccessful()
                    nonCacheOutputHashes = getOutputFiles(File(project.projectDir, "build"))

                }

                project.build("clean", "build") {
                    assertSuccessful()
                    val outputFromCacheHashes = getOutputFiles(File(project.projectDir, "build"))
                    assertEquals(nonCacheOutputHashes, outputFromCacheHashes)
                }
            }
            catch (e: AssertionError) {
                throw AssertionError("Failed for project ${project.projectName}", e)
            }
        }
    }

    private fun Project.prepareLocalBuildCache() {
        if (!projectDir.exists()) {
            setupWorkingDir()
        }
        val newCacheDirPath = Files.createTempDirectory("GradleTestBuildCache").toFile().absolutePath.replace("\\", "/")
        File(projectDir, "settings.gradle").appendText("\nbuildCache.local.directory = '$newCacheDirPath'")
    }

    private val outputExtensions = setOf("java", "kt", "class", "kotlin_module")

    private fun getOutputFiles(directory: File) = directory.walkTopDown()
            .filter { it.extension in outputExtensions }
            .associate { it to it.readBytes().contentHashCode() }
}