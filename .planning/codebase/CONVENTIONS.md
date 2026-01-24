# Coding Conventions

**Analysis Date:** 2026-01-23

## Naming Patterns

**Files:**
- PascalCase for all classes and top-level declarations: `Channel.kt`, `Identity.kt`, `Link.kt`
- kebab-case for extension function files when multiple: `byte-utils.kt` style not used; all grouped into single files
- Constants files use singular noun: `Constants.kt`, `Exceptions.kt`, `Enums.kt`

**Functions:**
- camelCase for all function names: `send()`, `receive()`, `isReadyToSend()`, `registerMessageType()`
- Boolean checks start with `is`, `has`, `can`: `isInitialized`, `hasPrivateKey`, `canReceive`, `canSend`
- Getter properties use simple names: `hash`, `hexHash`, `mdu`, `window`

**Variables:**
- camelCase for all variables: `nextSequence`, `txRing`, `rxRing`, `windowMax`, `messageHandlers`
- Concurrent/atomic types use clear names: `AtomicInteger`, `ConcurrentHashMap`, `CopyOnWriteArrayList`
- Private fields prefixed with underscore rarely; instead use private modifier: `private val messageHandlers`
- Constants in companion objects use UPPER_SNAKE_CASE: `WINDOW`, `WINDOW_MAX`, `SEQ_MAX`, `FAST_RATE_THRESHOLD`

**Types:**
- PascalCase for all custom types: `Channel`, `MessageBase`, `Envelope`, `ChannelOutlet`
- Exception classes end with `Exception`: `ChannelException`, `CryptoException`, `IdentityException`
- Type aliases use camelCase: `MessageCallback = (MessageBase) -> Boolean`

## Code Style

**Formatting:**
- Kotlin official code style enabled: `kotlin.code.style=official` in `gradle.properties`
- JVM target: Java 21 for non-Android modules, Java 17 for Android modules
- JSR305 strict mode: `-Xjsr305=strict` enabled for better nullability checks
- 4-space indentation (standard Kotlin)

**Linting:**
- No explicit linter configured (ktlint or detekt not found)
- Code quality enforced through review and test coverage
- Type safety via Kotlin compiler in strict mode

## Import Organization

**Order:**
1. Standard library imports (`java.*`, `kotlin.*`)
2. Third-party library imports (`org.jetbrains.*`, `org.msgpack.*`, etc.)
3. Internal project imports (`network.reticulum.*`)

**Path Aliases:**
- No path aliases used; fully qualified imports from `network.reticulum.*` package structure
- Package structure mirrors functionality: `network.reticulum.channel`, `network.reticulum.crypto`, `network.reticulum.destination`

**File Suppression:**
- Suppress used sparingly: `@file:Suppress("NOTHING_TO_INLINE")` in `ByteUtils.kt` for performance-critical extension functions

## Error Handling

**Patterns:**
- Sealed exception hierarchy with base class `RnsException`: see `common/Exceptions.kt`
- Specific exception types for domains: `ChannelException`, `CryptoException`, `PacketException`, `LinkException`, `InterfaceException`, `TransportException`, `DestinationException`, `IdentityException`, `ConfigException`
- Exceptions carry context-specific messages and optional cause
- Try-catch in critical sections logs errors to stdout before proceeding
- Callback error handling: catch and log in place, continue processing other callbacks

**Example from `Channel.kt`:**
```kotlin
try {
    val envelope = Envelope(outlet, raw = raw)
    // ... processing
} catch (e: ChannelException) {
    println("[Channel] Error receiving message: ${e.message}")
} catch (e: Exception) {
    println("[Channel] Unexpected error receiving message: ${e.message}")
}
```

## Logging

**Framework:** `println()` and `System.out` for console output (not using slf4j currently despite imports)

**Patterns:**
- Error logs prefixed with domain: `[Channel]`, `[Destination]`, `[Link]`
- Informational logs for state changes: "Saving X known destinations to storage"
- Debug logs include context: `"Using cached announce data for path response with tag ${tag.toHexString()}"`
- Async operations log start and completion with timing

**Example from `Identity.kt`:**
```kotlin
println("Saving ${knownDestinations.size} known destinations to storage...")
// ... operation ...
println("Saved known destinations to storage in $timeStr")
```

## Comments

**When to Comment:**
- KDoc comments on all public classes, methods, and properties
- Inline comments for non-obvious algorithm logic (especially cryptographic or timing-sensitive code)
- Comments explaining "why" not "what" (what is obvious from code, why requires explanation)
- References to Python implementation when porting: "This must match Python implementation exactly"

**JSDoc/TSDoc/KDoc:**
- Full KDoc for public APIs with descriptions, parameters, return values, throws
- Usage examples in KDoc for complex types like `Channel`, `Link`, `Destination`
- KDoc lists key assumptions and preconditions

**Example from `Channel.kt`:**
```kotlin
/**
 * Send a message over the channel.
 *
 * @param message The message to send
 * @return The envelope tracking this message
 * @throws ChannelException if channel is not ready or message is too big
 */
fun send(message: MessageBase): Envelope { ... }
```

## Function Design

**Size:** Functions are cohesive and sized appropriately; largest core functions around 200-300 lines when implementing complex algorithms (e.g., Transport.kt 4000 lines total with multiple methods)

**Parameters:**
- Use data classes for multiple related parameters: `IdentityData` holds timestamp, packetHash, publicKey, appData
- Named parameters preferred for clarity, especially in builder-style APIs
- Inline lambdas for callbacks: `(Link) -> Unit`, `(MessageBase) -> Boolean`

**Return Values:**
- Return early to reduce nesting: guard clauses at function start
- Nullable returns used judiciously: `Identity.hash: ByteArray` is non-null, but internal methods return `ByteArray?`
- Functions return single data points (Envelope, Link) or Unit for side effects

**Example from `Link.kt`:**
```kotlin
fun send(data: ByteArray): ByteArray? {
    if (!isEstablished) return null
    // ... implementation
    return packet.raw
}
```

## Module Design

**Exports:**
- Each module exports a cohesive API; internal implementation uses `private` extensively
- Companion objects hold static/factory methods: `Link.create()`, `Identity.create()`, `Destination.create()`
- Public properties expose read-only data; mutations through methods

**Barrel Files:**
- Not used; instead import specific classes directly
- Internal utilities grouped in single utility files: `ByteUtils.kt` contains all byte extension functions

**Access Modifiers:**
- `private` for implementation details
- `internal` for cross-module implementation sharing (rare)
- Public only for stable APIs
- Data classes use default visibility (public)

## Concurrency

**Thread Safety:**
- `ConcurrentHashMap` for shared state: `messageFactories`, `knownDestinations`
- `CopyOnWriteArrayList` for callback lists: `messageHandlers`
- `AtomicInteger`, `AtomicLong`, `AtomicReference` for atomic counters
- `ReentrantLock` with `lock.withLock {}` for critical sections
- `@Volatile` for simple boolean flags: `isShutdown`, `window`, `windowMax`

**Example from `Channel.kt`:**
```kotlin
private val messageHandlers = CopyOnWriteArrayList<MessageCallback>()
private val nextSequence = AtomicInteger(0)
private val lock = ReentrantLock()

fun addMessageHandler(callback: MessageCallback) {
    messageHandlers.add(callback)
}
```

## Resource Management

**Lifecycle:**
- `AutoCloseable` interface for resources that need cleanup: `Channel : AutoCloseable`
- `close()` method calls `shutdown()` to clean up callbacks and state
- Detach pattern for network interfaces: `interface.detach()`
- Try-with-resources or explicit close in tests

**Example:**
```kotlin
channel.use {
    // Use channel
    // Automatically calls close()
}
```

## Constants

**Location:**
- Domain-specific constants in `Constants.kt` files: `ChannelConstants`, `LinkConstants`, `ResourceConstants`
- Global constants in `common/Constants.kt` as `RnsConstants` object
- Wire protocol constants must match Python implementation exactly

**Naming:**
- UPPER_SNAKE_CASE for all constants
- Grouped logically with related constants
- Comments explaining wire protocol meaning: `const val MTU = 500 // Maximum Transmission Unit`

---

*Convention analysis: 2026-01-23*
