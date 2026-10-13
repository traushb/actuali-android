package io.github.traushb.actuali.services.sync

/**
 * MurmurHash3 (32-bit, x86) matching the JS murmurhash package (v3, seed=0),
 * used for CRDT Merkle tree hashing. Returns the hash as a signed Int, which
 * is what upstream's XOR arithmetic produces.
 */
object MurmurHash3 {
    fun hash(key: String, seed: Int = 0): Int = hash(key.toByteArray(Charsets.UTF_8), seed)

    fun hash(key: ByteArray, seed: Int = 0): Int {
        val length = key.size
        var h1 = seed
        val c1 = 0xcc9e2d51.toInt()
        val c2 = 0x1b873593

        // Body - process 4-byte chunks
        val nblocks = length / 4
        for (i in 0 until nblocks) {
            val o = i * 4
            var k1 = (key[o].toInt() and 0xff) or
                ((key[o + 1].toInt() and 0xff) shl 8) or
                ((key[o + 2].toInt() and 0xff) shl 16) or
                ((key[o + 3].toInt() and 0xff) shl 24)
            k1 *= c1
            k1 = Integer.rotateLeft(k1, 15)
            k1 *= c2
            h1 = h1 xor k1
            h1 = Integer.rotateLeft(h1, 13)
            h1 = h1 * 5 + 0xe6546b64.toInt()
        }

        // Tail - handle remaining bytes
        var k1 = 0
        val tail = nblocks * 4
        val rem = length and 3
        if (rem >= 3) k1 = k1 xor ((key[tail + 2].toInt() and 0xff) shl 16)
        if (rem >= 2) k1 = k1 xor ((key[tail + 1].toInt() and 0xff) shl 8)
        if (rem >= 1) {
            k1 = k1 xor (key[tail].toInt() and 0xff)
            k1 *= c1
            k1 = Integer.rotateLeft(k1, 15)
            k1 *= c2
            h1 = h1 xor k1
        }

        // Finalization
        h1 = h1 xor length
        h1 = h1 xor (h1 ushr 16)
        h1 *= 0x85ebca6b.toInt()
        h1 = h1 xor (h1 ushr 13)
        h1 *= 0xc2b2ae35.toInt()
        h1 = h1 xor (h1 ushr 16)
        return h1
    }
}
