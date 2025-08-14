/*
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

import nullpops.ktorrent.lib.bencode.BencodeDict
import nullpops.ktorrent.lib.bencode.BencodeInt
import nullpops.ktorrent.lib.bencode.BencodeList
import nullpops.ktorrent.lib.bencode.BencodeString
import nullpops.ktorrent.lib.bencode.BencodeValue
import nullpops.ktorrent.lib.bencode.bencode
import nullpops.ktorrent.lib.bencode.parseBencodeFile
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.io.SequenceInputStream
import java.security.MessageDigest
import java.util.Collections

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

data class TorrentFileEntry(
    /** Path inside the torrent (slash-separated for multi-file torrents). */
    val path: String,
    /** Length of this file in bytes. */
    val length: Long
)

enum class TorrentVersion { V1, V2 }

/**
 * Parsed .torrent with helpers for size, files, and integrity checks.
 */
data class TorrentFile(
    /** Primary announce URL if present. */
    val announce: String?,
    /** Raw info dictionary (bencoded map). */
    val info: Map<String, BencodeValue>,
    /** Root bencode dictionary of the .torrent file. */
    val raw: BencodeDict,
    /** Piece length in bytes. */
    val pieceLength: Int,
    /** SHA-1(info) for v1 torrents. */
    val infoHash: ByteArray,
    /** True if this is a multi-file torrent. */
    val hasFiles: Boolean,
) {

    /** Torrent name (directory for multi-file, file name for single-file). */
    val name: String by lazy { info.getString("name") }

    /** Raw 20-byte SHA-1 piece hashes concatenated (v1). */
    val piecesRaw: ByteArray by lazy { info.getBytes("pieces") }

    /** Number of pieces (v1). */
    val pieceCount: Int by lazy { piecesRaw.size / 20 }

    /** Torrent version—throws for unsupported v2. */
    val version: TorrentVersion by lazy { detectVersion(info) }

    /** Entries (files) described by the torrent. */
    val entries: List<TorrentFileEntry> by lazy { parseEntries() }

    /**
     * Map of pieceIndex -> isValid. Only contains entries for pieces that have been checked.
     * Absent key means "unknown / unchecked".
     */
    val pieceValidity: MutableMap<Int, Boolean> = HashMap()

    // ---------- File preparation ----------

    /**
     * Ensures the torrent’s output files/folders exist under [baseOutputDir]/[name.sanitize()].
     *
     * @param baseOutputDir Base folder to contain the torrent payload (default: ./data/out).
     * @param preAllocate   If true, expands each file to its full length using RandomAccessFile.
     *
     * @return List of created/existing File handles in the same order as [entries].
     */
    fun prepareFiles(
        baseOutputDir: File = File("./data/out"),
        preAllocate: Boolean = false
    ): List<File> {
        val rootDir = File(baseOutputDir, name.sanitize())
        rootDir.mkdirs()

        return entries.map { entry ->
            val file = File(rootDir, entry.path)
            file.parentFile?.let { parent ->
                if (!parent.isDirectory) parent.delete()
                parent.mkdirs()
            }
            if (!file.exists()) {
                file.createNewFile()
            }
            if (preAllocate && file.length() != entry.length) {
                RandomAccessFile(file, "rw").use { raf -> raf.setLength(entry.length) }
            }
            file
        }
    }

    // ---------- Sizes ----------

    /** Total payload size in bytes, across all files. */
    fun totalSizeBytes(): Long =
        when {
            "length" in info -> info.getLong("length")
            "files" in info -> info.getList("files").sumOf { (it as BencodeDict).value.getLong("length") }
            else -> 0L
        }

    /** Total size in mebibytes (MiB). */
    fun sizeMiB(): Double = totalSizeBytes() / (1024.0 * 1024.0)

    /** Human-friendly MiB with 3 decimals. */
    fun sizeMiBFormatted(): String = String.format("%.3f", sizeMiB())

    // ---------- Integrity ----------

    /**
     * Checks payload integrity against the v1 piece hashes.
     *
     * @param files Files returned from [prepareFiles]. They must cover the torrent payload in the same order as [entries].
     * @param onPieceChecked Optional callback invoked after each piece: (index, isValid) -> Unit.
     */
    fun checkIntegrity(
        files: List<File>,
        onPieceChecked: ((index: Int, isValid: Boolean) -> Unit)? = null
    ) {
        require(version == TorrentVersion.V1) { "Only v1 torrents are supported for integrity check." }

        // Concatenate all files into a single stream in order.
        val streams = files.map { FileInputStream(it) }
        val sha1 = MessageDigest.getInstance("SHA-1")

        SequenceInputStream(Collections.enumeration(streams)).use { full ->
            val buf = ByteArray(pieceLength)
            var pieceIndex = 0
            while (true) {
                val read = full.readNBytes(buf, 0, pieceLength)
                if (read <= 0) break

                val data = if (read < pieceLength) buf.copyOf(read) else buf
                sha1.reset()
                val digest = sha1.digest(data)

                val expectedStart = pieceIndex * 20
                val expectedEnd = expectedStart + 20
                val expected = piecesRaw.copyOfRange(expectedStart, expectedEnd)

                val ok = digest.contentEquals(expected)
                pieceValidity[pieceIndex] = ok
                onPieceChecked?.invoke(pieceIndex, ok)
                pieceIndex++
            }
        }
    }

    /**
     * Bytes of valid data (sums actual piece sizes for pieces that passed).
     * Handles a potentially shorter last piece.
     */
    fun validBytes(): Long {
        if (pieceValidity.isEmpty()) return 0L
        val total = totalSizeBytes()
        return pieceValidity.entries
            .filter { it.value } // only valid pieces
            .sumOf { (index, _) -> pieceSize(index, total) }
    }

    /** Bytes remaining to download/validate. */
    fun leftBytes(): Long = (totalSizeBytes() - validBytes()).coerceAtLeast(0)

    /** Completion percentage in [0.0, 100.0]. */
    fun completionPercent(): Double {
        val total = totalSizeBytes().toDouble()
        if (total <= 0.0) return 0.0
        return (validBytes() / total) * 100.0
    }

    /** Count of pieces marked valid. */
    fun validPieceCount(): Int = pieceValidity.values.count { it }

    /** Count of pieces marked invalid. (Unchecked pieces are not counted.) */
    fun invalidPieceCount(): Int = pieceValidity.values.count { !it }

    // ---------- Helpers ----------

    private fun detectVersion(info: Map<String, BencodeValue>): TorrentVersion {
        val hasV1 = "pieces" in info
        val hasV2 = "file tree" in info
        return when {
            hasV2 -> throw IllegalArgumentException("v2 torrents are not supported yet.")
            hasV1 -> TorrentVersion.V1
            else -> throw IllegalArgumentException("Unknown torrent version: info dict has neither 'pieces' nor 'file tree'.")
        }
    }

    private fun parseEntries(): List<TorrentFileEntry> {
        if (hasFiles) {
            val files = info.getList("files")
            return files.map { fileVal ->
                val fileDict = (fileVal as BencodeDict).value
                val length = fileDict.getLong("length")
                val pathList = fileDict.getList("path")
                val subPath = pathList.joinToString("/") { (it as BencodeString).asUtf8() }
                TorrentFileEntry("$name/$subPath", length)
            }
        }

        val length = info.getLong("length")
        return listOf(TorrentFileEntry(name, length))
    }

    /**
     * Size of piece at [index], accounting for a possibly shorter last piece.
     */
    private fun pieceSize(index: Int, totalBytes: Long): Long {
        val fullPieces = totalBytes / pieceLength
        val remainder = (totalBytes % pieceLength).toInt()
        return when {
            index < fullPieces.toInt() -> pieceLength.toLong()
            index == fullPieces.toInt() -> if (remainder == 0) pieceLength.toLong() else remainder.toLong()
            else -> 0L
        }
    }

    // ---------- Optional v2 info-hash helper (pre-emptive) ----------

    /**
     * Returns SHA-256(bencode(info)) per BEP52 (not used by v1 clients).
     * Throws if this is not a v2 torrent map (caller responsibility).
     */
    fun infoHashV2(): ByteArray {
        val infoDict = BencodeDict(info)
        val encoded = bencode(infoDict)
        return MessageDigest.getInstance("SHA-256").digest(encoded)
    }

    companion object {
        /**
         * Parse a .torrent file into [TorrentFile].
         */
        fun parse(file: File): TorrentFile {
            val root = parseBencodeFile(file) as BencodeDict
            val announce = (root.value["announce"] as? BencodeString)?.asUtf8()

            val info = (root.value["info"] as? BencodeDict)?.value
                ?: throw IllegalArgumentException("Missing 'info' field in torrent.")

            val infoBytes = bencode(BencodeDict(info))
            val infoHash = MessageDigest.getInstance("SHA-1").digest(infoBytes)
            val pieceLength = info.getLong("piece length").toInt()
            val isMulti = "files" in info

            return TorrentFile(
                announce = announce,
                info = info,
                raw = root,
                pieceLength = pieceLength,
                infoHash = infoHash,
                hasFiles = isMulti
            )
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as TorrentFile

        if (pieceLength != other.pieceLength) return false
        if (hasFiles != other.hasFiles) return false
        if (announce != other.announce) return false
        if (info != other.info) return false
        if (raw != other.raw) return false
        if (!infoHash.contentEquals(other.infoHash)) return false
        if (pieceValidity != other.pieceValidity) return false
        if (pieceCount != other.pieceCount) return false
        if (name != other.name) return false
        if (!piecesRaw.contentEquals(other.piecesRaw)) return false
        if (version != other.version) return false
        if (entries != other.entries) return false

        return true
    }

    override fun hashCode(): Int {
        var result = pieceLength
        result = 31 * result + hasFiles.hashCode()
        result = 31 * result + (announce?.hashCode() ?: 0)
        result = 31 * result + info.hashCode()
        result = 31 * result + raw.hashCode()
        result = 31 * result + infoHash.contentHashCode()
        result = 31 * result + pieceValidity.hashCode()
        result = 31 * result + pieceCount
        result = 31 * result + name.hashCode()
        result = 31 * result + piecesRaw.contentHashCode()
        result = 31 * result + version.hashCode()
        result = 31 * result + entries.hashCode()
        return result
    }
}

// -------------------- Bencode access helpers --------------------

private fun Map<String, BencodeValue>.getString(key: String): String =
    (this[key] as? BencodeString)?.asUtf8()
        ?: error("Expected bencode string for key '$key'.")

private fun Map<String, BencodeValue>.getBytes(key: String): ByteArray =
    (this[key] as? BencodeString)?.value
        ?: error("Expected bencode byte string for key '$key'.")

private fun Map<String, BencodeValue>.getLong(key: String): Long =
    (this[key] as? BencodeInt)?.value
        ?: error("Expected bencode int for key '$key'.")

private fun Map<String, BencodeValue>.getList(key: String): List<BencodeValue> =
    (this[key] as? BencodeList)?.value
        ?: error("Expected bencode list for key '$key'.")
