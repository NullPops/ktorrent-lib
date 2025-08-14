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
 * you must obtain a commercial license from NullPops.
 *
 * See the LICENSE file for details.
 */

package nullpops.ktorrent.lib

import nullpops.ktorrent.lib.files.TorrentFile
import java.io.File

object UbuntuTorrentTest {
    val dist = "ubuntu-25.04-desktop-amd64"

    @JvmStatic
    fun main(args: Array<String>) {
        val file = File("./$dist.iso.torrent")
        if (file.exists()) {
            TorrentFile.load(file).let {
                println("Checking integrity...")
                it.checkIntegrity()
                println("${it.name()} | ${it.completionPercentage()}%")
            }
        }
    }
}