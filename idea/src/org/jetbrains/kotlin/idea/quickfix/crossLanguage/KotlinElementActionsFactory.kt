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

package org.jetbrains.kotlin.idea.quickfix.crossLanguage

import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.codeInsight.intention.QuickFixFactory
import com.intellij.lang.jvm.*
import com.intellij.lang.jvm.actions.*
import com.intellij.openapi.project.Project
import com.intellij.psi.*
import com.intellij.psi.impl.source.tree.java.PsiReferenceExpressionImpl
import org.jetbrains.kotlin.asJava.classes.KtLightClassForFacade
import org.jetbrains.kotlin.asJava.classes.KtLightClassForSourceDeclaration
import org.jetbrains.kotlin.asJava.toLightMethods
import org.jetbrains.kotlin.asJava.unwrapped
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.descriptors.SourceElement
import org.jetbrains.kotlin.descriptors.TypeParameterDescriptor
import org.jetbrains.kotlin.descriptors.impl.ClassDescriptorImpl
import org.jetbrains.kotlin.descriptors.impl.MutablePackageFragmentDescriptor
import org.jetbrains.kotlin.idea.caches.resolve.getResolutionFacade
import org.jetbrains.kotlin.idea.core.CollectingNameValidator
import org.jetbrains.kotlin.idea.core.KotlinNameSuggester
import org.jetbrains.kotlin.idea.quickfix.AddModifierFix
import org.jetbrains.kotlin.idea.quickfix.RemoveModifierFix
import org.jetbrains.kotlin.idea.quickfix.createFromUsage.callableBuilder.*
import org.jetbrains.kotlin.idea.quickfix.createFromUsage.createCallable.CreateCallableFromUsageFix
import org.jetbrains.kotlin.idea.resolve.ResolutionFacade
import org.jetbrains.kotlin.lexer.KtModifierKeywordToken
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.load.java.components.TypeUsage
import org.jetbrains.kotlin.load.java.lazy.JavaResolverComponents
import org.jetbrains.kotlin.load.java.lazy.LazyJavaResolverContext
import org.jetbrains.kotlin.load.java.lazy.TypeParameterResolver
import org.jetbrains.kotlin.load.java.lazy.child
import org.jetbrains.kotlin.load.java.lazy.descriptors.LazyJavaTypeParameterDescriptor
import org.jetbrains.kotlin.load.java.lazy.types.JavaTypeAttributes
import org.jetbrains.kotlin.load.java.lazy.types.JavaTypeResolver
import org.jetbrains.kotlin.load.java.structure.JavaTypeParameter
import org.jetbrains.kotlin.load.java.structure.impl.JavaTypeImpl
import org.jetbrains.kotlin.load.java.structure.impl.JavaTypeParameterImpl
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtModifierListOwner
import org.jetbrains.kotlin.types.KotlinType
import org.jetbrains.kotlin.types.Variance
import org.jetbrains.kotlin.types.typeUtil.supertypes
import org.jetbrains.uast.UElement

class KotlinElementActionsFactory : JvmElementActionsFactory() {
    companion object {
        val javaPsiModifiersMapping = mapOf(
                JvmModifier.PRIVATE to KtTokens.PRIVATE_KEYWORD,
                JvmModifier.PUBLIC to KtTokens.PUBLIC_KEYWORD,
                JvmModifier.PROTECTED to KtTokens.PUBLIC_KEYWORD,
                JvmModifier.ABSTRACT to KtTokens.ABSTRACT_KEYWORD
        )
    }

    private class FakeExpressionFromParameter(private val psiParam: PsiParameter) : PsiReferenceExpressionImpl() {
        override fun getText(): String = psiParam.name!!
        override fun getProject(): Project = psiParam.project
        override fun getParent(): PsiElement = psiParam.parent
        override fun getType(): PsiType? = psiParam.type
        override fun isValid(): Boolean = true
        override fun getContainingFile(): PsiFile = psiParam.containingFile
        override fun getReferenceName(): String? = psiParam.name
        override fun resolve(): PsiElement? = psiParam
    }

    private fun JvmElement.unwrapUAST(): PsiElement? {
        val sourceElement = sourceElement
        return if (sourceElement is UElement) sourceElement.psi else sourceElement
    }

    private fun JvmClass.toKtClassOrFile(): KtElement? {
        val psi = unwrapUAST()
        return when (psi) {
            is KtClassOrObject -> psi
            is KtLightClassForSourceDeclaration -> psi.kotlinOrigin
            is KtLightClassForFacade -> psi.files.firstOrNull()
            else -> null
        }
    }

    private inline fun <reified T : KtElement> JvmElement.toKtElement() = unwrapUAST()?.unwrapped as? T

    private fun fakeParametersExpressions(parameters: List<JvmParameter>, project: Project): Array<PsiExpression>? =
            when {
                parameters.isEmpty() -> emptyArray()
                else -> JavaPsiFacade
                        .getElementFactory(project)
                        .createParameterList(
                                parameters.map { it.name }.toTypedArray(),
                                parameters.map { it.type as? PsiType ?: return null }.toTypedArray()
                        )
                        .parameters
                        .map(::FakeExpressionFromParameter)
                        .toTypedArray()
            }

    private fun PsiType.collectTypeParameters(): List<PsiTypeParameter> {
        val results = ArrayList<PsiTypeParameter>()
        accept(
                object : PsiTypeVisitor<Unit>() {
                    override fun visitArrayType(arrayType: PsiArrayType) {
                        arrayType.componentType.accept(this)
                    }

                    override fun visitClassType(classType: PsiClassType) {
                        (classType.resolve() as? PsiTypeParameter)?.let { results += it }
                        classType.parameters.forEach { it.accept(this) }
                    }

                    override fun visitWildcardType(wildcardType: PsiWildcardType) {
                        wildcardType.bound?.accept(this)
                    }
                }
        )
        return results
    }

    private fun PsiType.resolveToKotlinType(resolutionFacade: ResolutionFacade): KotlinType? {
        val typeParameters = collectTypeParameters()
        val components = resolutionFacade.getFrontendService(JavaResolverComponents::class.java)
        val rootContext = LazyJavaResolverContext(components, TypeParameterResolver.EMPTY) { null }
        val dummyPackageDescriptor = MutablePackageFragmentDescriptor(resolutionFacade.moduleDescriptor, FqName("dummy"))
        val dummyClassDescriptor = ClassDescriptorImpl(
                dummyPackageDescriptor,
                Name.identifier("Dummy"),
                Modality.FINAL,
                ClassKind.CLASS,
                emptyList(),
                SourceElement.NO_SOURCE,
                false
        )
        val typeParameterResolver = object : TypeParameterResolver {
            override fun resolveTypeParameter(javaTypeParameter: JavaTypeParameter): TypeParameterDescriptor? {
                val psiTypeParameter = (javaTypeParameter as JavaTypeParameterImpl).psi
                val index = typeParameters.indexOf(psiTypeParameter)
                if (index < 0) return null
                return LazyJavaTypeParameterDescriptor(rootContext.child(this), javaTypeParameter, index, dummyClassDescriptor)
            }
        }
        val typeResolver = JavaTypeResolver(rootContext, typeParameterResolver)
        val attributes = JavaTypeAttributes(TypeUsage.COMMON)
        return typeResolver.transformJavaType(JavaTypeImpl.create(this), attributes)
    }

    private fun ExpectedTypes.toKotlinTypeInfo(resolutionFacade: ResolutionFacade): TypeInfo {
        val candidateTypes = flatMapTo(LinkedHashSet<KotlinType>()) {
            val ktType = (it.theType as? PsiType)?.resolveToKotlinType(resolutionFacade) ?: return@flatMapTo emptyList()
            when (it.theKind) {
                ExpectedType.Kind.EXACT, ExpectedType.Kind.SUBTYPE -> listOf(ktType)
                ExpectedType.Kind.SUPERTYPE -> listOf(ktType) + ktType.supertypes()
            }
        }
        if (candidateTypes.isEmpty()) {
            val nullableAnyType = resolutionFacade.moduleDescriptor.builtIns.nullableAnyType
            return TypeInfo(nullableAnyType, Variance.INVARIANT)
        }
        return TypeInfo.ByExplicitCandidateTypes(candidateTypes.toList())
    }

    private fun getKtModifier(jvmModifier: JvmModifier, shouldPresent: Boolean): Pair<KtModifierKeywordToken, Boolean>? {
        return if (JvmModifier.FINAL == jvmModifier)
            KtTokens.OPEN_KEYWORD to !shouldPresent
        else
            javaPsiModifiersMapping[jvmModifier]?.let { it to shouldPresent }
    }

    override fun createChangeModifierActions(target: JvmModifiersOwner, request: MemberRequest.Modifier): List<IntentionAction> {
        val kModifierOwner = target.toKtElement<KtModifierListOwner>() ?: return emptyList()

        val modifier = request.modifier
        val shouldPresent = request.shouldPresent
        val (kToken, shouldPresentMapped) = getKtModifier(modifier, shouldPresent) ?: return emptyList()

        val action = if (shouldPresentMapped)
            AddModifierFix.createIfApplicable(kModifierOwner, kToken)
        else
            RemoveModifierFix(kModifierOwner, kToken, false)
        return listOfNotNull(action)
    }

    override fun createAddConstructorActions(targetClass: JvmClass, request: MemberRequest.Constructor): List<IntentionAction> {
        val targetContainer = targetClass.toKtClassOrFile() as? KtClass ?: return emptyList()

        if (request.typeParameters.isNotEmpty()) return emptyList()

        val project = targetContainer.project

        val resolutionFacade = targetContainer.getResolutionFacade()
        val nullableAnyType = resolutionFacade.moduleDescriptor.builtIns.nullableAnyType
        val nameValidator = CollectingNameValidator()
        val parameterInfos = request.parameters.map { param ->
            val ktType = (param.type as? PsiType)?.resolveToKotlinType(resolutionFacade) ?: nullableAnyType
            val names = KotlinNameSuggester.suggestNamesByType(ktType, nameValidator, "p")
            ParameterInfo(TypeInfo(ktType, Variance.IN_VARIANCE), names)
        }
        val constructorInfo = ConstructorInfo(
                parameterInfos,
                targetContainer,
                isPrimary = !targetContainer.hasExplicitPrimaryConstructor(),
                isFromJava = true
        )
        val addConstructorAction = CreateCallableFromUsageFix(targetContainer, listOf(constructorInfo))

        val changePrimaryConstructorAction = run {
            val primaryConstructor = targetContainer.primaryConstructor ?: return@run null
            val lightMethod = primaryConstructor.toLightMethods().firstOrNull() ?: return@run null
            val fakeParametersExpressions = fakeParametersExpressions(request.parameters, project) ?: return@run null
            QuickFixFactory.getInstance()
                    .createChangeMethodSignatureFromUsageFix(
                            lightMethod,
                            fakeParametersExpressions,
                            PsiSubstitutor.EMPTY,
                            targetContainer,
                            false,
                            2
                    ).takeIf { it.isAvailable(project, null, targetContainer.containingFile) }
        }

        return listOfNotNull(changePrimaryConstructorAction, addConstructorAction)
    }

    override fun createAddPropertyActions(targetClass: JvmClass, request: MemberRequest.Property): List<IntentionAction> {
        val targetContainer = targetClass.toKtClassOrFile() ?: return emptyList()
        val resolutionFacade = targetContainer.getResolutionFacade()
        val nullableAnyType = resolutionFacade.moduleDescriptor.builtIns.nullableAnyType
        val ktType = (request.propertyType as? PsiType)?.resolveToKotlinType(resolutionFacade) ?: nullableAnyType
        val propertyInfo = PropertyInfo(
                request.propertyName,
                TypeInfo.Empty,
                TypeInfo(ktType, Variance.INVARIANT),
                request.setterRequired,
                listOf(targetContainer),
                isFromJava = true
        )
        return listOf(CreateCallableFromUsageFix(targetContainer, listOf(propertyInfo)))
    }

    override fun createAddFieldActions(targetClass: JvmClass, request: CreateFieldRequest): List<IntentionAction> {
        val targetContainer = targetClass.toKtClassOrFile() ?: return emptyList()
        val resolutionFacade = targetContainer.getResolutionFacade()
        val typeInfo = request.fieldType.toKotlinTypeInfo(resolutionFacade)
        val propertyInfo = PropertyInfo(
                request.fieldName,
                TypeInfo.Empty,
                typeInfo,
                JvmModifier.FINAL !in request.modifiers,
                listOf(targetContainer),
                isForCompanion = JvmModifier.STATIC in request.modifiers,
                isFromJava = true
        )
        return listOf(CreateCallableFromUsageFix(targetContainer, listOf(propertyInfo)))
    }

    override fun createAddMethodActions(targetClass: JvmClass, request: CreateMethodRequest): List<IntentionAction> {
        val targetContainer = targetClass.toKtClassOrFile() ?: return emptyList()
        val resolutionFacade = targetContainer.getResolutionFacade()
        val returnTypeInfo = request.returnType.toKotlinTypeInfo(resolutionFacade)
        val parameterInfos = request.parameters.map { (suggestedNames, expectedTypes) ->
            ParameterInfo(expectedTypes.toKotlinTypeInfo(resolutionFacade), suggestedNames.names.toList())
        }
        val functionInfo = FunctionInfo(
                request.methodName,
                TypeInfo.Empty,
                returnTypeInfo,
                listOf(targetContainer),
                parameterInfos,
                isAbstract = JvmModifier.ABSTRACT in request.modifiers,
                isForCompanion = JvmModifier.STATIC in request.modifiers,
                isFromJava = true
        )
        return listOf(CreateCallableFromUsageFix(targetContainer, listOf(functionInfo)))
    }
}