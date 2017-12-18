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

package org.jetbrains.kotlin.android.compat.gradle

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.ResolvedArtifact
import org.gradle.api.internal.AbstractTask
import org.gradle.api.tasks.SourceSet
import org.gradle.api.tasks.compile.AbstractCompile
import org.jetbrains.kotlin.gradle.plugin.KotlinGradleSubplugin
import org.jetbrains.kotlin.gradle.plugin.SubpluginOption
import java.io.File

class AndroidCompatGradleSubplugin : Plugin<Project> {
    companion object {
        fun isEnabled(project: Project) = project.plugins.findPlugin(AndroidCompatGradleSubplugin::class.java) != null
    }

    fun Project.getBuildscriptArtifacts(): Set<ResolvedArtifact> =
            buildscript.configurations.findByName("classpath")?.resolvedConfiguration?.resolvedArtifacts ?: emptySet()

    override fun apply(project: Project) {
        project.afterEvaluate {
            val allBuildscriptArtifacts = project.getBuildscriptArtifacts() + project.rootProject.getBuildscriptArtifacts()
            val compilerPlugin = allBuildscriptArtifacts.filter {
                val id = it.moduleVersion.id
                id.group == AndroidCompatKotlinGradleSubplugin.ANDROID_COMPAT_GROUP_NAME &&
                it.name == AndroidCompatKotlinGradleSubplugin.ANDROID_COMPAT_ARTIFACT_NAME
            }.firstOrNull()?.file?.absolutePath ?: ""

            open class TaskForAndroidCompat : AbstractTask()
            project.tasks.add(project.tasks.create("replaceCompats", TaskForAndroidCompat::class.java).apply {
                isEnabled = true
                description = compilerPlugin
            })
        }
    }
}

class AndroidCompatKotlinGradleSubplugin : KotlinGradleSubplugin<AbstractCompile> {
    companion object {
        val ANDROID_COMPAT_GROUP_NAME = "org.jetbrains.kotlin"
        val ANDROID_COMPAT_ARTIFACT_NAME = "kotlin-android-compat"
    }

    override fun isApplicable(project: Project, task: AbstractCompile) = AndroidCompatGradleSubplugin.isEnabled(project)

    override fun apply(
            project: Project,
            kotlinCompile: AbstractCompile,
            javaCompile: AbstractCompile,
            variantData: Any?,
            androidProjectHandler: Any?,
            javaSourceSet: SourceSet?
    ): List<SubpluginOption> = emptyList()

    override fun getGroupName() = ANDROID_COMPAT_GROUP_NAME
    override fun getArtifactName() = ANDROID_COMPAT_ARTIFACT_NAME

    override fun getCompilerPluginId() = "org.jetbrains.kotlin.android.compat"
}