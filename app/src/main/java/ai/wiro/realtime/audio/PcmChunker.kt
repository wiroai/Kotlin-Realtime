package ai.wiro.realtime.audio

class PcmChunker(
    val chunkSizeInBytes: Int,
) {
    init {
        require(chunkSizeInBytes > 0) { "Chunk size must be positive." }
    }

    private var remainder: ByteArray = ByteArray(0)

    val remainderLength: Int
        get() = remainder.size

    fun add(bytes: ByteArray): List<ByteArray> {
        if (bytes.isEmpty()) {
            return emptyList()
        }
        val combined = remainder + bytes
        val chunks = mutableListOf<ByteArray>()
        var offset = 0
        while (combined.size - offset >= chunkSizeInBytes) {
            val end = offset + chunkSizeInBytes
            chunks += combined.copyOfRange(offset, end)
            offset = end
        }
        remainder = combined.copyOfRange(offset, combined.size)
        return chunks
    }

    fun reset() {
        remainder = ByteArray(0)
    }
}
