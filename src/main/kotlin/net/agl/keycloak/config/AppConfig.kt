package net.agl.keycloak.config

import org.jboss.logging.Logger
import java.io.File
import java.io.InputStream

/**
 * Spring-Boot-style layered configuration, exposed as a tree (not a flat map) so nested
 * sections can be navigated directly: `AppConfig.tree["event-listener"]["webhook"]["url"].asString()`.
 *
 * Sources, lowest to highest priority (a later source overrides matching keys from an
 * earlier one; objects are merged recursively, see [deepMerge]):
 *  1. classpath:/kc-event-listener.{yml,yaml,properties}   (packaged in the jar, under src/main/resources)
 *  2. file:./kc-event-listener.{yml,yaml,properties}
 *  3. file:./config/kc-event-listener.{yml,yaml,properties}
 *  4. OS environment variables prefixed `KCEL_` — only applied by [get], via relaxed binding
 *     (see [toEnvVarName]); [node] and [tree] read files only. Env vars are flat key/value pairs
 *     and can't express a whole subtree, so there's no way for them to override at that level.
 *
 * Within a single location, if both a YAML and a .properties file are present, the
 * .properties file wins for keys they both define (same rule Spring uses).
 *
 * Loaded once, lazily, for the lifetime of this classloader (provider jars are reloaded on
 * `kc.sh build` / server restart, not on every request).
 */
object AppConfig {

    private val log = Logger.getLogger(AppConfig::class.java)
    private const val BASE_NAME = "kc-event-listener"
    private val FILE_KINDS = listOf("yml", "yaml", "properties")

    val tree: ConfigNode.Obj by lazy { loadTree() }

    /** Navigates a dotted/indexed path ("a.b[0].c") through [tree]. File sources only, no env override. */
    fun node(path: String): ConfigNode? = navigate(tree, path)

    /** Same as [node] but resolves to a string leaf, with an OS environment variable override. */
    fun get(path: String): String? = System.getenv(toEnvVarName(path)) ?: node(path).asString()

    fun get(path: String, default: String): String = get(path) ?: default

    fun getBoolean(path: String, default: Boolean = false): Boolean = get(path)?.toBooleanStrictOrNull() ?: default

    fun getInt(path: String, default: Int): Int = get(path)?.toIntOrNull() ?: default

    fun getLong(path: String, default: Long): Long = get(path)?.toLongOrNull() ?: default

    private fun loadTree(): ConfigNode.Obj {
        var merged: ConfigNode.Obj = ConfigNode.Obj(emptyMap())
        merged = mergeTier(merged, "classpath:/") { name -> AppConfig::class.java.classLoader.getResourceAsStream(name) }
        merged = mergeTier(merged, "./") { name -> File(name).takeIf { it.isFile }?.inputStream() }
        merged = mergeTier(merged, "./config/") { name -> File("config", name).takeIf { it.isFile }?.inputStream() }
        return merged
    }

    private fun mergeTier(root: ConfigNode.Obj, locationLabel: String, open: (String) -> InputStream?): ConfigNode.Obj {
        var current = root
        for (kind in FILE_KINDS) {
            val name = "$BASE_NAME.$kind"
            val parsed = try {
                open(name)?.use { stream -> if (kind == "properties") propertiesToTree(stream) else yamlToTree(stream) }
            } catch (e: Exception) {
                log.warnf(e, "Failed to parse %s%s, skipping it", locationLabel, name)
                null
            } ?: continue
            current = deepMerge(current, parsed) as ConfigNode.Obj
            log.infof("Loaded configuration from %s%s (%d leaf value(s))", locationLabel, name, parsed.leafCount())
        }
        return current
    }
}
