package br.com.soat.config

class Config(
    private val flat: MutableMap<String, Any?>
) {

    fun getString(key: String, defaultValue: String? = null) = get(key, defaultValue) { it.toString() }

    fun getStringOrNull(key: String): String? = try { getString(key) } catch (_: Exception) { null }

    fun getInt(key: String, defaultValue: Int? = null) =
        get(key, defaultValue) {
            when (it) {
                is Int -> it
                is Number -> it.toInt()
                is String -> it.toIntOrNull()
                    ?: throw IllegalArgumentException("Key '$key' is not a valid integer: '$it'")

                else -> throw IllegalArgumentException("Key '$key' not found and default value was not provided")
            }
        }

    fun getBoolean(key: String, defaultValue: Boolean? = null) =
        get(key, defaultValue) {
            when (it) {
                is Boolean -> it
                is String -> it.equals("true", ignoreCase = true)
                else -> throw IllegalArgumentException("Key '$key' not found and default value was not provided")
            }
        }

    fun put(key: String, value: Any?) {
        flat[key] = value
    }

    private fun <T> get(key: String, defaultValue: T?, transformerFunc: (Any) -> T): T =
        flat[key]?.let { transformerFunc(it) }
            ?: defaultValue
            ?: throw IllegalArgumentException("Key '$key' not found and default value was not provided")

    companion object
}
