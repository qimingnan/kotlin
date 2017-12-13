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
import com.intellij.lang.jvm.JvmElement
import com.intellij.lang.jvm.JvmModifier
import com.intellij.lang.jvm.JvmModifiersOwner
import com.intellij.lang.jvm.actions.JvmElementActionsFactory
import com.intellij.lang.jvm.actions.MemberRequest
import org.jetbrains.kotlin.asJava.unwrapped
import org.jetbrains.kotlin.idea.quickfix.AddModifierFix
import org.jetbrains.kotlin.idea.quickfix.RemoveModifierFix
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtModifierListOwner
import org.jetbrains.uast.UElement

class KotlinElementActionsFactory : JvmElementActionsFactory() {
    companion object {
        val javaPsiModifiersMapping = mapOf(
                JvmModifier.PRIVATE to KtTokens.PRIVATE_KEYWORD,
                JvmModifier.PUBLIC to KtTokens.PUBLIC_KEYWORD,
                JvmModifier.PROTECTED to KtTokens.PUBLIC_KEYWORD,
                JvmModifier.ABSTRACT to KtTokens.ABSTRACT_KEYWORD
        )

        private inline fun <reified T : KtElement> JvmElement.toKtElement(): T? {
            val sourceElement = sourceElement
            return when {
                sourceElement is T -> sourceElement
                sourceElement is UElement -> sourceElement.psi?.unwrapped as? T
                else -> null
            }
        }
    }

    override fun createChangeModifierActions(target: JvmModifiersOwner, request: MemberRequest.Modifier): List<IntentionAction> {
        val kModifierOwner = target.toKtElement<KtModifierListOwner>() ?: return emptyList()

        val modifier = request.modifier
        val shouldPresent = request.shouldPresent
        val (kToken, shouldPresentMapped) = if (JvmModifier.FINAL == modifier)
            KtTokens.OPEN_KEYWORD to !shouldPresent
        else
            javaPsiModifiersMapping[modifier] to shouldPresent

        if (kToken == null) return emptyList()
        val action = if (shouldPresentMapped)
            AddModifierFix.createIfApplicable(kModifierOwner, kToken)
        else
            RemoveModifierFix(kModifierOwner, kToken, false)
        return listOfNotNull(action)
    }
}