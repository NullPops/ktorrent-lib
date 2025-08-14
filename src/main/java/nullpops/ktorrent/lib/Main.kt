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

import nullpops.ktorrent.lib.TorrentFile.Companion.parse
import java.io.File

object Main {
    @JvmStatic
    fun main(args: Array<String>) {
        File("./ubuntu-25.04-desktop-amd64.iso.torrent").apply {
            if (exists()) {
                parse(this).apply {
                    checkIntegrity(listOf(File("./ubuntu-25.04-desktop-amd64.iso"))) { idx, passed ->
                        println("$idx $passed")
                    }
                    println("$name | ${completionPercent()}%")
                }
            }
        }
    }
}