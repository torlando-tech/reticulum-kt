# Testing Patterns

**Analysis Date:** 2026-01-23

## Test Framework

**Runner:**
- JUnit 5 (Jupiter) - test framework
- Kotest 5.8.0 - assertion library and test runner plugin
- Config: `build.gradle.kts` configures `useJUnitPlatform()`

**Assertion Library:**
- Kotest assertions: `io.kotest.matchers.shouldBe`
- Kotlin stdlib: `kotlin.test.assertEquals`, `kotlin.test.assertTrue`, `kotlin.test.assertNotNull`
- JUnit assertions: `org.junit.jupiter.api.Assertions.*` (deprecated in favor of kotlin.test)

**Run Commands:**
```bash
./gradlew test                    # Run all tests
./gradlew test --watch           # Watch mode (if supported)
./gradlew test --info            # Verbose test output
./gradlew :rns-core:test         # Run tests for specific module
```

## Test File Organization

**Location:**
- Co-located with source: `src/test/kotlin/network/reticulum/*/` mirrors `src/main/kotlin/network/reticulum/*/`
- Shared test base in: `rns-test/src/test/kotlin/network/reticulum/interop/`

**Naming:**
- `*Test.kt` suffix for test classes: `ChannelIntegrationTest.kt`, `HashInteropTest.kt`, `LocalInterfaceTest.kt`
- Interop tests (Python bridge): `rns-test/src/test/kotlin/network/reticulum/interop/crypto/`, `rns-test/src/test/kotlin/network/reticulum/interop/channel/`
- Integration tests: `rns-test/src/test/kotlin/network/reticulum/integration/`

**Structure:**
```
rns-core/src/test/kotlin/network/reticulum/
├── announce/
│   └── AnnounceValidationTest.kt
├── ... (mirrors main source structure)

rns-interfaces/src/test/kotlin/network/reticulum/interfaces/
├── local/
│   ├── LocalInterfaceTest.kt
│   └── SharedInstanceRoutingTest.kt
├── udp/
│   └── UDPInterfaceTest.kt

rns-test/src/test/kotlin/network/reticulum/
├── integration/
│   ├── ChannelIntegrationTest.kt
│   ├── LinkIntegrationTest.kt
│   ├── ResourceIntegrationTest.kt
│   └── TcpIntegrationTest.kt
├── interop/
│   ├── InteropTestBase.kt
│   ├── announce/
│   │   └── AnnounceInteropTest.kt
│   ├── crypto/
│   │   ├── HashInteropTest.kt
│   │   ├── HkdfInteropTest.kt
│   │   ├── TokenInteropTest.kt
│   │   └── X25519InteropTest.kt
│   ├── channel/
│   └── destination/
```

## Test Structure

**Suite Organization:**
```kotlin
@DisplayName("Channel Integration Tests")
class ChannelIntegrationTest {

    @Test
    @DisplayName("Envelope can pack and unpack messages")
    @Timeout(5)
    fun `envelope can pack and unpack messages`() {
        // Setup
        val outlet = MockChannelOutlet()
        val testMessage = TestMessage().apply {
            content = "Hello, Channel!"
        }

        // Execute
        val envelope = Envelope(outlet, message = testMessage, sequence = 42)
        val packed = envelope.pack()
        val unpacked = Envelope(outlet, raw = packed)
        val received = unpacked.unpack(factories)

        // Verify
        assertTrue(received is TestMessage)
        assertEquals("Hello, Channel!", (received as TestMessage).content)
        assertEquals(42, unpacked.sequence)
    }
}
```

**Patterns:**
- `@DisplayName` provides human-readable test descriptions
- `@Timeout` enforces test execution time limits (prevents hanging)
- Backtick method names for readable test output: `` `channel can send and receive messages` ``
- Setup-Execute-Verify pattern (Arrange-Act-Assert)

## Mocking

**Framework:** Manual mock objects and test doubles (no mockito or mock library)

**Patterns:**
```kotlin
// From ChannelIntegrationTest.kt
val outlet = MockChannelOutlet()

// From InteropTestBase.kt - Python bridge mocking
protected fun python(command: String, vararg params: Pair<String, Any?>): JsonObject {
    val paramMap = params.mapNotNull { (key, value) ->
        when (value) {
            null -> null
            is ByteArray -> key to value.toHex()
            is String -> key to value
            is Int -> key to value
            else -> key to value.toString()
        }
    }.toMap()
    return bridge.executeSuccess(command, paramMap)
}
```

**What to Mock:**
- Network interfaces in unit tests (use `MockChannelOutlet`, `AtomicReference` for callbacks)
- External systems in integration tests (Python bridge via `PythonBridge`)
- Callbacks and state changes via latches and atomic references

**What NOT to Mock:**
- Core domain objects: test real `Channel`, `Link`, `Identity` instances
- Cryptographic operations: always test real crypto (critical for correctness)
- Serialization (MessagePack): test real serialization round-trips
- Constants and enums: no mocking needed

**Example from LocalInterfaceTest.kt:**
```kotlin
private var server: LocalServerInterface? = null
private val clients = mutableListOf<LocalClientInterface>()

@AfterEach
fun tearDown() {
    clients.forEach { it.detach() }
    clients.clear()
    server?.detach()
    server = null
    Thread.sleep(100)
}

@Test
fun `test client connects to server via TCP`() {
    val tcpPort = 37428
    server = LocalServerInterface(name = "TestServer", tcpPort = tcpPort)
    server!!.start()

    val client = LocalClientInterface(name = "TestClient", tcpPort = tcpPort)
    clients.add(client)
    client.start()

    Thread.sleep(200)

    assertTrue(server!!.online.get())
    assertTrue(client.online.get())
    assertEquals(1, server!!.clientCount())
}
```

## Fixtures and Factories

**Test Data:**
```kotlin
// From ResourceIntegrationTest.kt
fun createMockAdvertisement(flags: Byte = 0x00): ByteArray {
    val output = ByteArrayOutputStream()
    // Pack advertisement data
    // ...
    return output.toByteArray()
}

// From ChannelIntegrationTest.kt
class TestMessage : MessageBase() {
    companion object {
        const val MSG_TYPE = 0x0001
    }
    var content: String = ""
    // ...
}

// From HashInteropTest.kt
val testCases = listOf(
    ByteArray(0),
    "Hello, Reticulum!".toByteArray(),
    ByteArray(32) { it.toByte() },
    ByteArray(1000) { (it % 256).toByte() }
)
```

**Location:**
- Shared test utilities in `rns-test/src/test/kotlin/network/reticulum/interop/`
- Test doubles (mocks) defined inline in test classes: `MockChannelOutlet`
- Factory functions for test messages within test file or shared base class

## Coverage

**Requirements:** No explicit coverage enforced; coverage should be validated via CI/CD if configured

**View Coverage:**
```bash
./gradlew test --info         # Shows test execution
# Gradle can be configured for Jacoco coverage:
./gradlew jacocoTestReport    # If Jacoco plugin added
```

**Current Coverage Areas:**
- Unit tests for core crypto operations (Hash, HKDF, X25519, Token)
- Integration tests for network communication (Link, Channel, Resource)
- Interoperability tests with Python reference implementation
- Interface tests (UDP, TCP, Local IPC)

## Test Types

**Unit Tests:**
- Crypto functions: `HashInteropTest.kt`, `X25519InteropTest.kt`, `TokenInteropTest.kt`
- Scope: Individual functions and small components
- Approach: Direct function calls, validate return values, test edge cases
- Example: Test hash output matches Python for various input sizes

**Integration Tests:**
- Network communication: `ChannelIntegrationTest.kt`, `LinkIntegrationTest.kt`
- Scope: Multiple components working together (e.g., Channel + Outlet, Link + Transport)
- Approach: Create full communication scenarios, verify end-to-end behavior
- Example: Create link, establish encryption, send/receive packets

**Interop Tests:**
- Python bridge validation: `rns-test/src/test/kotlin/network/reticulum/interop/`
- Scope: Kotlin implementation vs. Python reference implementation
- Approach: Execute operations in both, compare byte-for-byte results
- Example: Compute SHA-256 in Kotlin, call Python via bridge, assert hashes match
- Requires environment variable: `PYTHON_RNS_PATH` pointing to Python Reticulum repo

**E2E Tests:**
- Not extensively used; integration tests serve as E2E for protocol validation
- `PythonInteropTest.kt` can trigger tunnel server scenarios if `PYTHON_SERVER_RUNNING=true`

## Common Patterns

**Async Testing:**
```kotlin
// From LocalInterfaceTest.kt - CountDownLatch pattern
val receivedLatch = CountDownLatch(1)
var receivedData: ByteArray? = null

server!!.onPacketReceived = { data, _ ->
    receivedData = data
    receivedLatch.countDown()
}

// Wait for async operation
assertTrue(receivedLatch.await(2, TimeUnit.SECONDS))
assertNotNull(receivedData)
```

**Timeout Testing:**
```kotlin
@Test
@DisplayName("Envelope can pack and unpack messages")
@Timeout(5)  // Test must complete in 5 seconds
fun `envelope can pack and unpack messages`() {
    // ... test code ...
}
```

**Error Testing:**
```kotlin
// From ChannelIntegrationTest.kt - implicit error handling
try {
    val envelope = Envelope(outlet, raw = raw)
    // ... process envelope ...
} catch (e: ChannelException) {
    println("[Channel] Error receiving message: ${e.message}")
}
// Test verifies exception is caught and handled gracefully
```

**Equality Testing:**
```kotlin
// From InteropTestBase.kt
protected fun assertBytesEqual(expected: ByteArray, actual: ByteArray, message: String = "") {
    if (!expected.contentEquals(actual)) {
        val diff = buildDiffMessage(expected, actual, message)
        throw AssertionError(diff)
    }
}

// Usage in tests
assertBytesEqual(
    pythonResult.getBytes("hash"),
    kotlinHash,
    "SHA-256 for ${data.size} bytes"
)
```

## Test Lifecycle Hooks

**Setup/Teardown:**
- `@BeforeEach` / `@AfterEach` - run before/after each test
- `@BeforeAll` / `@AfterAll` - run once per test class (with `@TestInstance(Lifecycle.PER_CLASS)`)
- `@TempDir` - JUnit 5 creates temporary directories for file tests

**Example from LinkIntegrationTest.kt:**
```kotlin
@BeforeEach
fun setup() {
    Transport.stop()
}

@AfterEach
fun teardown() {
    if (::client.isInitialized) {
        try { client.detach() } catch (_: Exception) {}
    }
    if (::server.isInitialized) {
        try { server.detach() } catch (_: Exception) {}
    }
    Transport.stop()
}
```

## Python Interop Testing

**Bridge Setup:**
```kotlin
// From InteropTestBase.kt
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class InteropTestBase {

    protected lateinit var bridge: PythonBridge

    @BeforeAll
    fun setupBridge() {
        val rnsPath = System.getenv("PYTHON_RNS_PATH")
            ?: findReticulumPath()
            ?: throw IllegalStateException(
                "Cannot find Reticulum. Set PYTHON_RNS_PATH environment variable."
            )
        bridge = PythonBridge.start(rnsPath)
    }

    @AfterAll
    fun teardownBridge() {
        if (::bridge.isInitialized) {
            bridge.close()
        }
    }
}
```

**Environment:**
- `PYTHON_RNS_PATH`: Required for interop tests, path to Python Reticulum repository
- Set in `rns-test/build.gradle.kts`: tries multiple search locations
- Tests with `@EnabledIfEnvironmentVariable(named = "PYTHON_SERVER_RUNNING", matches = "true")` only run if server is active

## Gradle Configuration

**Test Task Config:**
```kotlin
// From rns-test/build.gradle.kts
tasks.test {
    val rnsPath = System.getenv("PYTHON_RNS_PATH")
        ?: listOf(
            File(System.getProperty("user.home"), "repos/Reticulum"),
            File("${rootProject.projectDir}/../../../Reticulum")
        ).find { it.exists() && File(it, "RNS").exists() }?.absolutePath
        ?: "${rootProject.projectDir}/../../../Reticulum"

    environment("PYTHON_RNS_PATH", rnsPath)
    useJUnitPlatform()
}
```

---

*Testing analysis: 2026-01-23*
