package net.agl.keycloak.config

import org.yaml.snakeyaml.Yaml
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.Properties
import kotlin.collections.iterator

internal sealed interface PathSegment {
    data class Key(val name: String) : PathSegment
    data class Index(val i: Int) : PathSegment
}

private val ARRAY_INDEX = Regex("""\[(\d+)]""")

/** "event-listener.topics[0]" -> [Key(event-listener), Key(topics), Index(0)] */
internal fun splitPath(path: String): List<PathSegment> {
    val segments = ArrayList<PathSegment>()
    for (part in path.split('.')) {
        val bracketStart = part.indexOf('[')
        if (bracketStart < 0) {
            segments += PathSegment.Key(part)
        } else {
            val name = part.substring(0, bracketStart)
            if (name.isNotEmpty()) segments += PathSegment.Key(name)
            for (m in ARRAY_INDEX.findAll(part.substring(bracketStart))) {
                segments += PathSegment.Index(m.groupValues[1].toInt())
            }
        }
    }
    return segments
}

internal fun navigate(root: ConfigNode, path: String): ConfigNode? {
    var current: ConfigNode? = root
    for (seg in splitPath(path)) {
        current = when (seg) {
            is PathSegment.Key -> current[seg.name]
            is PathSegment.Index -> current[seg.i]
        }
    }
    return current
}

/** kc-event-listener.yml/.yaml -> tree, using SnakeYAML's native map/list/scalar structure directly. */
internal fun yamlToTree(stream: InputStream): ConfigNode.Obj {
    val raw = Yaml().load<Any?>(stream)
    return fromRaw(raw) as? ConfigNode.Obj
        ?: throw IllegalArgumentException("YAML root must be a mapping, got: ${raw?.let { it::class.simpleName }}")
}

private fun fromRaw(value: Any?): ConfigNode = when (value) {
    is Map<*, *> -> ConfigNode.Obj(
        value.entries.mapNotNull { (k, v) -> if (v == null) null else k.toString() to fromRaw(v) }.toMap(),
    )
    is List<*> -> ConfigNode.Arr(value.map { fromRaw(it) })
    else -> ConfigNode.Scalar(value.toString())
}

/** kc-event-listener.properties -> tree, expanding dotted/indexed keys ("a.b[0].c=x") into nested objects/arrays. */
internal fun propertiesToTree(stream: InputStream): ConfigNode.Obj {
    val props = Properties()
    stream.reader(StandardCharsets.UTF_8).use { props.load(it) }
    return propertiesToTree(props.entries.associate { (k, v) -> k.toString() to v.toString() })
}

internal fun propertiesToTree(flat: Map<String, String>): ConfigNode.Obj {
    val root = Builder.ObjBuilder()
    for ((key, value) in flat) {
        insert(root, splitPath(key), value)
    }
    return root.toNode() as ConfigNode.Obj
}

private sealed class Builder {
    class ObjBuilder : Builder() {
        val fields = LinkedHashMap<String, Builder>()
    }

    class ArrBuilder : Builder() {
        val items = ArrayList<Builder?>()
    }

    class Leaf(val value: String) : Builder()

    fun toNode(): ConfigNode = when (this) {
        is Leaf -> ConfigNode.Scalar(value)
        is ObjBuilder -> ConfigNode.Obj(fields.mapValues { it.value.toNode() })
        is ArrBuilder -> ConfigNode.Arr(items.map { it?.toNode() ?: ConfigNode.Scalar("") })
    }
}

private fun insert(root: Builder.ObjBuilder, segments: List<PathSegment>, value: String) {
    var container: Builder = root
    for ((i, seg) in segments.withIndex()) {
        val isLast = i == segments.lastIndex
        val nextIsIndex = !isLast && segments[i + 1] is PathSegment.Index
        when (seg) {
            is PathSegment.Key -> {
                val obj = container as? Builder.ObjBuilder ?: return
                if (isLast) {
                    obj.fields[seg.name] = Builder.Leaf(value)
                } else {
                    container = obj.fields.getOrPut(seg.name) { if (nextIsIndex) Builder.ArrBuilder() else Builder.ObjBuilder() }
                }
            }
            is PathSegment.Index -> {
                val arr = container as? Builder.ArrBuilder ?: return
                while (arr.items.size <= seg.i) arr.items.add(null)
                if (isLast) {
                    arr.items[seg.i] = Builder.Leaf(value)
                } else {
                    var next = arr.items[seg.i]
                    if (next == null) {
                        next = if (nextIsIndex) Builder.ArrBuilder() else Builder.ObjBuilder()
                        arr.items[seg.i] = next
                    }
                    container = next
                }
            }
        }
    }
}

/**
 * "event-listener.webhook.url" -> "KCEL_EVENT_LISTENER_WEBHOOK_URL": dots/dashes collapse to '_'
 * (like Spring's relaxed binding), then the whole name is namespaced under a `KCEL_` prefix so
 * these env vars can't collide with unrelated ones on the server.
 */
internal fun toEnvVarName(path: String): String = "KCEL_" + path.uppercase().replace(Regex("[^A-Z0-9]"), "_").trim('_')
