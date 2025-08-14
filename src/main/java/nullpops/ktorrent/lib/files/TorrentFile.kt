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

package nullpops.ktorrent.lib.files

import nullpops.ktorrent.lib.bencode.BencodeDict
import nullpops.ktorrent.lib.bencode.BencodeInt
import nullpops.ktorrent.lib.bencode.BencodeList
import nullpops.ktorrent.lib.bencode.BencodeString
import nullpops.ktorrent.lib.bencode.BencodeValue
import nullpops.ktorrent.lib.bencode.bencode
import nullpops.ktorrent.lib.bencode.parseBencodeFile
import nullpops.ktorrent.lib.data.TorrentVersion
import java.io.File
import java.security.MessageDigest

abstract class TorrentFile(file: File) {
    var dict: BencodeDict
    var info: Map<String, BencodeValue>
    var version: TorrentVersion
    var announce: String?
    var pieceLength: Int
    var singleFile: Boolean
    var infoBytes: ByteArray
    var infoBytesHash: ByteArray

    init {
        try {
            dict = parseBencodeFile(file) as BencodeDict
            announce = (dict.value["announce"] as? BencodeString)?.asUtf8()
            info = (dict.value["info"] as? BencodeDict)?.value
                ?: throw IllegalArgumentException("Missing 'info' field in torrent.")
            version = detectVersion()
            pieceLength = info.getLong("piece length").toInt()
            infoBytes = bencode(BencodeDict(info))
            infoBytesHash = MessageDigest.getInstance("SHA-1").digest(infoBytes)

            singleFile = "files" !in info
        } catch (e: Exception) {
            throw IllegalArgumentException(e)
        }
    }


    private fun detectVersion(): TorrentVersion {
        val hasV1 = "pieces" in info
        val hasV2 = "file tree" in info
        return when {
            hasV2 -> throw IllegalArgumentException("v2 torrents are not supported yet.")
            hasV1 -> TorrentVersion.V1
            else -> throw IllegalArgumentException("Unknown torrent version: info dict has neither 'pieces' nor 'file tree'.")
        }
    }

    open fun parse() : TorrentFile? {
        return null
    }

    open fun checkIntegrity(
        onPieceChecked: ((index: Int, isValid: Boolean) -> Unit)? = null
    ) {

    }

    open fun completionPercentage() : Double? {
        return null
    }

    open fun name() : String? {
        return null
    }

    companion object {
        fun load(file: File) : TorrentFile {
            val dict = parseBencodeFile(file) as BencodeDict
            val info = (dict.value["info"] as? BencodeDict)?.value
                ?: throw IllegalArgumentException("Missing 'info' field in torrent.")
            val hasV1 = "pieces" in info
            val hasV2 = "file tree" in info
            return when {
                hasV2 -> throw IllegalArgumentException("v2 torrents are not supported yet.")
                hasV1 -> TorrentFileV1(file).apply { parse() }
                else -> throw IllegalArgumentException("Unknown torrent version: info dict has neither 'pieces' nor 'file tree'.")
            }
        }
    }

    // -------------------- Bencode access helpers --------------------

    fun Map<String, BencodeValue>.getString(key: String): String =
        (this[key] as? BencodeString)?.asUtf8()
            ?: error("Expected bencode string for key '$key'.")

    fun Map<String, BencodeValue>.getBytes(key: String): ByteArray =
        (this[key] as? BencodeString)?.value
            ?: error("Expected bencode byte string for key '$key'.")

    fun Map<String, BencodeValue>.getLong(key: String): Long =
        (this[key] as? BencodeInt)?.value
            ?: error("Expected bencode int for key '$key'.")

    fun Map<String, BencodeValue>.getList(key: String): List<BencodeValue> =
        (this[key] as? BencodeList)?.value
            ?: error("Expected bencode list for key '$key'.")
}