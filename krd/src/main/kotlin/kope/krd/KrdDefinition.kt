package kope.krd

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.fkorotkov.kubernetes.apiextensions.*
import io.fabric8.kubernetes.api.model.apiextensions.CustomResourceDefinition
import io.fabric8.kubernetes.api.model.apiextensions.JSONSchemaProps
import kotlin.reflect.KClass
import kotlin.reflect.full.declaredMemberProperties
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.jvm.jvmErasure

internal fun yaml(): ObjectMapper = ObjectMapper(YAMLFactory()).registerKotlinModule()
internal fun json(): ObjectMapper = ObjectMapper().registerKotlinModule()

class KrdDefinition(val clazz: KClass<out Krd>) {

    val yaml: String by lazy { generateYaml() }

    val definition: CustomResourceDefinition by lazy { customResourceDefinition() }

    fun krdObject(obj: Krd): KrdObject = KrdObject(this, obj)

    fun krdObjectFromJson(json: String): KrdObject = KrdObject.fromJsonTree(this, json().readTree(json) as ObjectNode)

    private fun generateYaml(): String = yaml().writeValueAsString(definition)

    private fun customResourceDefinition(): CustomResourceDefinition {
        val resourceDefinition = clazz.findAnnotation<ResourceDefinition>()
                ?: throw IllegalStateException("Specify ${ResourceDefinition::class} annotation on specified class $clazz")
        return newCustomResourceDefinition {
            apiVersion = resourceDefinition.apiVersion
            metadata {
                name = resourceDefinition.name
            }
            spec {
                group = resourceDefinition.group
                scope = resourceDefinition.scope.value
                preserveUnknownFields = resourceDefinition.preserveUnknownFields
                names {
                    plural = resourceDefinition.pluralName
                    singular = resourceDefinition.singularName
                    kind = resourceDefinition.kind
                    shortNames = resourceDefinition.shortNames.toList()
                }
                version = resourceDefinition.version
                versions = listOf(
                        newCustomResourceDefinitionVersion {
                            name = resourceDefinition.version
                            served = true
                            storage = true
                            if (resourceDefinition.apiVersion == "apiextensions.k8s.io/v1") {
                                schema {
                                    openAPIV3Schema = generateJsonSchemaOf(clazz)
                                }
                            }
                        }
                )
                if (resourceDefinition.apiVersion == "apiextensions.k8s.io/v1beta1") {
                    validation = newCustomResourceValidation {
                        openAPIV3Schema = generateJsonSchemaOf(clazz)
                    }
                }
            }
        }
    }
}

private fun generateJsonSchemaOf(
        clazz: KClass<*>,
        annotations: List<Annotation> = emptyList(),
        nullableProperty: Boolean = false
): JSONSchemaProps {
    return newJSONSchemaProps {
        val propertyDefinition = annotations.firstOrNull { it is PropertyDefinition } as PropertyDefinition?

        if (propertyDefinition != null && propertyDefinition.description.isNotEmpty())
            description = propertyDefinition.description

        if (nullableProperty)
            nullable = true

        when (clazz) {
            String::class -> {
                type = "string"
                val stringDefinition = annotations.firstOrNull { it is StringDefinition } as StringDefinition?
                if (stringDefinition != null) {
                    if (stringDefinition.format.isNotEmpty()) format = stringDefinition.format
                    if (stringDefinition.pattern.isNotEmpty()) pattern = stringDefinition.pattern
                    if (stringDefinition.minLength >= 0) minLength = stringDefinition.minLength.toLong()
                    if (stringDefinition.maxLength >= 0) maxLength = stringDefinition.maxLength.toLong()
                }
            }
            Short::class, Int::class, Long::class, Double::class, Float::class -> {
                type = "number"
                val numberDefinition = annotations.firstOrNull { it is NumberDefinition } as NumberDefinition?
                if (numberDefinition != null) {
                    if (numberDefinition.minimum != NAN)
                        minimum = numberDefinition.minimum
                    if (numberDefinition.maximum != NAN)
                        maximum = numberDefinition.maximum
                }
            }
            else -> {
                if (clazz.javaPrimitiveType != null) throw UnsupportedOperationException("$clazz support is not implemented")
                type = "object"
                val props = clazz.declaredMemberProperties
                if (props.isEmpty()) {
                    throw UnsupportedOperationException("Unsupported. For $clazz no properties were detected")
                } else {
                    properties = clazz.declaredMemberProperties.mapNotNull {
                        if (it.findAnnotation<Ignore>() != null) return@mapNotNull null

                        val name = it.findAnnotation<PropertyDefinition>()
                                ?.name
                                ?.let { name -> if (name.isNotEmpty()) name else null }
                                ?: it.name
                        name to generateJsonSchemaOf(
                                clazz = it.returnType.jvmErasure,
                                annotations = it.annotations,
                                nullableProperty = it.returnType.isMarkedNullable
                        )
                    }.toMap()
                }
            }
        }
    }
}