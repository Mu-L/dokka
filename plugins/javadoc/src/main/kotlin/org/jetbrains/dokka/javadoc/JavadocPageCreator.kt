/*
 * Copyright 2014-2023 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.dokka.javadoc

import org.jetbrains.dokka.DokkaConfiguration.DokkaSourceSet
import org.jetbrains.dokka.base.DokkaBase
import org.jetbrains.dokka.base.signatures.SignatureProvider
import org.jetbrains.dokka.base.transformers.pages.comments.DocTagToContentConverter
import org.jetbrains.dokka.base.translators.documentables.firstSentenceBriefFromContentNodes
import org.jetbrains.dokka.javadoc.pages.*
import org.jetbrains.dokka.model.*
import org.jetbrains.dokka.model.doc.*
import org.jetbrains.dokka.model.properties.PropertyContainer
import org.jetbrains.dokka.model.properties.WithExtraProperties
import org.jetbrains.dokka.pages.*
import org.jetbrains.dokka.plugability.DokkaContext
import org.jetbrains.dokka.plugability.plugin
import org.jetbrains.dokka.plugability.querySingle
import kotlin.reflect.KClass

public open class JavadocPageCreator(context: DokkaContext) {
    private val signatureProvider: SignatureProvider = context.plugin<DokkaBase>().querySingle { signatureProvider }
    private val documentationVersion = context.configuration.moduleVersion

    public fun pageForModule(m: DModule): JavadocModulePageNode {
        return JavadocModulePageNode(
            name = m.name.ifEmpty { "root" },
            content = contentForModule(m),
            children = m.packages.map { pageForPackage(it) },
            dri = setOf(m.dri),
            extra = ((m as? WithExtraProperties<DModule>)?.extra ?: PropertyContainer.empty())
        )
    }

    public fun pageForPackage(p: DPackage): JavadocPackagePageNode {
        return JavadocPackagePageNode(p.name, contentForPackage(p), setOf(p.dri), listOf(p),
            p.classlikes.mapNotNull { pageForClasslike(it) }
        )
    }

    public fun pageForClasslike(c: DClasslike): JavadocClasslikePageNode? {
        return c.highestJvmSourceSet?.let { jvm ->
            @Suppress("UNCHECKED_CAST")
            val extra = ((c as? WithExtraProperties<Documentable>)?.extra ?: PropertyContainer.empty())
            val children = c.classlikes.mapNotNull { pageForClasslike(it) }

            JavadocClasslikePageNode(
                name = c.dri.classNames.orEmpty(),
                content = contentForClasslike(c),
                dri = setOf(c.dri),
                brief = c.brief(),
                signature = signatureForNode(c, jvm),
                description = c.descriptionToContentNodes(),
                constructors = (c as? WithConstructors)?.constructors?.mapNotNull { it.toJavadocFunction() }.orEmpty(),
                methods = c.functions.mapNotNull { it.toJavadocFunction() },
                entries = (c as? DEnum)?.entries?.map {
                    JavadocEntryNode(
                        it.dri,
                        it.name,
                        signatureForNode(it, jvm),
                        it.descriptionToContentNodes(jvm),
                        PropertyContainer.withAll(it.indexesInDocumentation())
                    )
                }.orEmpty(),
                classlikes = children,
                properties = c.properties.map {
                    JavadocPropertyNode(
                        it.dri,
                        it.name,
                        signatureForNode(it, jvm),
                        it.descriptionToContentNodes(jvm),
                        PropertyContainer.withAll(it.indexesInDocumentation()),
                    )
                },
                sinceTagContent = c.sinceToContentNodes(jvm),
                authorTagContent = c.authorsToContentNodes(jvm),
                documentables = listOf(c),
                children = children,
                extra = extra + c.indexesInDocumentation()
            )
        }
    }

    private fun contentForModule(m: DModule): JavadocContentNode =
        JavadocContentGroup(
            setOf(m.dri),
            JavadocContentKind.OverviewSummary,
            m.sourceSets.toDisplaySourceSets()
        ) {
            title(m.name, m.descriptionToContentNodes(), documentationVersion, dri = setOf(m.dri), kind = ContentKind.Main)
            leafList(setOf(m.dri),
                ContentKind.Packages, JavadocList(
                    "Packages", "Package",
                    m.packages.sortedBy { it.packageName }.map { p ->
                        RowJavadocListEntry(
                            LinkJavadocListEntry(p.name, setOf(p.dri), JavadocContentKind.PackageSummary, sourceSets),
                            p.brief()
                        )
                    }
                ))
        }

    private fun contentForPackage(p: DPackage): JavadocContentNode =
        JavadocContentGroup(
            setOf(p.dri),
            JavadocContentKind.PackageSummary,
            p.sourceSets.toDisplaySourceSets()
        ) {
            title("Package ${p.name}", p.descriptionToContentNodes(), dri = setOf(p.dri), kind = ContentKind.Packages)
            fun allClasslikes(c: DClasslike): List<DClasslike> = c.classlikes.flatMap { allClasslikes(it) } + c
            val rootList = p.classlikes.map { allClasslikes(it) }.flatten().groupBy { it::class }.map { (key, value) ->
                JavadocList(key.tabTitle, key.colTitle, value.map { c ->
                    RowJavadocListEntry(
                        LinkJavadocListEntry(c.name ?: "", setOf(c.dri), JavadocContentKind.Class, sourceSets),
                        c.brief()
                    )
                })
            }
            rootList(setOf(p.dri), JavadocContentKind.Class, rootList)
        }

    private val KClass<out DClasslike>.colTitle: String
        get() = when (this) {
            DClass::class -> "Class"
            DObject::class -> "Object"
            DAnnotation::class -> "Annotation"
            DEnum::class -> "Enum"
            DInterface::class -> "Interface"
            else -> ""
        }

    private val KClass<out DClasslike>.tabTitle: String
        get() = "$colTitle Summary"

    private fun contentForClasslike(c: DClasslike): JavadocContentNode =
        JavadocContentGroup(
            setOf(c.dri),
            JavadocContentKind.Class,
            c.sourceSets.toDisplaySourceSets()
        ) {
            title(
                c.name.orEmpty(),
                c.brief(),
                documentationVersion,
                parent = c.dri.packageName,
                dri = setOf(c.dri),
                kind = JavadocContentKind.Class
            )
        }

    private fun DFunction.toJavadocFunction() = highestJvmSourceSet?.let { jvm ->
        JavadocFunctionNode(
            name = name,
            dri = dri,
            signature = signatureForNode(this, jvm),
            brief = brief(jvm),
            description = descriptionToContentNodes(jvm),
            parameters = parameters.mapNotNull {
                val signature = signatureForNode(it, jvm)
                signature.modifiers?.let { type ->
                    JavadocParameterNode(
                        name = it.name.orEmpty(),
                        type = type,
                        description = it.brief(),
                        typeBound = it.type,
                        dri = it.dri,
                        extra = PropertyContainer.withAll(it.indexesInDocumentation())
                    )
                }
            },
            returnTagContent = returnToContentNodes(jvm),
            sinceTagContent = sinceToContentNodes(jvm),
            extra = extra + indexesInDocumentation()
        )
    }

    private val Documentable.highestJvmSourceSet
        get() = sourceSets.let { sources ->
            sources.firstOrNull { it != expectPresentInSet } ?: sources.firstOrNull()
        }

    private inline fun <reified T : TagWrapper> Documentable.findNodeInDocumentation(sourceSetData: DokkaSourceSet?): T? =
        documentation[sourceSetData]?.firstChildOfTypeOrNull<T>()

    private inline fun <reified T : TagWrapper> Documentable.findAllNodesInDocumentation(sourceSetData: DokkaSourceSet?): List<T> =
        documentation[sourceSetData]?.childrenOfType<T>() ?: emptyList()

    private fun Documentable.descriptionToContentNodes(sourceSet: DokkaSourceSet? = highestJvmSourceSet) =
        contentNodesFromType<Description>(sourceSet)

    private fun DParameter.paramsToContentNodes(sourceSet: DokkaSourceSet? = highestJvmSourceSet) =
        contentNodesFromType<Param>(sourceSet)

    private inline fun <reified T : TagWrapper> Documentable.contentNodesFromType(sourceSet: DokkaSourceSet?) =
        findNodeInDocumentation<T>(sourceSet)?.let {
            DocTagToContentConverter().buildContent(
                it.root,
                DCI(setOf(dri), JavadocContentKind.OverviewSummary),
                sourceSets.toSet()
            )
        }.orEmpty()

    private inline fun <reified T : TagWrapper> Documentable.allContentNodesFromType(sourceSet: DokkaSourceSet?) =
        findAllNodesInDocumentation<T>(sourceSet).map {
            DocTagToContentConverter().buildContent(
                it.root,
                DCI(setOf(dri), JavadocContentKind.OverviewSummary),
                sourceSets.toSet()
            )
        }

    public fun List<ContentNode>.nodeForJvm(jvm: DokkaSourceSet): ContentNode {
        return firstOrNull { jvm.sourceSetID in it.sourceSets.computeSourceSetIds() }
            ?: throw IllegalStateException("No source set found for ${jvm.sourceSetID} ")
    }

    private fun Documentable.brief(sourceSet: DokkaSourceSet? = highestJvmSourceSet): List<ContentNode> =
        firstSentenceBriefFromContentNodes(descriptionToContentNodes(sourceSet))

    private fun DParameter.brief(sourceSet: DokkaSourceSet? = highestJvmSourceSet): List<ContentNode> =
        firstSentenceBriefFromContentNodes(paramsToContentNodes(sourceSet).dropWhile { it is ContentDRILink })

    private fun ContentNode.asJavadocNode(): JavadocSignatureContentNode =
        (this as ContentGroup).firstChildOfTypeOrNull<JavadocSignatureContentNode>()
            ?: throw IllegalStateException("No content for javadoc signature found")

    private fun signatureForNode(documentable: Documentable, sourceSet: DokkaSourceSet): JavadocSignatureContentNode =
        signatureProvider.signature(documentable).nodeForJvm(sourceSet).asJavadocNode()

    private fun Documentable.indexesInDocumentation(): JavadocIndexExtra {
        val indexes =
            documentation[highestJvmSourceSet]?.withDescendants()?.filterIsInstance<Index>()?.toList().orEmpty()
        return JavadocIndexExtra(
            indexes.map {
                ContentGroup(
                    children = DocTagToContentConverter().buildContent(
                        it,
                        DCI(setOf(dri), JavadocContentKind.OverviewSummary),
                        sourceSets.toSet()
                    ),
                    dci = DCI(setOf(dri), JavadocContentKind.OverviewSummary),
                    sourceSets = sourceSets.toDisplaySourceSets(),
                    style = emptySet(),
                    extra = PropertyContainer.empty()
                )
            }
        )
    }

    private fun Documentable.authorsToContentNodes(sourceSet: DokkaSourceSet? = highestJvmSourceSet) =
        allContentNodesFromType<Author>(sourceSet)

    private fun Documentable.sinceToContentNodes(sourceSet: DokkaSourceSet? = highestJvmSourceSet) =
        allContentNodesFromType<Since>(sourceSet)

    private fun Documentable.returnToContentNodes(sourceSet: DokkaSourceSet? = highestJvmSourceSet) =
        contentNodesFromType<Return>(sourceSet)
}

