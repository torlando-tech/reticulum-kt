/**
 * LXMF-layer conformance commands for reticulum-kt.
 *
 * Mirrors reference/lxmf_bridge.py. Layered on top of WireTcp.kt: an
 * lxmf_start call binds an LXMRouter to an already-running wire RNS
 * instance (identified by the wire handle from wire_start_tcp_*). The
 * LXMF router does NOT spin up its own Reticulum — it uses the singleton
 * WireTcp already started.
 *
 * MVP scope: the Kotlin side implements the SENDER role only. The
 * propagation-node role is intentionally absent — in production,
 * propagation nodes are the Python `lxmd` daemon (see
 * fleet/yggdrasil/reticulum-node.yaml); the Kotlin bridge has no
 * equivalent, and the conformance fixture spawns lxmd via the Python
 * reference bridge regardless of sender impl.
 *
 * The receiver-side pull API (sync_inbound / poll_inbox) drives
 * LXMF-kt's requestMessagesFromPropagationNode + delivery callback
 * inbox. This was added in the follow-up widening the conformance
 * parametrization to include kotlin-in-receiver slots.
 *
 * Commands handled:
 *   lxmf_start
 *   lxmf_set_outbound_propagation_node
 *   lxmf_send_propagated
 *   lxmf_sync_inbound
 *   lxmf_poll_inbox
 *   lxmf_stop
 *
 * Intentionally NOT handled (no Kotlin equivalent):
 *   lxmf_spawn_daemon_propagation_node  — Python-only; lxmd is Python.
 *   lxmf_stop_daemon_propagation_node   — paired with the above.
 */

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import kotlinx.coroutines.runBlocking
import network.reticulum.common.DestinationDirection
import network.reticulum.common.DestinationType
import network.reticulum.destination.Destination
import network.reticulum.identity.Identity
import network.reticulum.lxmf.DeliveryMethod
import network.reticulum.lxmf.LXMRouter
import network.reticulum.lxmf.LXMessage
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * Handle-indexed LXMF router + its inbox buffer.
 *
 * One router per handle; one handle per lxmf_start call. The wireHandle
 * that seeded this router is kept around for error reporting only — the
 * router holds its own strong reference to the underlying Reticulum/
 * Transport through the RNS singleton the wire layer owns.
 */
private class LxmfInstance(
    val wireHandle: String,
    val router: LXMRouter,
    val identity: Identity,
    val deliveryDestination: Destination,
    val storageDir: File,
    // Delivered-message inbox. Drained by lxmf_poll_inbox (MVP: Python
    // only). Keeping the type and structure in place on the Kotlin side
    // so the receiver-side follow-up PR can just wire it up.
    val inbox: ConcurrentLinkedDeque<JsonObject> = ConcurrentLinkedDeque(),
)

private val lxmfInstances = ConcurrentHashMap<String, LxmfInstance>()

fun handleLxmfCommand(command: String, p: JsonObject): JsonObject = when (command) {
    "lxmf_start" -> {
        val wireHandle = p.str("wire_handle")
        val displayName = p.strOpt("display_name")

        // Validate the wire handle points at a started instance so we
        // surface a clean "unknown handle" instead of an NPE in the
        // LXMRouter constructor when Transport hasn't been initialized.
        if (!wireHandleExists(wireHandle)) {
            throw IllegalArgumentException("Unknown wire_handle: $wireHandle")
        }

        val storageDir = java.nio.file.Files.createTempDirectory("lxmf_conf_").toFile()
        val identity = Identity.create()
        // Tracks the running router so catch{} can stop it. Only
        // non-null once router.start() has returned; stays null on
        // failures before start() so stop() is never called on a
        // half-constructed router.
        var startedRouter: LXMRouter? = null
        // Tracks the map key so catch{} can undo the insertion if the
        // post-start setup (announce) throws. Null before insertion.
        var registeredHandle: String? = null
        val handle: String
        try {
            val router = LXMRouter(
                identity = identity,
                storagePath = storageDir.absolutePath,
            )
            val deliveryDestination = router.registerDeliveryIdentity(
                identity = identity,
                displayName = displayName,
            )

            val inst = LxmfInstance(
                wireHandle = wireHandle,
                router = router,
                identity = identity,
                deliveryDestination = deliveryDestination,
                storageDir = storageDir,
            )

            // Wire the delivery callback BEFORE router.start() so a fast
            // propagation node pushing a stored message during startup
            // doesn't land on a null/no-op handler and get silently
            // dropped. The receiver-side follow-up (sync_inbound /
            // poll_inbox) relies on this being in place before the
            // router can begin delivering.
            router.registerDeliveryCallback { message ->
                val entry = JsonObject()
                entry.addProperty("hash", message.hash?.toHex() ?: "")
                entry.addProperty("source", message.sourceHash.toHex())
                entry.addProperty("destination", message.destinationHash.toHex())
                entry.addProperty("title", message.title)
                entry.addProperty("content", message.content)
                val fields = JsonObject()
                message.fields.forEach { (k, v) ->
                    val key = k.toString()
                    when (v) {
                        is ByteArray -> fields.addProperty(key, v.toHex())
                        is String -> fields.addProperty(key, v)
                        is Number -> fields.addProperty(key, v)
                        is Boolean -> fields.addProperty(key, v)
                        else -> fields.addProperty(key, v.toString())
                    }
                }
                entry.add("fields", fields)
                inst.inbox.add(entry)
            }

            router.start()
            startedRouter = router

            // Register in the map BEFORE announce() so that even if
            // announce() (or any later step) throws, the catch block
            // can still reach the started router via the map and stop
            // it. Without this, a throw between start() and the map
            // insertion orphans the router: startedRouter handles the
            // stop() half, but if UUID.randomUUID() or the map
            // insertion itself throws after this block, the router
            // would otherwise be untrackable — matching the
            // wire_start_tcp_server defensive pattern.
            handle = UUID.randomUUID().toString().replace("-", "").substring(0, 16)
            lxmfInstances[handle] = inst
            registeredHandle = handle

            // Announce the delivery destination so the propagation node
            // and other peers can route to it / recall its identity for
            // encryption. Caller (the test / fixture) must give
            // announces time to propagate before sending — same as the
            // wire layer.
            deliveryDestination.announce()
        } catch (t: Throwable) {
            // Partial setup — roll back the router (if start() ran, so
            // its background coroutines get stopped), the map entry
            // (if insertion happened), and the temp storage dir so we
            // don't leak any of these for the remainder of the bridge
            // process's lifetime. Matches the wire_start_tcp_server
            // pattern (WireTcp.kt) which also catches Throwable so JVM
            // Errors still trigger cleanup.
            registeredHandle?.let { lxmfInstances.remove(it) }
            startedRouter?.let { runCatching { it.stop() } }
            runCatching { storageDir.deleteRecursively() }
            throw t
        }

        val inst = lxmfInstances.getValue(handle)
        result(
            "handle" to JsonPrimitive(handle),
            "delivery_dest_hash" to hexVal(inst.deliveryDestination.hash),
            "identity_hash" to hexVal(identity.hash),
        )
    }

    "lxmf_set_outbound_propagation_node" -> {
        val handle = p.str("handle")
        val pnHash = p.hex("propagation_node_dest_hash")
        val pnHashHex = pnHash.toHex()

        val inst = lxmfInstances[handle]
            ?: throw IllegalArgumentException("Unknown handle: $handle")

        // LXMF-kt's setActivePropagationNode looks up the identity via
        // Identity.recall under the hood; the propagation announce from
        // the node must have arrived via Transport for this to succeed
        // on the "real" path. If it hasn't, the call still saves the
        // hash and the next send will request a path — same semantics
        // as the Python side.
        val ok = inst.router.setActivePropagationNode(pnHashHex)
        result("success" to boolVal(ok))
    }

    "lxmf_send_propagated" -> {
        val handle = p.str("handle")
        val recipientHash = p.hex("recipient_delivery_dest_hash")
        val content = p.str("content")
        val title = p.get("title")?.asString ?: ""

        val inst = lxmfInstances[handle]
            ?: throw IllegalArgumentException("Unknown handle: $handle")

        // Recall the recipient's identity so we can build an OUT
        // Destination for it. Matches the Python bridge's precondition:
        // the recipient must have announced its delivery destination,
        // and that announce must have been observed on this peer's RNS.
        val recipientIdentity = Identity.recall(recipientHash)
            ?: throw IllegalStateException(
                "No identity known for recipient ${recipientHash.toHex()}. " +
                    "Ensure the recipient announced its delivery destination " +
                    "before calling lxmf_send_propagated.",
            )

        val recipientDestination = Destination.create(
            identity = recipientIdentity,
            direction = DestinationDirection.OUT,
            type = DestinationType.SINGLE,
            appName = "lxmf",
            "delivery",
        )

        val message = LXMessage.create(
            destination = recipientDestination,
            source = inst.deliveryDestination,
            content = content,
            title = title,
            desiredMethod = DeliveryMethod.PROPAGATED,
        )

        // Submit to the router. handleOutbound is suspend — runBlocking
        // here because the bridge protocol is synchronous request/
        // response. LXMF-kt's delivery loop runs on its own coroutine
        // scope so blocking the bridge dispatcher only waits for the
        // queue handoff, not the actual transfer.
        runBlocking { inst.router.handleOutbound(message) }

        // message.hash is populated by LXMF-kt during packing inside
        // handleOutbound, so in the sender-only conformance path it is
        // expected to be non-null here and the test harness asserts it
        // is truthy. We still fall back to "" for parity with the
        // Python reference bridge (reference/lxmf_bridge.py returns
        // `message.hash.hex() if message.hash else ""`). An empty
        // string therefore indicates "packing did not produce a hash"
        // rather than "hash will arrive later" — callers MUST NOT use
        // this value for delivery tracking.
        result(
            "message_hash" to JsonPrimitive(message.hash?.toHex() ?: ""),
        )
    }

    "lxmf_sync_inbound" -> {
        // Drive LXMF-kt's two-phase pull: it opens a link to the active
        // propagation node, lists stored messages, then downloads them.
        // Delivered messages arrive via the router's delivery callback
        // (wired in lxmf_start), which enqueues onto inst.inbox.
        val handle = p.str("handle")
        val timeoutMs = p.intOpt("timeout_ms") ?: 30000

        val inst = lxmfInstances[handle]
            ?: throw IllegalArgumentException("Unknown handle: $handle")

        inst.router.requestMessagesFromPropagationNode()

        // Poll until the router's state machine reaches a terminal
        // state (COMPLETE, FAILED, NO_PATH, NO_LINK). Matches the
        // Python reference: terminal_states = {PR_COMPLETE, PR_FAILED,
        // PR_NO_IDENTITY_RCVD, PR_NO_ACCESS}.
        val terminalStates = setOf(
            LXMRouter.PropagationTransferState.COMPLETE,
            LXMRouter.PropagationTransferState.FAILED,
            LXMRouter.PropagationTransferState.NO_PATH,
            LXMRouter.PropagationTransferState.NO_LINK,
        )
        // If a prior lxmf_sync_inbound left the state machine in a
        // terminal state (COMPLETE is the common case), the new
        // requestMessagesFromPropagationNode() call above may not have
        // synchronously reset it yet. Wait briefly for the state to
        // leave the previous terminal before entering the completion-
        // wait loop — otherwise the loop would observe the stale
        // terminal and return immediately without actually waiting for
        // the new transfer.
        val transitionDeadline = System.currentTimeMillis() + 2000
        while (System.currentTimeMillis() < transitionDeadline &&
            inst.router.propagationTransferState in terminalStates
        ) {
            Thread.sleep(50)
        }

        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (inst.router.propagationTransferState in terminalStates) {
                break
            }
            Thread.sleep(100)
        }

        // Let any freshly-decrypted messages hit the delivery callback
        // before we return — callbacks run on the router's own threads
        // and the caller is about to poll the inbox. Matches the 0.3s
        // settle window in reference/lxmf_bridge.py.
        Thread.sleep(300)

        // Snapshot the state AFTER the settle sleep so `state` and
        // `timed_out` are always consistent with each other: if the
        // router transitions to a terminal state during the settle
        // window, we want to report that terminal state and
        // timed_out=false, not timed_out=true with state=COMPLETE.
        // The test harness relies on these two fields agreeing to
        // distinguish a hung state machine from a late completion.
        val finalState = inst.router.propagationTransferState
        val timedOut = finalState !in terminalStates

        result(
            "messages_received" to JsonPrimitive(inst.router.propagationTransferLastResult),
            "state" to JsonPrimitive(finalState.name),
            "timed_out" to boolVal(timedOut),
        )
    }

    "lxmf_poll_inbox" -> {
        val handle = p.str("handle")
        val inst = lxmfInstances[handle]
            ?: throw IllegalArgumentException("Unknown handle: $handle")

        // Best-effort drain — the delivery callback enqueues onto
        // inst.inbox from the router's own threads, so a late delivery
        // arriving between two pollFirst() calls WILL land in this
        // drain. Tests that assert exact counts must call
        // lxmf_sync_inbound first (its 300 ms settle window lets the
        // router finish dispatching pending callbacks before we
        // return) to avoid racy counts here.
        val drained = JsonArray()
        while (true) {
            val entry = inst.inbox.pollFirst() ?: break
            drained.add(entry)
        }
        result("messages" to drained)
    }

    "lxmf_stop" -> {
        val handle = p.str("handle")
        val inst = lxmfInstances.remove(handle)
        if (inst != null) {
            runCatching { inst.router.stop() }
            runCatching { inst.storageDir.deleteRecursively() }
            result("stopped" to boolVal(true))
        } else {
            result("stopped" to boolVal(false))
        }
    }

    else -> throw IllegalArgumentException("Unknown lxmf command: $command")
}
