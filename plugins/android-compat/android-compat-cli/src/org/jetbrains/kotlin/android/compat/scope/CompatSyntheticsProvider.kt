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

package org.jetbrains.kotlin.android.compat.scope

import org.jetbrains.kotlin.descriptors.*
import org.jetbrains.kotlin.descriptors.annotations.Annotations
import org.jetbrains.kotlin.descriptors.impl.SimpleFunctionDescriptorImpl
import org.jetbrains.kotlin.descriptors.impl.ValueParameterDescriptorImpl
import org.jetbrains.kotlin.descriptors.synthetic.SyntheticMemberDescriptor
import org.jetbrains.kotlin.incremental.components.LookupLocation
import org.jetbrains.kotlin.incremental.components.NoLookupLocation
import org.jetbrains.kotlin.load.java.descriptors.JavaMethodDescriptor
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.resolve.annotations.argumentValue
import org.jetbrains.kotlin.resolve.descriptorUtil.module
import org.jetbrains.kotlin.resolve.scopes.*
import org.jetbrains.kotlin.storage.StorageManager
import org.jetbrains.kotlin.synthetic.extensions.SyntheticScopeProviderExtension
import org.jetbrains.kotlin.types.KotlinType
import org.jetbrains.kotlin.types.checker.KotlinTypeChecker
import org.jetbrains.kotlin.types.typeUtil.isInterface
import kotlin.properties.Delegates

interface CompatSyntheticFunctionDescriptor : FunctionDescriptor, SyntheticMemberDescriptor<FunctionDescriptor>

val compatAnnotationFqName = FqName("kotlin.annotations.jvm.internal.Compat")

private data class OriginAndCompat(
        val origin: ClassDescriptor,
        val compat: ClassDescriptor?
)

private abstract class CompatSyntheticResolutionScope(
        storageManager: StorageManager,
        protected val ownerClass: ClassDescriptor,
        protected val type: KotlinType
) : AbstractResolutionScopeAdapter() {
    protected val compats = storageManager.createLazyValue {
        doGetCompats()
    }

    private fun doGetCompats(): Collection<OriginAndCompat> {
        return (type.constructor.supertypes + type)
                .filterNot { it.isInterface() }
                .mapNotNull {
                    OriginAndCompat(
                            it.constructor.declarationDescriptor as ClassDescriptor,
                            (it.constructor.declarationDescriptor as? ClassDescriptor)?.findCompat()
                    )
                }
    }

    private fun ClassDescriptor.findCompat(): ClassDescriptor? {
        val annotation = annotations.firstOrNull { it.fqName == compatAnnotationFqName } ?: return null
        val annotationValue = annotation.argumentValue("value") ?: error("Compat annotation must have value")
        val valueString = annotationValue as? String ?: error("$annotationValue must be string")

        var packageName = ""
        var className = valueString
        if (valueString.contains('.')) {
            packageName = valueString.substring(0, valueString.lastIndexOf('.'))
            className = valueString.substring(valueString.lastIndexOf('.') + 1)
        }

        return ownerClass.module.getPackage(FqName(packageName))
                .memberScope
                .getContributedClassifier(
                        Name.identifier(className),
                        NoLookupLocation.FROM_SYNTHETIC_SCOPE
                ) as? ClassDescriptor
    }
}

private class CompatSyntheticMemberFunctionsScope(
        storageManager: StorageManager,
        override val workerScope: ResolutionScope,
        ownerClass: ClassDescriptor,
        type: KotlinType
) : CompatSyntheticResolutionScope(storageManager, ownerClass, type) {
    private val functions = storageManager.createMemoizedFunction<Name, Collection<FunctionDescriptor>> {
        shadowOriginalFunctions(super.getContributedFunctions(it, NoLookupLocation.FROM_SYNTHETIC_SCOPE), doGetFunctions(it))
    }

    private fun doGetFunctions(name: Name): Collection<FunctionDescriptor> {
        return compats().flatMap { (origin, compat) ->
            if (compat == null) return@flatMap emptyList<FunctionDescriptor>()
            val compatFunctions = compat.staticScope.getContributedFunctions(name, NoLookupLocation.FROM_SYNTHETIC_SCOPE)
            val visibleCompatFunctions = compatFunctions.filter { it.visibility == Visibilities.PUBLIC }
            val visibleCompatFunctionsWhichTakeOriginAsFistParam = visibleCompatFunctions.filter {
                it.valueParameters.size > 0 && KotlinTypeChecker.DEFAULT.isSubtypeOf(type, it.valueParameters.first().type)
            }
            val synthetic = visibleCompatFunctionsWhichTakeOriginAsFistParam.map { CompatMemberFunctionDescriptor.create(origin, it) }
            synthetic
        }
    }

    override fun getContributedFunctions(name: Name, location: LookupLocation): Collection<FunctionDescriptor> {
        return functions(name)
    }

    // Looks like origin function, acts like compat function
    private class CompatMemberFunctionDescriptor(
            containingDeclaration: DeclarationDescriptor,
            original: SimpleFunctionDescriptor?,
            annotations: Annotations,
            name: Name,
            kind: CallableMemberDescriptor.Kind,
            source: SourceElement
    ) :
            SimpleFunctionDescriptorImpl(containingDeclaration, original, annotations, name, kind, source),
            CompatSyntheticFunctionDescriptor {
        override var baseDescriptorForSynthetic: FunctionDescriptor by Delegates.notNull()
            private set

        companion object {
            fun create(ownerClass: ClassDescriptor, compat: FunctionDescriptor): CompatMemberFunctionDescriptor {
                val result = CompatMemberFunctionDescriptor(
                        ownerClass,
                        null,
                        compat.annotations,
                        compat.name,
                        CallableMemberDescriptor.Kind.SYNTHESIZED,
                        compat.original.source
                )
                result.baseDescriptorForSynthetic = compat
                val valueParams = compat.valueParameters.drop(1).map {
                    ValueParameterDescriptorImpl(
                            it.containingDeclaration,
                            it,
                            it.index - 1,
                            it.annotations,
                            it.name,
                            it.type,
                            it.declaresDefaultValue(),
                            it.isCrossinline,
                            it.isNoinline,
                            it.varargElementType,
                            it.source
                    )
                }
                result.initialize(
                        null,
                        ownerClass.thisAsReceiverParameter,
                        compat.typeParameters,
                        valueParams,
                        compat.returnType,
                        compat.modality,
                        compat.visibility
                )
                return result
            }
        }
    }
}

private class CompatSyntheticStaticScope(
        storageManager: StorageManager,
        override val workerScope: ResolutionScope,
        ownerClass: ClassDescriptor,
        type: KotlinType
) : CompatSyntheticResolutionScope(storageManager, ownerClass, type) {
    private val functions = storageManager.createMemoizedFunction<Name, Collection<FunctionDescriptor>> {
        shadowOriginalFunctions(super.getContributedFunctions(it, NoLookupLocation.FROM_SYNTHETIC_SCOPE), doGetFunctions(it))
    }

    private val fields = storageManager.createMemoizedFunction<Name, Collection<VariableDescriptor>> {
        shadowOriginalFields(super.getContributedVariables(it, NoLookupLocation.FROM_SYNTHETIC_SCOPE), doGetFields(it))
    }

    private fun doGetFields(name: Name): Collection<VariableDescriptor> {
        return compats().flatMap { (_, compat) ->
            if (compat == null) return@flatMap emptyList<VariableDescriptor>()
            compat.staticScope.getContributedVariables(name, NoLookupLocation.FROM_SYNTHETIC_SCOPE)
                    .filter { it.visibility == Visibilities.PUBLIC }
        }
    }

    private fun shadowOriginalFields(
            originals: Collection<VariableDescriptor>,
            synthetics: Collection<VariableDescriptor>
    ): Collection<VariableDescriptor> {
        val res = arrayListOf<VariableDescriptor>()
        for (original in originals) {
            var replaced = false
            for (synthetic in synthetics) {
                if (synthetic.name == original.name && KotlinTypeChecker.DEFAULT.equalTypes(synthetic.type, original.type)) {
                    res.add(synthetic)
                    replaced = true
                    break
                }
            }
            if (!replaced) {
                res.add(original)
            }
        }
        return res
    }

    private fun doGetFunctions(name: Name): Collection<FunctionDescriptor> {
        return compats().flatMap { (origin, compat) ->
            if (compat == null) return@flatMap emptyList<FunctionDescriptor>()
            val compatFunctions = compat.staticScope.getContributedFunctions(name, NoLookupLocation.FROM_SYNTHETIC_SCOPE)
                    .filter { it.visibility == Visibilities.PUBLIC }
            val compatFunctionsWithoutOriginAsFistParam = compatFunctions.filterNot {
                it.valueParameters.size > 0 && KotlinTypeChecker.DEFAULT.isSubtypeOf(origin.defaultType, it.valueParameters.first().type)
            }
            compatFunctionsWithoutOriginAsFistParam.map { CompatStaticFunctionDescriptor.create(origin, it) }
        }
    }

    override fun getContributedFunctions(name: Name, location: LookupLocation): Collection<FunctionDescriptor> {
        return functions(name)
    }

    override fun getContributedVariables(name: Name, location: LookupLocation): Collection<VariableDescriptor> {
        return fields(name)
    }

    private class CompatStaticFunctionDescriptor(
            containingDeclaration: DeclarationDescriptor,
            original: SimpleFunctionDescriptor?,
            annotations: Annotations,
            name: Name,
            kind: CallableMemberDescriptor.Kind,
            source: SourceElement
    ) :
            JavaMethodDescriptor(containingDeclaration, original, annotations, name, kind, source), //Kinda gross, but SAM Adapter expects JavaMethodDescriptor
            CompatSyntheticFunctionDescriptor {
        override var baseDescriptorForSynthetic: FunctionDescriptor by Delegates.notNull()
            private set

        companion object {
            fun create(ownerClass: ClassDescriptor, compat: FunctionDescriptor): CompatStaticFunctionDescriptor {
                val result = CompatStaticFunctionDescriptor(
                        ownerClass,
                        null,
                        compat.annotations,
                        compat.name,
                        CallableMemberDescriptor.Kind.SYNTHESIZED,
                        compat.original.source
                )
                result.setParameterNamesStatus(compat.hasStableParameterNames(), compat.hasSynthesizedParameterNames())
                result.baseDescriptorForSynthetic = compat
                result.initialize(
                        null,
                        null,
                        compat.typeParameters,
                        compat.valueParameters,
                        compat.returnType,
                        compat.modality,
                        compat.visibility
                )
                return result
            }
        }
    }
}

class CompatSyntheticsProvider(private val storageManager: StorageManager) : SyntheticScopeProvider {
    override fun provideSyntheticScope(scope: ResolutionScope, requirements: SyntheticScopesRequirements): ResolutionScope {
        val descriptor = scope.getContributedDescriptors().firstOrNull() ?: return scope
        val ownerClass = descriptor.containingDeclaration as? ClassDescriptor ?: return scope
        val type = ownerClass.defaultType as? KotlinType ?: return scope
        return when {
        // The property can be generated from getters, thus, we should provide a scope for them
            requirements.needMemberFunctions || requirements.needExtensionProperties ->
                CompatSyntheticMemberFunctionsScope(storageManager, scope, ownerClass, type)
            requirements.needStaticFunctions || requirements.needStaticFields ->
                CompatSyntheticStaticScope(storageManager, scope, ownerClass, type)
            else -> scope
        }
    }
}

object CompatSyntheticsProviderExtension : SyntheticScopeProviderExtension {
    override fun getProvider(storageManager: StorageManager): SyntheticScopeProvider = CompatSyntheticsProvider(storageManager)
}

private fun shadowOriginalFunctions(
        originals: Collection<FunctionDescriptor>,
        synthetics: Collection<FunctionDescriptor>
): Collection<FunctionDescriptor> {
    val res = arrayListOf<FunctionDescriptor>()
    for (original in originals) {
        var replaced = false
        for (synthetic in synthetics) {
            if (synthetic.shadows(original)) {
                res.add(synthetic)
                replaced = true
                break
            }
        }
        if (!replaced) {
            res.add(original)
        }
    }
    return res
}

private fun FunctionDescriptor.shadows(other: FunctionDescriptor): Boolean {
    if (name != other.name) return false
    if (valueParameters.size != other.valueParameters.size) return false
    if (returnType == null) {
        if (other.returnType != null) return false
    }
    else {
        if (other.returnType == null) return false
        if (!KotlinTypeChecker.DEFAULT.equalTypes(returnType!!, other.returnType!!)) return false
    }
    for (i in 0 until valueParameters.size) {
        if (!KotlinTypeChecker.DEFAULT.equalTypes(valueParameters[i].type, other.valueParameters[i].type)) return false
    }
    return true
}