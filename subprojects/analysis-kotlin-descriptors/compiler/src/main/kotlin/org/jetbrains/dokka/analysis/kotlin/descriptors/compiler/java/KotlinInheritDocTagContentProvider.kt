/*
 * Copyright 2014-2023 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.dokka.analysis.kotlin.descriptors.compiler.java

import org.jetbrains.dokka.analysis.java.doccomment.DocumentationContent
import org.jetbrains.dokka.analysis.java.JavaAnalysisPlugin
import org.jetbrains.dokka.analysis.java.parsers.doctag.DocTagParserContext
import org.jetbrains.dokka.analysis.java.parsers.doctag.InheritDocTagContentProvider
import org.jetbrains.dokka.plugability.DokkaContext
import org.jetbrains.dokka.plugability.plugin
import org.jetbrains.dokka.plugability.query

internal class KotlinInheritDocTagContentProvider(
    context: DokkaContext
) : InheritDocTagContentProvider {

    val parser: DescriptorKotlinDocCommentParser by lazy {
        context.plugin<JavaAnalysisPlugin>().query { docCommentParsers }
            .single { it is DescriptorKotlinDocCommentParser } as DescriptorKotlinDocCommentParser
    }

    override fun canConvert(content: DocumentationContent): Boolean = content is DescriptorDocumentationContent

    override fun convertToHtml(content: DocumentationContent, docTagParserContext: DocTagParserContext): String {
        val descriptorContent = content as DescriptorDocumentationContent
        val inheritedDocNode = parser.parseDocumentation(
            DescriptorKotlinDocComment(descriptorContent.element, descriptorContent.descriptor),
            parseWithChildren = false
        )
        val id = docTagParserContext.store(inheritedDocNode)
        return """<inheritdoc id="$id"/>"""
    }
}
