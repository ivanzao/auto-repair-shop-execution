package br.com.soat.config

import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.slf4j.LoggerFactory

private val mapper = YAMLMapper().registerKotlinModule()
private val logger = LoggerFactory.getLogger(Config::class.java)

fun Config.Companion.fromClasspath(path: String, env: (String) -> String? = { System.getenv(it) }): Config {
    val stream = Config::class.java.classLoader.getResourceAsStream(path)
        ?: throw IllegalArgumentException("File not found within classpath: $path")

    return buildConfig(mapper.readValue(stream), env)
}

private val PLACEHOLDER = Regex("""\$\{([A-Za-z_][A-Za-z0-9_]*)(?::(.*?))?}""")

private fun buildConfig(raw: Map<String, Any?>, env: (String) -> String?): Config {
    val flat = flatten(raw)
        .mapValues { (_, v) -> interpolate(v, env) }

    return Config(flat as MutableMap).also {
        logger.info("Loaded configs from application.yaml")
    }
}

private fun interpolate(value: Any?, env: (String) -> String?): Any? {
    if (value !is String) return value
    return PLACEHOLDER.replace(value) { match ->
        val name = match.groupValues[1]
        env(name)
            ?: match.groups[2]?.value
            ?: throw ConfigException("Missing required environment variable: $name")
    }
}

private fun flatten(
    source: Map<String, Any?>,
    parentKey: String = "",
    separator: String = "."
): Map<String, Any?> {
    val result = mutableMapOf<String, Any?>()

    for ((key, value) in source) {
        val newKey = if (parentKey.isEmpty()) key else "$parentKey$separator$key"

        when (value) {
            is Map<*, *> -> {
                @Suppress("UNCHECKED_CAST")
                result.putAll(flatten(value as Map<String, Any?>, newKey, separator))
            }

            is List<*> -> {
                value.forEachIndexed { index, item ->
                    val listKey = "$newKey[$index]"

                    if (item is Map<*, *>) {
                        @Suppress("UNCHECKED_CAST")
                        result.putAll(flatten(item as Map<String, Any?>, listKey, separator))
                    } else {
                        result[listKey] = item
                    }
                }
            }

            else -> result[newKey] = value
        }
    }

    return result
}
