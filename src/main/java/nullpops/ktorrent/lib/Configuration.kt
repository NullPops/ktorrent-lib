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

import java.io.File

object Configuration {
    var platform = Platform.WINDOWS
    val torrentsDir: File
        get() {
            return when (platform) {
                Platform.WINDOWS -> {
                    File("./data/")
                }
                else -> {
                    TODO()
                }
            }
        }
    val downloadsDir: File
        get() {
            return when (platform) {
                Platform.WINDOWS -> {
                    File("${System.getProperty("user.home")}/ktorrent/").also {
                        if (!it.exists())
                            it.mkdirs()
                    }
                }
                else -> {
                    TODO()
                }
            }
        }
}