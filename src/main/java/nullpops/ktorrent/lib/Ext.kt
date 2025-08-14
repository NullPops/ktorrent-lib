/*
 *
 * Copyright (c) 2025 NullPops
 *
 * This file is part of ktorrent-lib.
 *
 * Licensed under the GNU Affero General Public License v3.0 (AGPLv3)
 * or a Commercial License.
 *
 * You may use this file under AGPLv3 if you release your project under
 * a compatible open source license. For closed source or commercial use,
 * you must obtain a commercial license from [Your Name or Company].
 *
 * See the LICENSE file for details.
 */

package nullpops.ktorrent.lib

object Ext {
    /**
     * Sanitize a filename or directory name for cross-platform use.
     *
     * - Replaces reserved/unsafe characters with '_'
     * - Trims whitespace and trailing dots/spaces (Windows)
     * - Ensures non-empty and not "." or ".."
     * - Truncates by UTF-8 byte length without splitting code points
     */
    fun String.sanitize(maxBytes: Int = 255): String {
        val unsafe = Regex("""[<>:"/\\|?*\x00-\x1F]""")
        var sanitized = replace(unsafe, "_")
            .trim()
            .replace(Regex("""\s+"""), " ")
            .trimEnd('.', ' ')

        if (sanitized.isEmpty() || sanitized == "." || sanitized == "..") sanitized = "_torrent"

        val utf8 = sanitized.encodeToByteArray()
        if (utf8.size <= maxBytes) return sanitized

        var cut = sanitized.length
        while (cut > 0 && sanitized.substring(0, cut).encodeToByteArray().size > maxBytes) cut--
        sanitized = sanitized.substring(0, cut).trimEnd('.', ' ')
        if (sanitized.isEmpty() || sanitized == "." || sanitized == "..") sanitized = "_torrent"
        return sanitized
    }
}