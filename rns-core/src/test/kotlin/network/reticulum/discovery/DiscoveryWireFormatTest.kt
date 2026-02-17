package network.reticulum.discovery

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import network.reticulum.identity.Identity
import network.reticulum.transport.Transport
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.msgpack.core.MessagePack

@DisplayName("Discovery Wire Format")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DiscoveryWireFormatTest {

    private lateinit var announcer: InterfaceAnnouncer

    @BeforeAll
    fun setup() {
        // Ensure Transport has an identity for InterfaceAnnouncer
        try {
            Transport.start(Identity.create(), enableTransport = false)
        } catch (_: Exception) { /* already started */ }
        announcer = InterfaceAnnouncer()
    }

    @Test
    @DisplayName("Info dict round-trips through msgpack")
    fun `pack and unpack info dict`() {
        val info = mapOf<Int, Any?>(
            DiscoveryConstants.INTERFACE_TYPE to "TCPServerInterface",
            DiscoveryConstants.TRANSPORT to true,
            DiscoveryConstants.TRANSPORT_ID to ByteArray(16) { it.toByte() },
            DiscoveryConstants.NAME to "Test Interface",
            DiscoveryConstants.LATITUDE to 52.52,
            DiscoveryConstants.LONGITUDE to 13.405,
            DiscoveryConstants.HEIGHT to null,
            DiscoveryConstants.REACHABLE_ON to "192.168.1.1",
            DiscoveryConstants.PORT to 4242,
        )

        val packed = announcer.packInfoDict(info)
        packed shouldNotBe null

        // Unpack and verify
        val unpacker = MessagePack.newDefaultUnpacker(packed)
        val mapSize = unpacker.unpackMapHeader()
        mapSize shouldBe info.size

        val result = HashMap<Int, Any?>()
        repeat(mapSize) {
            val key = unpacker.unpackInt()
            val format = unpacker.nextFormat
            val value: Any? = when (format.valueType) {
                org.msgpack.value.ValueType.NIL -> { unpacker.unpackNil(); null }
                org.msgpack.value.ValueType.BOOLEAN -> unpacker.unpackBoolean()
                org.msgpack.value.ValueType.INTEGER -> unpacker.unpackInt()
                org.msgpack.value.ValueType.FLOAT -> unpacker.unpackDouble()
                org.msgpack.value.ValueType.STRING -> unpacker.unpackString()
                org.msgpack.value.ValueType.BINARY -> {
                    val len = unpacker.unpackBinaryHeader()
                    unpacker.readPayload(len)
                }
                else -> { unpacker.skipValue(); null }
            }
            result[key] = value
        }

        result[DiscoveryConstants.INTERFACE_TYPE] shouldBe "TCPServerInterface"
        result[DiscoveryConstants.TRANSPORT] shouldBe true
        result[DiscoveryConstants.NAME] shouldBe "Test Interface"
        result[DiscoveryConstants.LATITUDE] shouldBe 52.52
        result[DiscoveryConstants.REACHABLE_ON] shouldBe "192.168.1.1"
        result[DiscoveryConstants.PORT] shouldBe 4242
        result[DiscoveryConstants.HEIGHT] shouldBe null
    }

    @Test
    @DisplayName("Key 0xFF encodes as msgpack uint8 not fixint")
    fun `key 0xFF encodes correctly in msgpack`() {
        val info = mapOf<Int, Any?>(
            DiscoveryConstants.NAME to "test" // NAME = 0xFF
        )

        val packed = announcer.packInfoDict(info)

        // Unpack and verify the key reads back as 0xFF (255), not -1
        val unpacker = MessagePack.newDefaultUnpacker(packed)
        val mapSize = unpacker.unpackMapHeader()
        mapSize shouldBe 1
        val key = unpacker.unpackInt()
        key shouldBe 0xFF
    }

    @Test
    @DisplayName("Flags byte + stamp layout matches expected format")
    fun `payload layout is flags + packed + stamp`() {
        val flags: Byte = 0x00
        val packed = "test_packed_data".toByteArray()
        val stamp = ByteArray(Stamper.STAMP_SIZE) { 0xAB.toByte() }

        val payload = byteArrayOf(flags) + packed + stamp

        payload[0] shouldBe 0x00.toByte()
        val data = payload.copyOfRange(1, payload.size)
        val extractedStamp = data.copyOfRange(data.size - Stamper.STAMP_SIZE, data.size)
        val extractedPacked = data.copyOfRange(0, data.size - Stamper.STAMP_SIZE)

        extractedStamp shouldBe stamp
        extractedPacked shouldBe packed
    }
}
