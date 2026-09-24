/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.files

import java.io.File

/**
 * Resolve a relative path without allowing it to escape [root].
 * Backup archives and plugin supplied paths must go through this boundary.
 */
object SafeFileResolver {
    fun resolveInside(root: File, relativePath: String, allowRoot: Boolean = false): File? {
        if (relativePath.indexOf('\u0000') >= 0) return null

        val normalized = relativePath.replace('\\', '/')
        val canonicalRoot = runCatching { root.canonicalFile }.getOrNull() ?: return null
        val requested = File(normalized)
        val target = runCatching {
            if (requested.isAbsolute) requested.canonicalFile else File(canonicalRoot, normalized).canonicalFile
        }.getOrNull() ?: return null
        if (target == canonicalRoot) return target.takeIf { allowRoot }

        val rootPath = canonicalRoot.path
        return target.takeIf { it.path.startsWith(rootPath + File.separator) }
    }
}
