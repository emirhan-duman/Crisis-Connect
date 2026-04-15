package com.auralis.crisisconnect.service.gattmesh

internal object GattMeshTransportFramer {
    fun buildLegacyTransportPayload(
        payload: ByteArray,
        maxPacketBytes: Int,
        transportHeaderBytes: Int,
        maxTransportPacketBytes: Int
    ): ByteArray? {
        val payloadLength = payload.size
        if (payloadLength !in 1..maxPacketBytes) {
            return null
        }
        if (payloadLength + transportHeaderBytes > maxTransportPacketBytes) {
            return null
        }
        return ByteArray(payloadLength + transportHeaderBytes).apply {
            this[0] = ((payloadLength shr 8) and 0xFF).toByte()
            this[1] = (payloadLength and 0xFF).toByte()
            System.arraycopy(payload, 0, this, transportHeaderBytes, payloadLength)
        }
    }

    fun splitIntoChunks(payload: ByteArray, chunkSize: Int): List<ByteArray> {
        if (chunkSize <= 0 || payload.isEmpty()) {
            return emptyList()
        }
        val chunks = ArrayList<ByteArray>((payload.size + chunkSize - 1) / chunkSize)
        var offset = 0
        while (offset < payload.size) {
            val end = (offset + chunkSize).coerceAtMost(payload.size)
            chunks += payload.copyOfRange(offset, end)
            offset = end
        }
        return chunks
    }
}
