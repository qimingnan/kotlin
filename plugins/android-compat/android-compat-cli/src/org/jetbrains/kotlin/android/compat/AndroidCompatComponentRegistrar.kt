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

package org.jetbrains.kotlin.android.compat

import com.intellij.mock.MockProject
import com.intellij.openapi.project.Project
import org.jetbrains.kotlin.android.compat.AndroidCompatConfigurationKeys.ANNOTATION
import org.jetbrains.kotlin.android.compat.codegen.CompatExpressionCodegenExtension
import org.jetbrains.kotlin.android.compat.scope.CompatSyntheticsProviderExtension
import org.jetbrains.kotlin.codegen.extensions.ExpressionCodegenExtension
import org.jetbrains.kotlin.compiler.plugin.CliOption
import org.jetbrains.kotlin.compiler.plugin.CliOptionProcessingException
import org.jetbrains.kotlin.compiler.plugin.CommandLineProcessor
import org.jetbrains.kotlin.compiler.plugin.ComponentRegistrar
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.config.CompilerConfigurationKey
import org.jetbrains.kotlin.synthetic.extensions.SyntheticScopeProviderExtension

object AndroidCompatConfigurationKeys {
    val ANNOTATION: CompilerConfigurationKey<List<String>> =
            CompilerConfigurationKey.create("annotation qualified name")
}

class AndroidCompatCommandLineProcessor : CommandLineProcessor {
    override val pluginId = ANDROID_COMPAT_COMPILER_PLUGIN_ID
    override val pluginOptions: Collection<CliOption> = listOf(ANNOTATION_OPTION)

    override fun processOption(option: CliOption, value: String, configuration: CompilerConfiguration) = when (option) {
        ANNOTATION_OPTION -> configuration.appendList(ANNOTATION, value)
        else -> throw CliOptionProcessingException("Unknown option: ${option.name}")
    }

    companion object {
        val ANDROID_COMPAT_COMPILER_PLUGIN_ID: String = "org.jetbrains.kotlin.android.compat"
        val ANNOTATION_OPTION = CliOption("annotation", "<fqname>", "Annotation qualified names",
                                          required = false, allowMultipleOccurrences = true)
    }
}

class AndroidCompatComponentRegistrar : ComponentRegistrar {
    override fun registerProjectComponents(project: MockProject, configuration: CompilerConfiguration) {
        registerExtensions(project)
        // TODO: add targetApi version check
    }

    companion object {
        fun registerExtensions(project: Project) {
            SyntheticScopeProviderExtension.registerExtension(project, CompatSyntheticsProviderExtension)
            ExpressionCodegenExtension.registerExtension(project, CompatExpressionCodegenExtension)
        }
    }
}