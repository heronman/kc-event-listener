package net.agl.keycloak.config

/** Immutable tree produced by parsing one YAML/properties source, or by merging several. */
sealed interface ConfigNode {
    data class Obj(val fields: Map<String, ConfigNode>) : ConfigNode
    data class Arr(val items: List<ConfigNode>) : ConfigNode
    data class Scalar(val text: String) : ConfigNode
}

/** Null-safe chaining: `tree["a"]["b"]["c"]` returns null the moment any segment is missing or of the wrong shape. */
operator fun ConfigNode?.get(key: String): ConfigNode? = (this as? ConfigNode.Obj)?.fields?.get(key)

operator fun ConfigNode?.get(index: Int): ConfigNode? = (this as? ConfigNode.Arr)?.items?.getOrNull(index)

fun ConfigNode?.asString(): String? = (this as? ConfigNode.Scalar)?.text

fun ConfigNode?.asBoolean(default: Boolean = false): Boolean = asString()?.toBooleanStrictOrNull() ?: default

fun ConfigNode?.asInt(default: Int): Int = asString()?.toIntOrNull() ?: default

fun ConfigNode?.asLong(default: Long): Long = asString()?.toLongOrNull() ?: default

fun ConfigNode?.asStringList(): List<String> = (this as? ConfigNode.Arr)?.items.orEmpty().mapNotNull { it.asString() }

/** For array-of-object sections like `feedback.webhook: [ {url: ...}, {url: ...} ]`. Non-Obj items are dropped. */
fun ConfigNode?.asObjList(): List<ConfigNode.Obj> = (this as? ConfigNode.Arr)?.items.orEmpty().filterIsInstance<ConfigNode.Obj>()

/** Number of Scalar leaves under this node — for diagnostics/logging only. */
fun ConfigNode.leafCount(): Int = when (this) {
    is ConfigNode.Obj -> fields.values.sumOf { it.leafCount() }
    is ConfigNode.Arr -> items.sumOf { it.leafCount() }
    is ConfigNode.Scalar -> 1
}

/**
 * Deep-merges [override] onto [base]: object fields are merged key by key (recursively);
 * a Scalar/Arr, or a type mismatch (e.g. object vs scalar at the same path), makes [override]
 * win wholesale at that path — arrays are replaced, not merged element-by-element.
 */
fun deepMerge(base: ConfigNode, override: ConfigNode): ConfigNode {
    if (base is ConfigNode.Obj && override is ConfigNode.Obj) {
        val keys = LinkedHashSet<String>(base.fields.keys)
        keys.addAll(override.fields.keys)
        return ConfigNode.Obj(keys.associateWith { key ->
            val b = base.fields[key]
            val o = override.fields[key]
            when {
                b != null && o != null -> deepMerge(b, o)
                o != null -> o
                else -> b!!
            }
        })
    }
    return override
}
