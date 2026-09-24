package me.rerere.rikkahub.plugin.scanner

import java.io.File

internal object LegacyPluginMigration {
    fun migrate(legacyRoot: File, destinationRoot: File, install: (File, File) -> Unit) {
        if (!legacyRoot.isDirectory || legacyRoot.canonicalFile == destinationRoot.canonicalFile) return
        val markers = File(destinationRoot, ".legacy-migrated")
        legacyRoot.listFiles { file -> file.isDirectory }
            ?.filter { File(it, "manifest.json").isFile }
            ?.forEach { source ->
                val marker = File(markers, source.name)
                if (!marker.isFile) {
                    val destination = File(destinationRoot, source.name)
                    if (!destination.exists()) install(source, destination)
                    check(destination.isDirectory) { "Plugin migration did not create a directory" }
                    check(markers.isDirectory || markers.mkdirs()) { "Cannot record plugin migration" }
                    // Keep this record after uninstall so the preserved legacy copy stays uninstalled.
                    marker.writeText("migrated")
                }
            }
    }
}
