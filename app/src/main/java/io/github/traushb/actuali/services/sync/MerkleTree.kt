package io.github.traushb.actuali.services.sync

import io.github.traushb.actuali.services.longOrNullValue
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * A node in the Merkle trie. Uses ternary branches ("0", "1", "2") based on
 * the base-3 encoding of minutes. Immutable: every edit returns a new tree.
 */
data class MerkleNode(
    /** Signed, as the server sends it. */
    val hash: Int = 0,
    val children: Map<String, MerkleNode> = emptyMap(),
) {
    /** `{"hash": n, "0": {...}, "1": {...}, "2": {...}}`, upstream's trie JSON. */
    fun toJson(): JsonObject {
        val fields = linkedMapOf<String, JsonElement>("hash" to JsonPrimitive(hash))
        for (key in children.keys.sorted()) fields[key] = children.getValue(key).toJson()
        return JsonObject(fields)
    }

    companion object {
        val empty = MerkleNode()

        /** Lenient like the Swift decoder: a missing hash is 0, an unreadable child is dropped. */
        fun fromJson(json: JsonObject): MerkleNode {
            val raw = json["hash"].longOrNullValue
            if (raw != null && (raw < Int.MIN_VALUE || raw > Int.MAX_VALUE)) {
                throw IllegalArgumentException("merkle hash out of Int32 range")
            }
            val children = buildMap {
                for (key in listOf("0", "1", "2")) {
                    val child = json[key] as? JsonObject ?: continue
                    runCatching { fromJson(child) }.getOrNull()?.let { put(key, it) }
                }
            }
            return MerkleNode(raw?.toInt() ?: 0, children)
        }
    }
}

/**
 * Merkle trie for efficient sync diffing. Timestamps are mapped to paths
 * using base-3 encoding of minutes since epoch.
 */
data class MerkleTree(val root: MerkleNode = MerkleNode.empty) {
    /** Insert a timestamp: XORs its hash into every node along its minute's path. */
    fun inserting(timestamp: HLCTimestamp): MerkleTree = folding(timestamp.hash(), timestamp.millis)

    private fun folding(hash: Int, millis: Long): MerkleTree =
        MerkleTree(insertKey(root.copy(hash = root.hash xor hash), minuteKey(millis), hash))

    private fun insertKey(node: MerkleNode, key: String, hash: Int): MerkleNode {
        if (key.isEmpty()) return node
        val first = key.substring(0, 1)
        val existing = node.children[first] ?: MerkleNode.empty
        val child = insertKey(existing.copy(hash = existing.hash xor hash), key.substring(1), hash)
        return node.copy(children = node.children + (first to child))
    }

    /**
     * Find the earliest divergence point between two tries. Returns null if
     * trees are in sync, otherwise milliseconds since epoch.
     */
    fun diff(other: MerkleTree): Long? {
        if (root.hash == other.root.hash) return null
        var node1 = root
        var node2 = other.root
        var path = ""
        while (true) {
            val keys = (node1.children.keys + node2.children.keys).sorted()
            var diffKey: String? = null
            // Traverse down the keys in order
            for (key in keys) {
                // If one side is missing, we can't traverse further. This
                // handles pruning - we don't know if we've pruned off a changed key.
                val c1 = node1.children[key] ?: break
                val c2 = node2.children[key] ?: break
                if (c1.hash != c2.hash) {
                    diffKey = key
                    break
                }
            }
            // No differing key found - the bottom of the tree or a pruned key
            val dk = diffKey ?: return keyToTimestamp(path)
            path += dk
            node1 = node1.children[dk] ?: MerkleNode.empty
            node2 = node2.children[dk] ?: MerkleNode.empty
        }
    }

    /** Prune old branches, keeping only the last [n] keys at each level (Actual keeps 2). */
    fun pruned(n: Int = 2): MerkleTree = MerkleTree(pruneNode(root, n))

    private fun pruneNode(node: MerkleNode, n: Int): MerkleNode {
        if (node.children.isEmpty()) return node
        val keep = node.children.keys.sorted().takeLast(n)
        return MerkleNode(node.hash, keep.associateWith { pruneNode(node.children.getValue(it), n) })
    }

    /** Matches the TypeScript: Number(Math.floor(timestamp.millis() / 1000 / 60)).toString(3). */
    private fun minuteKey(millis: Long): String = (millis / 1000 / 60).toString(3)

    /** Pads the key to 16 base-3 digits (full precision) before converting back to millis. */
    private fun keyToTimestamp(key: String): Long {
        val padded = key + "0".repeat(maxOf(0, 16 - key.length))
        val minutes = padded.toLongOrNull(3) ?: 0
        return minutes * 60 * 1000
    }

    companion object {
        /**
         * Build a trie from minute buckets, where each entry maps a time to the
         * XOR of every message hash falling in that time's minute.
         *
         * Equivalent to `inserting` per message: every timestamp within a minute
         * walks the identical path, and XOR is associative, so folding a
         * minute's combined hash in one walk lands the same value in every node
         * along it.
         */
        fun building(minuteBuckets: Map<Long, Int>): MerkleTree {
            var tree = MerkleTree()
            for ((millis, hash) in minuteBuckets) tree = tree.folding(hash, millis)
            return tree
        }
    }
}
