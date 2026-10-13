package io.github.traushb.actuali.services.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Fixture-based tests proving the sync engine is bit-identical to the
// upstream JS implementation (actual/packages/crdt/src/crdt/). The vectors
// are the iOS suite's, which cites the provenance of each one.

class MurmurHash3FixtureTest {
    private fun unsigned(value: Int): Long = value.toLong() and 0xffffffffL

    @Test
    fun timestampStringVectors() {
        val vectors = listOf(
            "2018-11-12T13:21:40.122Z-0000-0123456789ABCDEF" to 1_983_295_247L,
            "2018-11-13T13:21:40.122Z-0000-0123456789ABCDEF" to 1_469_038_940L,
            "1970-01-01T00:00:00.000Z-0000-0000000000000000" to 4_179_357_717L,
            "2015-04-24T22:23:42.123Z-1000-0123456789ABCDEF" to 2_838_536_857L,
            "9999-12-31T23:59:59.999Z-FFFF-FFFFFFFFFFFFFFFF" to 1_359_285_735L,
            "2019-06-03T16:40:53.876Z-0000-9f66d38cba0ef956" to 779_909_595L,
        )
        for ((input, expected) in vectors) assertEquals("hash mismatch for $input", expected, unsigned(MurmurHash3.hash(input)))
    }

    @Test
    fun tailLengthVectors() {
        val vectors = listOf("" to 0L, "abc" to 3_017_643_002L, "abcd" to 1_139_631_978L, "abcde" to 3_902_511_862L, "abcdef" to 1_635_893_381L)
        for ((input, expected) in vectors) assertEquals("hash mismatch for $input", expected, unsigned(MurmurHash3.hash(input)))
    }

    @Test
    fun nonASCIIVectors() {
        assertEquals(605_818_632L, unsigned(MurmurHash3.hash("café")))
        assertEquals(2_779_017_879L, unsigned(MurmurHash3.hash("日本語")))
    }
}

class HLCTimestampFixtureTest {
    @Test
    fun parseRoundTripValidInputs() {
        for (input in listOf(
            "1970-01-01T00:00:00.000Z-0000-0000000000000000",
            "2015-04-24T22:23:42.123Z-1000-0123456789ABCDEF",
            "9999-12-31T23:59:59.999Z-FFFF-FFFFFFFFFFFFFFFF",
        )) {
            assertEquals("round trip mismatch for $input", input, HLCTimestamp.parse(input)?.toString())
        }
    }

    @Test
    fun parsedFieldValues() {
        val parsed = HLCTimestamp.parse("2015-04-24T22:23:42.123Z-1000-0123456789ABCDEF")!!
        assertEquals(1_429_914_222_123L, parsed.millis)
        assertEquals(0x1000, parsed.counter)
        assertEquals("0123456789ABCDEF", parsed.node)
    }

    @Test
    fun parseRejectsInvalidInputs() {
        for (input in listOf(
            "",
            " ",
            "0",
            "invalid",
            "1969-1-1T0:0:0.0Z-0-0-0",
            "1969-01-01T00:00:00.000Z-0000-0000000000000000",
            "10000-01-01T00:00:00.000Z-FFFF-FFFFFFFFFFFFFFFF",
            "9999-12-31T23:59:59.999Z-10000-FFFFFFFFFFFFFFFF",
            "9999-12-31T23:59:59.999Z-FFFF-10000000000000000",
        )) {
            assertNull("should not parse $input", HLCTimestamp.parse(input))
        }
    }

    @Test
    fun zeroAndMaxFormat() {
        assertEquals("1970-01-01T00:00:00.000Z-0000-0000000000000000", HLCTimestamp.zero.toString())
        assertEquals("9999-12-31T23:59:59.999Z-FFFF-FFFFFFFFFFFFFFFF", HLCTimestamp.max.toString())
    }

    @Test
    fun counterAndNodePadding() {
        assertEquals("2018-11-12T13:21:40.122Z-1000-0000000000000ABC", HLCTimestamp(1_542_028_900_122, 0x1000, "ABC").toString())
        assertEquals("1970-01-01T00:00:00.000Z-0000-0123456789abcdef", HLCTimestamp(0, 0, "0123456789abcdef").toString())
        assertEquals("1970-01-01T00:00:00.000Z-FFFF-0123456789abcdef", HLCTimestamp(0, 0xffff, "0123456789abcdef").toString())
    }

    @Test
    fun sinceFormat() {
        assertEquals("2017-01-01T00:00:00.000Z-0000-0000000000000000", HLCTimestamp.since("2017-01-01T00:00:00.000Z"))
    }

    @Test
    fun timestampHashMatchesUpstream() {
        val vectors = listOf(
            "2018-11-12T13:21:40.122Z-0000-0123456789ABCDEF" to 1_983_295_247L,
            "2019-06-03T16:40:53.876Z-0000-9f66d38cba0ef956" to 779_909_595L,
            "1970-01-01T00:00:00.000Z-0000-0000000000000000" to 4_179_357_717L,
            "9999-12-31T23:59:59.999Z-FFFF-FFFFFFFFFFFFFFFF" to 1_359_285_735L,
        )
        for ((input, unsignedHash) in vectors) {
            assertEquals("hash mismatch for $input", unsignedHash.toInt(), HLCTimestamp.parse(input)!!.hash())
        }
    }

    @Test
    fun ordering() {
        val mid = HLCTimestamp.parse("2015-04-24T22:23:42.123Z-1000-0123456789ABCDEF")!!
        assertTrue(HLCTimestamp.zero < mid)
        assertTrue(mid < HLCTimestamp.max)
        assertTrue(HLCTimestamp.zero < HLCTimestamp.max)
    }
}

class MerkleTreeFixtureTest {
    private fun ts(string: String) = HLCTimestamp.parse(string)!!

    /** JS JSON.stringify(trie) order: "0","1","2" ascending, then "hash". */
    private fun canonicalJSON(node: MerkleNode): String {
        val parts = node.children.keys.sorted().map { "\"$it\":${canonicalJSON(node.children.getValue(it))}" } + "\"hash\":${node.hash}"
        return "{" + parts.joinToString(",") + "}"
    }

    @Test
    fun insertProducesUpstreamSnapshotStructure() {
        val tree = MerkleTree()
            .inserting(ts("2018-11-12T13:21:40.122Z-0000-0123456789ABCDEF"))
            .inserting(ts("2018-11-13T13:21:40.122Z-0000-0123456789ABCDEF"))
        val expected = """{"1":{"2":{"1":{"0":{"1":{"0":{"0":{"2":{"0":{"1":{"1":{"0":{"2":{"2":{"0":{"0":{"hash":1983295247},"hash":1983295247},"hash":1983295247},"hash":1983295247},"hash":1983295247},"hash":1983295247},"hash":1983295247},"hash":1983295247},"1":{"0":{"1":{"0":{"2":{"0":{"0":{"0":{"hash":1469038940},"hash":1469038940},"hash":1469038940},"hash":1469038940},"hash":1469038940},"hash":1469038940},"hash":1469038940},"hash":1469038940},"hash":565800531},"hash":565800531},"hash":565800531},"hash":565800531},"hash":565800531},"hash":565800531},"hash":565800531},"hash":565800531},"hash":565800531}"""
        assertEquals(expected, canonicalJSON(tree.root))
        assertEquals(565_800_531, tree.root.hash)
    }

    @Test
    fun diffReturnsCorrectTimeDifference() {
        val messages = listOf(
            ts("2018-11-13T13:20:40.122Z-0000-0123456789ABCDEF"),
            ts("2018-11-14T13:05:35.122Z-0000-0123456789ABCDEF"),
            ts("2018-11-15T22:19:00.122Z-0000-0123456789ABCDEF"),
            ts("2018-11-20T13:19:40.122Z-0000-0123456789ABCDEF"),
            ts("2018-11-25T13:19:40.122Z-0000-0123456789ABCDEF"),
        )
        var trie1 = MerkleTree().inserting(messages[0]).inserting(messages[1]).inserting(messages[2])
        assertEquals(1_562_158_574, trie1.root.hash)
        var trie2 = MerkleTree().inserting(messages[3]).inserting(messages[4])
        assertEquals(-1_230_958_401, trie2.root.hash)
        assertEquals(1_541_178_900_000L, trie1.diff(trie2))

        trie1 = trie1.inserting(messages[3]).inserting(messages[4])
        trie2 = trie2.inserting(messages[0]).inserting(messages[1]).inserting(messages[2])
        assertEquals(-339_888_815, trie1.root.hash)
        assertEquals(trie1.root.hash, trie2.root.hash)
        assertNull(trie1.diff(trie2))
    }

    @Test
    fun diffWithEmptyTrie() {
        val empty = MerkleTree()
        val populated = MerkleTree().inserting(ts("2009-01-02T10:17:37.789Z-0000-0000testinguuid1"))
        assertEquals(0L, empty.diff(populated))
        assertEquals(0L, populated.diff(empty))
        assertEquals(0, empty.root.hash)
        assertNull(empty.diff(MerkleTree()))
    }

    private val pruneScenarioMessages = listOf(
        "2018-11-01T01:00:00.000Z", "2018-11-01T01:09:00.000Z", "2018-11-01T01:18:00.000Z",
        "2018-11-01T01:27:00.000Z", "2018-11-01T01:36:00.000Z", "2018-11-01T01:45:00.000Z",
        "2018-11-01T01:54:00.000Z", "2018-11-01T02:03:00.000Z", "2018-11-01T02:10:00.000Z",
        "2018-11-01T02:19:00.000Z", "2018-11-01T02:28:00.000Z", "2018-11-01T02:37:00.000Z",
    ).map { ts("$it-0000-0123456789ABCDEF") }

    private fun pruneScenarioTree(): MerkleTree = pruneScenarioMessages.fold(MerkleTree()) { tree, m -> tree.inserting(m) }

    @Test
    fun pruningKeepsCorrectHashes() {
        val tree = pruneScenarioTree()
        assertEquals(345_045_312, tree.root.hash)
        val pruned = tree.pruned()
        assertEquals(345_045_312, pruned.root.hash)
        val expected = """{"1":{"2":{"1":{"0":{"0":{"2":{"2":{"2":{"1":{"2":{"2":{"0":{"1":{"1":{"2":{"0":{"hash":-1718969198},"hash":-1718969198},"hash":-1718969198},"2":{"2":{"0":{"hash":384820918},"hash":384820918},"hash":384820918},"hash":1710637055},"2":{"1":{"2":{"0":{"hash":-497345306},"hash":-497345306},"hash":-497345306},"2":{"2":{"0":{"hash":1003725159},"hash":1003725159},"hash":1003725159},"hash":613353200},"hash":1710746760},"1":{"0":{"1":{"1":{"1":{"hash":-666153754},"hash":-666153754},"hash":-666153754},"2":{"1":{"1":{"hash":748821548},"hash":748821548},"hash":748821548},"hash":703357274},"1":{"0":{"1":{"1":{"hash":1485534354},"hash":1485534354},"hash":1485534354},"hash":1485534354},"hash":1902581192},"hash":345045312},"hash":345045312},"hash":345045312},"hash":345045312},"hash":345045312},"hash":345045312},"hash":345045312},"hash":345045312},"hash":345045312},"hash":345045312},"hash":345045312},"hash":345045312}"""
        assertEquals(expected, canonicalJSON(pruned.root))
    }

    @Test
    fun diffingDifferentlyShapedTries() {
        val tree = pruneScenarioTree()
        assertEquals(0L, MerkleTree().diff(tree))
        assertEquals(0L, tree.diff(MerkleTree()))

        val trie1 = tree.inserting(ts("2018-11-01T00:59:00.000Z-0000-0123456789ABCDEF"))
        assertEquals(1_541_033_640_000L, trie1.diff(tree))
        assertEquals(1_541_033_100_000L, trie1.pruned().diff(tree))
        assertEquals(1_541_033_100_000L, trie1.diff(tree.pruned()))
        assertEquals(1_541_033_100_000L, trie1.pruned().diff(tree.pruned()))

        val trie2 = tree
            .inserting(ts("2018-11-01T00:59:00.000Z-0000-0123456789ABCDEF"))
            .inserting(ts("2018-11-01T01:15:00.000Z-0000-0123456789ABCDEF"))
        assertEquals(1_541_033_640_000L, trie2.diff(tree))
        assertEquals(1_541_033_100_000L, trie2.pruned().diff(tree))
        assertEquals(1_541_033_100_000L, trie2.diff(tree.pruned()))
        assertEquals(1_541_034_720_000L, trie2.pruned().diff(tree.pruned()))
    }

    @Test
    fun base3MinuteKeyDerivation() {
        val tree = MerkleTree().inserting(ts("2018-11-12T13:21:40.122Z-0000-0123456789ABCDEF"))
        var node = tree.root
        for (key in "1210100201102200") {
            val child = node.children[key.toString()]
            assertNotNull("missing trie child for key $key", child)
            assertEquals(1, node.children.size)
            node = child!!
        }
        assertTrue(node.children.isEmpty())
        assertEquals(1_983_295_247, node.hash)
    }

    @Test
    fun minuteArithmeticMatchesDateParsing() {
        for (string in listOf(
            "1970-01-01T00:00:00.000Z-0000-0123456789ABCDEF",
            "1970-01-01T00:00:59.999Z-FFFF-0123456789ABCDEF",
            "1999-12-31T23:59:00.000Z-0000-0123456789ABCDEF",
            "2000-02-29T12:34:56.789Z-0000-0123456789ABCDEF",
            "2018-11-01T00:45:00.000Z-0000-0123456789ABCDEF",
            "2024-02-29T23:59:59.999Z-0000-0123456789ABCDEF",
            "2026-03-01T00:00:00.000Z-0000-0123456789ABCDEF",
            "2026-07-22T10:00:00.000Z-0000-a1b2c3d4e5f60718",
            "2026-12-31T23:59:59.999Z-0000-0123456789ABCDEF",
            "2100-03-01T00:00:00.000Z-0000-0123456789ABCDEF",
        )) {
            val parsed = HLCTimestamp.parse(string)!!
            assertEquals(string, parsed.millis / 1000 / 60, HLCTimestamp.minutesSinceEpoch(string))
        }
    }

    @Test
    fun minuteArithmeticRejectsMalformedTimestamps() {
        assertNull(HLCTimestamp.minutesSinceEpoch(""))
        assertNull(HLCTimestamp.minutesSinceEpoch("2026-07-22"))
        assertNull(HLCTimestamp.minutesSinceEpoch("not-a-timestamp-at-all"))
        assertNull(HLCTimestamp.minutesSinceEpoch("2026-13-22T10:00:00.000Z-0000-0"))
        assertNull(HLCTimestamp.minutesSinceEpoch("2026-07-22T99:00:00.000Z-0000-0"))
    }

    @Test
    fun bulkBuildMatchesPerMessageInsertion() {
        val timestamps = listOf(
            "2018-11-01T00:47:12.000Z-0000-0123456789ABCDEF",
            "2018-11-01T00:47:39.000Z-0001-0123456789ABCDEF",
            "2018-11-01T00:47:39.500Z-0000-FEDCBA9876543210",
            "2018-11-01T01:15:00.000Z-0000-0123456789ABCDEF",
            "2018-11-12T13:21:40.122Z-0000-0123456789ABCDEF",
        ).map(::ts)
        var incremental = MerkleTree()
        val buckets = HashMap<Long, Int>()
        for (timestamp in timestamps) {
            incremental = incremental.inserting(timestamp)
            val minute = timestamp.millis / 60000 * 60000
            buckets[minute] = (buckets[minute] ?: 0) xor timestamp.hash()
        }
        val bulk = MerkleTree.building(buckets)
        assertEquals(incremental.root, bulk.root)
        assertNull(bulk.diff(incremental))
    }
}

class SyncEncoderFixtureTest {
    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

    @Test
    fun messageEncodesToUpstreamBytes() {
        val inner = ProtoMessage("accounts", "r1", "name", "S:Checking")
        assertEquals("0a086163636f756e7473120272311a046e616d65220a533a436865636b696e67", inner.encode().hex())
    }

    @Test
    fun syncRequestEncodesToUpstreamBytes() {
        val message = CRDTMessage(
            HLCTimestamp.parse("2019-06-03T16:40:53.876Z-0000-9f66d38cba0ef956")!!, "accounts", "r1", "name", "S:Checking",
        )
        val data = SyncEncoder(null).encode(listOf(message), "file-1", "group-1", null, "2017-01-01T00:00:00.000Z-0000-0000000000000000")
        val expectedHex = "0a520a2e323031392d30362d30335431363a34303a35332e3837365a2d303030302d39663636" +
            "6433386362613065663935361a200a086163636f756e7473120272311a046e616d65220a533a436865636b" +
            "696e67120666696c652d311a0767726f75702d31322e323031372d30312d30315430303a30303a30302e3030" +
            "305a2d303030302d30303030303030303030303030303030"
        assertEquals(expectedHex, data.hex())
    }

    @Test
    fun plaintextRoundTrip() {
        val timestamp = HLCTimestamp.parse("2019-06-03T16:40:53.876Z-0000-9f66d38cba0ef956")!!
        val original = CRDTMessage(timestamp, "transactions", "8f6a9a52-906b-4e3c-bd09-621bd11b3c33", "amount", "N:-1050")
        val inner = ProtoMessage(original.dataset, original.row, original.column, original.value).encode()
        val response = SyncResponse(
            listOf(MessageEnvelope(original.timestamp.toString(), false, inner)),
            """{"hash":565800531,"1":{"hash":565800531}}""",
        )
        val decoded = SyncEncoder(null).decode(response.encode())
        assertEquals(listOf(original), decoded.messages)
        assertEquals(565_800_531, decoded.merkle.hash)
        assertEquals(565_800_531, decoded.merkle.children["1"]?.hash)
    }

    @Test
    fun encryptedRoundTrip() {
        val key = SymmetricKey(ByteArray(32) { it.toByte() })
        val timestamp = HLCTimestamp.parse("2019-06-03T16:40:53.876Z-0000-9f66d38cba0ef956")!!
        val original = CRDTMessage(timestamp, "accounts", "acct-1", "name", "S:Chécking ✓")
        val encoder = SyncEncoder(key)
        val requestData = encoder.encode(listOf(original), "file-1", "group-1", "key-1", HLCTimestamp.since("1970-01-01T00:00:00.000Z"))
        val request = SyncRequest.decode(requestData)
        assertEquals(1, request.messages.size)
        assertTrue(request.messages[0].isEncrypted)

        val decoded = encoder.decode(SyncResponse(request.messages, """{"hash":0}""").encode())
        assertEquals(listOf(original), decoded.messages)
    }

    @Test
    fun encodesKeyIdAndEncryptsWhenKeyPresent() {
        val message = CRDTMessage(HLCTimestamp.parse("2026-06-24T00:00:00.000Z-0000-0123456789abcdef")!!, "transactions", "row-1", "amount", "S:100")
        val request = SyncRequest.decode(SyncEncoder(SymmetricKey(ByteArray(32))).encode(listOf(message), "f1", "g1", "kid-1", "0"))
        assertEquals("kid-1", request.keyId)
        assertTrue(request.messages.first().isEncrypted)
        val plain = SyncRequest.decode(SyncEncoder().encode(listOf(message), "f1", "g1", null, "0"))
        assertNotEquals(true, plain.messages.first().isEncrypted)
    }
}

class CRDTValueTest {
    @Test
    fun serializesLikeTheSwiftClient() {
        assertEquals("0:", CRDTValue.serialize(null))
        assertEquals("N:1234", CRDTValue.serialize(1234))
        assertEquals("N:-1050", CRDTValue.serialize(-1050L))
        assertEquals("N:1", CRDTValue.serialize(true))
        assertEquals("S:text", CRDTValue.serialize("text"))
        // Swift's Double.description: positional, ".0" on whole values.
        assertEquals("N:1728550000000.123", CRDTValue.serialize(1728550000000.123))
        assertEquals("N:1.0", CRDTValue.serialize(1.0))
        assertEquals("N:-37.8136", CRDTValue.serialize(-37.8136))
        assertEquals("N:1e-05", CRDTValue.serialize(0.00001))
    }

    @Test
    fun deserializesToBindableValues() {
        assertNull(CRDTValue.deserialize("0:"))
        assertEquals(1234L, CRDTValue.deserialize("N:1234"))
        assertEquals(12.5, CRDTValue.deserialize("N:12.5"))
        assertEquals("hello", CRDTValue.deserialize("S:hello"))
        assertNull(CRDTValue.deserialize("N"))
    }
}
