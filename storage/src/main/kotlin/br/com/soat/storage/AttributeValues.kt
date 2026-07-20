package br.com.soat.storage

import aws.sdk.kotlin.services.dynamodb.model.AttributeValue
import java.math.BigDecimal

fun s(value: String): AttributeValue = AttributeValue.S(value)
fun n(value: Number): AttributeValue = AttributeValue.N(value.toString())
fun n(value: BigDecimal): AttributeValue = AttributeValue.N(value.toPlainString())

fun Map<String, AttributeValue>.str(key: String): String =
    (this[key] as? AttributeValue.S)?.value ?: error("Missing string attribute '$key'")

fun Map<String, AttributeValue>.strOrNull(key: String): String? =
    (this[key] as? AttributeValue.S)?.value

fun Map<String, AttributeValue>.int(key: String): Int =
    (this[key] as? AttributeValue.N)?.value?.toInt() ?: error("Missing number attribute '$key'")

fun Map<String, AttributeValue>.decimal(key: String): BigDecimal =
    (this[key] as? AttributeValue.N)?.value?.let(::BigDecimal) ?: error("Missing number attribute '$key'")

fun Map<String, AttributeValue>.longOrNull(key: String): Long? =
    (this[key] as? AttributeValue.N)?.value?.toLong()
