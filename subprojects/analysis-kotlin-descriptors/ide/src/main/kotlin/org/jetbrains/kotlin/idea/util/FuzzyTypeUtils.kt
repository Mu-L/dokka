// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
@file:JvmName("FuzzyTypeUtils")
package org.jetbrains.kotlin.idea.util

import org.jetbrains.kotlin.descriptors.CallableDescriptor
import org.jetbrains.kotlin.descriptors.CallableMemberDescriptor
import org.jetbrains.kotlin.descriptors.TypeParameterDescriptor
import org.jetbrains.kotlin.resolve.calls.inference.CallHandle
import org.jetbrains.kotlin.resolve.calls.inference.ConstraintSystemBuilderImpl
import org.jetbrains.kotlin.resolve.calls.inference.constraintPosition.ConstraintPositionKind
import org.jetbrains.kotlin.types.*
import org.jetbrains.kotlin.types.error.ErrorUtils
import org.jetbrains.kotlin.types.checker.StrictEqualityTypeChecker
import org.jetbrains.kotlin.types.typeUtil.*
import java.util.*

internal fun CallableDescriptor.fuzzyExtensionReceiverType() = extensionReceiverParameter?.type?.toFuzzyType(typeParameters)

internal fun FuzzyType.nullability() = type.nullability()

internal fun KotlinType.toFuzzyType(freeParameters: Collection<TypeParameterDescriptor>) = FuzzyType(this, freeParameters)

internal class FuzzyType(val type: KotlinType, freeParameters: Collection<TypeParameterDescriptor>) {
    val freeParameters: Set<TypeParameterDescriptor>

    init {
        if (freeParameters.isNotEmpty()) {
            // we allow to pass type parameters from another function with the same original in freeParameters
            val usedTypeParameters = HashSet<TypeParameterDescriptor>().apply { addUsedTypeParameters(type) }
            if (usedTypeParameters.isNotEmpty()) {
                val originalFreeParameters = freeParameters.map { it.toOriginal() }.toSet()
                this.freeParameters = usedTypeParameters.filter { it.toOriginal() in originalFreeParameters }.toSet()
            } else {
                this.freeParameters = emptySet()
            }
        } else {
            this.freeParameters = emptySet()
        }
    }

    // Diagnostic for EA-109046
    @Suppress("USELESS_ELVIS")
    private fun TypeParameterDescriptor.toOriginal(): TypeParameterDescriptor {
        val callableDescriptor = containingDeclaration as? CallableMemberDescriptor ?: return this
        val original = callableDescriptor.original ?: error("original = null for $callableDescriptor")
        val typeParameters = original.typeParameters ?: error("typeParameters = null for $original")
        return typeParameters[index]
    }

    override fun equals(other: Any?) = other is FuzzyType && other.type == type && other.freeParameters == freeParameters

    override fun hashCode() = type.hashCode()

    private fun MutableSet<TypeParameterDescriptor>.addUsedTypeParameters(type: KotlinType) {
        val typeParameter = type.constructor.declarationDescriptor as? TypeParameterDescriptor
        if (typeParameter != null && add(typeParameter)) {
            typeParameter.upperBounds.forEach { addUsedTypeParameters(it) }
        }

        for (argument in type.arguments) {
            if (!argument.isStarProjection) { // otherwise we can fall into infinite recursion
                addUsedTypeParameters(argument.type)
            }
        }
    }

    fun checkIsSubtypeOf(otherType: FuzzyType): TypeSubstitutor? = matchedSubstitutor(otherType, MatchKind.IS_SUBTYPE)

    fun checkIsSuperTypeOf(otherType: FuzzyType): TypeSubstitutor? = matchedSubstitutor(otherType,
        MatchKind.IS_SUPERTYPE
    )

    fun checkIsSubtypeOf(otherType: KotlinType): TypeSubstitutor? = checkIsSubtypeOf(otherType.toFuzzyType(emptyList()))

    fun checkIsSuperTypeOf(otherType: KotlinType): TypeSubstitutor? = checkIsSuperTypeOf(otherType.toFuzzyType(emptyList()))

    private enum class MatchKind {
        IS_SUBTYPE,
        IS_SUPERTYPE
    }

    private fun matchedSubstitutor(otherType: FuzzyType, matchKind: MatchKind): TypeSubstitutor? {
        if (type.isError) return null
        if (otherType.type.isError) return null
        if (otherType.type.isUnit() && matchKind == MatchKind.IS_SUBTYPE) return TypeSubstitutor.EMPTY

        fun KotlinType.checkInheritance(otherType: KotlinType): Boolean {
            return when (matchKind) {
                MatchKind.IS_SUBTYPE -> this.isSubtypeOf(otherType)
                MatchKind.IS_SUPERTYPE -> otherType.isSubtypeOf(this)
            }
        }

        if (freeParameters.isEmpty() && otherType.freeParameters.isEmpty()) {
            return if (type.checkInheritance(otherType.type)) TypeSubstitutor.EMPTY else null
        }

        val builder = ConstraintSystemBuilderImpl()
        val typeVariableSubstitutor = builder.registerTypeVariables(CallHandle.NONE, freeParameters + otherType.freeParameters)

        val typeInSystem = typeVariableSubstitutor.substitute(type, Variance.INVARIANT)
        val otherTypeInSystem = typeVariableSubstitutor.substitute(otherType.type, Variance.INVARIANT)

        when (matchKind) {
            MatchKind.IS_SUBTYPE ->
                builder.addSubtypeConstraint(typeInSystem, otherTypeInSystem, ConstraintPositionKind.RECEIVER_POSITION.position())
            MatchKind.IS_SUPERTYPE ->
                builder.addSubtypeConstraint(otherTypeInSystem, typeInSystem, ConstraintPositionKind.RECEIVER_POSITION.position())
        }

        builder.fixVariables()

        val constraintSystem = builder.build()

        if (constraintSystem.status.hasContradiction()) return null

        // currently ConstraintSystem return successful status in case there are problems with nullability
        // that's why we have to check subtyping manually
        val substitutor = constraintSystem.resultingSubstitutor
        val substitutedType = substitutor.substitute(type, Variance.INVARIANT) ?: return null
        if (substitutedType.isError) return TypeSubstitutor.EMPTY
        val otherSubstitutedType = substitutor.substitute(otherType.type, Variance.INVARIANT) ?: return null
        if (otherSubstitutedType.isError) return TypeSubstitutor.EMPTY
        if (!substitutedType.checkInheritance(otherSubstitutedType)) return null

        val substitutorToKeepCapturedTypes = object : DelegatedTypeSubstitution(substitutor.substitution) {
            override fun approximateCapturedTypes() = false
        }.buildSubstitutor()

        val substitutionMap: Map<TypeConstructor, TypeProjection> = constraintSystem.typeVariables
            .map { it.originalTypeParameter }
            .associateBy(
                keySelector = { it.typeConstructor },
                valueTransform = { parameterDescriptor ->
                    val typeProjection = TypeProjectionImpl(Variance.INVARIANT, parameterDescriptor.defaultType)
                    val substitutedProjection = substitutorToKeepCapturedTypes.substitute(typeProjection)
                    substitutedProjection?.takeUnless { ErrorUtils.containsUninferredTypeVariable(it.type) } ?: typeProjection
                })
        return TypeConstructorSubstitution.createByConstructorsMap(substitutionMap, approximateCapturedTypes = true).buildSubstitutor()
    }
}


internal fun TypeSubstitution.hasConflictWith(other: TypeSubstitution, freeParameters: Collection<TypeParameterDescriptor>): Boolean {
    return freeParameters.any { parameter ->
        val type = parameter.defaultType
        val substituted1 = this[type] ?: return@any false
        val substituted2 = other[type] ?: return@any false
        !StrictEqualityTypeChecker.strictEqualTypes(
            substituted1.type.unwrap(),
            substituted2.type.unwrap()
        ) || substituted1.projectionKind != substituted2.projectionKind
    }
}