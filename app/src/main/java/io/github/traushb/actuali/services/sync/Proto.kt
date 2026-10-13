package io.github.traushb.actuali.services.sync

import java.io.ByteArrayOutputStream

/**
 * The five messages of `Resources/sync.proto`, with a hand-rolled proto3 wire
 * codec.
 *
 * ponytail: written by hand instead of generated, because the schema is five
 * flat messages of strings, bools, bytes and one repeated message. Encoding
 * is canonical (field-number order, proto3 defaults omitted), which is what
 * SwiftProtobuf and protobufjs emit. Upgrade path if sync.proto grows nested
 * or numeric fields: generate Kotlin with protoc + protobuf-kotlin-lite.
 */
object Proto {
    class DecodeError(message: String) : Exception(message)

    class Writer {
        private val out = ByteArrayOutputStream()

        private fun varint(value: Long) {
            var v = value
            while (v and 0x7fL.inv() != 0L) {
                out.write(((v and 0x7f) or 0x80).toInt())
                v = v ushr 7
            }
            out.write(v.toInt())
        }

        private fun key(field: Int, wireType: Int) = varint(((field shl 3) or wireType).toLong())

        private fun lengthDelimited(field: Int, bytes: ByteArray) {
            key(field, 2)
            varint(bytes.size.toLong())
            out.write(bytes)
        }

        fun string(field: Int, value: String) {
            if (value.isNotEmpty()) lengthDelimited(field, value.toByteArray(Charsets.UTF_8))
        }

        fun bytes(field: Int, value: ByteArray) {
            if (value.isNotEmpty()) lengthDelimited(field, value)
        }

        fun bool(field: Int, value: Boolean) {
            if (value) {
                key(field, 0)
                varint(1)
            }
        }

        /** Repeated-message element: always written, even when empty. */
        fun message(field: Int, encoded: ByteArray) = lengthDelimited(field, encoded)

        fun toByteArray(): ByteArray = out.toByteArray()
    }

    class Reader(private val bytes: ByteArray) {
        private var position = 0

        private fun varint(): Long {
            var result = 0L
            var shift = 0
            while (true) {
                if (position >= bytes.size) throw DecodeError("truncated varint")
                val b = bytes[position++].toInt() and 0xff
                if (shift < 64) result = result or ((b and 0x7f).toLong() shl shift)
                if (b and 0x80 == 0) return result
                shift += 7
                if (shift >= 70) throw DecodeError("malformed varint")
            }
        }

        /** Calls [onField] with (field number, wire type) for every field, in order. */
        fun forEachField(onField: (Int, Int) -> Unit) {
            while (position < bytes.size) {
                val key = varint()
                val field = (key ushr 3).toInt()
                val wireType = (key and 7).toInt()
                if (field == 0) throw DecodeError("field number 0")
                onField(field, wireType)
            }
        }

        fun readBytes(): ByteArray {
            val length = varint()
            if (length < 0 || length > bytes.size - position) throw DecodeError("truncated field")
            return bytes.copyOfRange(position, position + length.toInt()).also { position += length.toInt() }
        }

        fun readString(): String = readBytes().toString(Charsets.UTF_8)

        fun readBool(): Boolean = varint() != 0L

        fun skip(wireType: Int) {
            when (wireType) {
                0 -> varint()
                1 -> advance(8)
                2 -> readBytes()
                5 -> advance(4)
                else -> throw DecodeError("unsupported wire type $wireType")
            }
        }

        private fun advance(count: Int) {
            if (count > bytes.size - position) throw DecodeError("truncated field")
            position += count
        }

        /** Reads a field expected at [expected] wire type, or skips a mismatched one. */
        inline fun <T> read(wireType: Int, expected: Int, read: () -> T): T? =
            if (wireType == expected) read() else null.also { skip(wireType) }
    }
}

data class EncryptedData(val iv: ByteArray = ByteArray(0), val authTag: ByteArray = ByteArray(0), val data: ByteArray = ByteArray(0)) {
    fun encode(): ByteArray = Proto.Writer().apply {
        bytes(1, iv)
        bytes(2, authTag)
        bytes(3, data)
    }.toByteArray()

    override fun equals(other: Any?): Boolean =
        other is EncryptedData && iv.contentEquals(other.iv) && authTag.contentEquals(other.authTag) && data.contentEquals(other.data)

    override fun hashCode(): Int = (iv.contentHashCode() * 31 + authTag.contentHashCode()) * 31 + data.contentHashCode()

    companion object {
        fun decode(bytes: ByteArray): EncryptedData {
            var result = EncryptedData()
            val reader = Proto.Reader(bytes)
            reader.forEachField { field, wireType ->
                when (field) {
                    1 -> reader.read(wireType, 2) { reader.readBytes() }?.let { result = result.copy(iv = it) }
                    2 -> reader.read(wireType, 2) { reader.readBytes() }?.let { result = result.copy(authTag = it) }
                    3 -> reader.read(wireType, 2) { reader.readBytes() }?.let { result = result.copy(data = it) }
                    else -> reader.skip(wireType)
                }
            }
            return result
        }
    }
}

data class ProtoMessage(val dataset: String = "", val row: String = "", val column: String = "", val value: String = "") {
    fun encode(): ByteArray = Proto.Writer().apply {
        string(1, dataset)
        string(2, row)
        string(3, column)
        string(4, value)
    }.toByteArray()

    companion object {
        fun decode(bytes: ByteArray): ProtoMessage {
            var result = ProtoMessage()
            val reader = Proto.Reader(bytes)
            reader.forEachField { field, wireType ->
                when (field) {
                    1 -> reader.read(wireType, 2) { reader.readString() }?.let { result = result.copy(dataset = it) }
                    2 -> reader.read(wireType, 2) { reader.readString() }?.let { result = result.copy(row = it) }
                    3 -> reader.read(wireType, 2) { reader.readString() }?.let { result = result.copy(column = it) }
                    4 -> reader.read(wireType, 2) { reader.readString() }?.let { result = result.copy(value = it) }
                    else -> reader.skip(wireType)
                }
            }
            return result
        }
    }
}

class MessageEnvelope(val timestamp: String = "", val isEncrypted: Boolean = false, val content: ByteArray = ByteArray(0)) {
    fun encode(): ByteArray = Proto.Writer().apply {
        string(1, timestamp)
        bool(2, isEncrypted)
        bytes(3, content)
    }.toByteArray()

    companion object {
        fun decode(bytes: ByteArray): MessageEnvelope {
            var timestamp = ""
            var isEncrypted = false
            var content = ByteArray(0)
            val reader = Proto.Reader(bytes)
            reader.forEachField { field, wireType ->
                when (field) {
                    1 -> reader.read(wireType, 2) { reader.readString() }?.let { timestamp = it }
                    2 -> reader.read(wireType, 0) { reader.readBool() }?.let { isEncrypted = it }
                    3 -> reader.read(wireType, 2) { reader.readBytes() }?.let { content = it }
                    else -> reader.skip(wireType)
                }
            }
            return MessageEnvelope(timestamp, isEncrypted, content)
        }
    }
}

class SyncRequest(
    val messages: List<MessageEnvelope> = emptyList(),
    val fileId: String = "",
    val groupId: String = "",
    val keyId: String = "",
    val since: String = "",
) {
    fun encode(): ByteArray = Proto.Writer().apply {
        for (message in messages) message(1, message.encode())
        string(2, fileId)
        string(3, groupId)
        string(5, keyId)
        string(6, since)
    }.toByteArray()

    companion object {
        fun decode(bytes: ByteArray): SyncRequest {
            val messages = mutableListOf<MessageEnvelope>()
            var fileId = ""
            var groupId = ""
            var keyId = ""
            var since = ""
            val reader = Proto.Reader(bytes)
            reader.forEachField { field, wireType ->
                when (field) {
                    1 -> reader.read(wireType, 2) { reader.readBytes() }?.let { messages += MessageEnvelope.decode(it) }
                    2 -> reader.read(wireType, 2) { reader.readString() }?.let { fileId = it }
                    3 -> reader.read(wireType, 2) { reader.readString() }?.let { groupId = it }
                    5 -> reader.read(wireType, 2) { reader.readString() }?.let { keyId = it }
                    6 -> reader.read(wireType, 2) { reader.readString() }?.let { since = it }
                    else -> reader.skip(wireType)
                }
            }
            return SyncRequest(messages, fileId, groupId, keyId, since)
        }
    }
}

class SyncResponse(val messages: List<MessageEnvelope> = emptyList(), val merkle: String = "") {
    fun encode(): ByteArray = Proto.Writer().apply {
        for (message in messages) message(1, message.encode())
        string(2, merkle)
    }.toByteArray()

    companion object {
        fun decode(bytes: ByteArray): SyncResponse {
            val messages = mutableListOf<MessageEnvelope>()
            var merkle = ""
            val reader = Proto.Reader(bytes)
            reader.forEachField { field, wireType ->
                when (field) {
                    1 -> reader.read(wireType, 2) { reader.readBytes() }?.let { messages += MessageEnvelope.decode(it) }
                    2 -> reader.read(wireType, 2) { reader.readString() }?.let { merkle = it }
                    else -> reader.skip(wireType)
                }
            }
            return SyncResponse(messages, merkle)
        }
    }
}
