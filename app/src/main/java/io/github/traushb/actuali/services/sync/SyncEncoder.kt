package io.github.traushb.actuali.services.sync

import io.github.traushb.actuali.services.AppJson
import kotlinx.serialization.json.JsonObject

sealed class SyncEncoderError : Exception() {
    object EncodingFailed : SyncEncoderError()
    object DecodingFailed : SyncEncoderError()
    object EncryptionRequired : SyncEncoderError()
    object InvalidTimestamp : SyncEncoderError()
    object InvalidMerkle : SyncEncoderError()
}

/** Encodes/decodes sync messages to/from Protobuf. */
class SyncEncoder(private val encryptionKey: SymmetricKey? = null) {
    fun encode(messages: List<CRDTMessage>, fileId: String, groupId: String, keyId: String?, since: String): ByteArray {
        val envelopes = messages.map { msg ->
            val inner = ProtoMessage(msg.dataset, msg.row, msg.column, msg.value).encode()
            // Encrypt if key is available
            if (encryptionKey != null) {
                MessageEnvelope(msg.timestamp.toString(), true, SyncEncryption.encrypt(inner, encryptionKey).encode())
            } else {
                MessageEnvelope(msg.timestamp.toString(), false, inner)
            }
        }
        return SyncRequest(envelopes, fileId, groupId, keyId ?: "", since).encode()
    }

    data class Decoded(val messages: List<CRDTMessage>, val merkle: MerkleNode)

    fun decode(data: ByteArray): Decoded {
        val response = try {
            SyncResponse.decode(data)
        } catch (_: Proto.DecodeError) {
            throw SyncEncoderError.DecodingFailed
        }

        // Parse merkle tree from JSON (server sends hash as signed Int32)
        val merkle = runCatching { MerkleNode.fromJson(AppJson.parseToJsonElement(response.merkle) as JsonObject) }
            .getOrElse { throw SyncEncoderError.InvalidMerkle }

        val messages = response.messages.map { envelope ->
            val innerData = if (envelope.isEncrypted) {
                val key = encryptionKey ?: throw SyncEncoderError.EncryptionRequired
                val encrypted = try {
                    EncryptedData.decode(envelope.content)
                } catch (_: Proto.DecodeError) {
                    throw SyncEncoderError.DecodingFailed
                }
                SyncEncryption.decrypt(encrypted, key)
            } else {
                envelope.content
            }
            val inner = try {
                ProtoMessage.decode(innerData)
            } catch (_: Proto.DecodeError) {
                throw SyncEncoderError.DecodingFailed
            }
            val timestamp = HLCTimestamp.parse(envelope.timestamp) ?: throw SyncEncoderError.InvalidTimestamp
            CRDTMessage(timestamp, inner.dataset, inner.row, inner.column, inner.value)
        }
        return Decoded(messages, merkle)
    }
}
