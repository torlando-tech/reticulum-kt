package network.reticulum.cli

import kotlinx.serialization.json.*
import network.reticulum.Reticulum
import network.reticulum.common.DestinationDirection
import network.reticulum.common.DestinationType
import network.reticulum.common.InterfaceMode
import network.reticulum.common.toHexString
import network.reticulum.destination.Destination
import network.reticulum.identity.Identity
import network.reticulum.interfaces.pipe.PipeInterface
import network.reticulum.interfaces.toRef
import network.reticulum.link.Link
import network.reticulum.transport.AnnounceHandler
import network.reticulum.transport.Transport
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.Files

/**
 * Kotlin Reticulum Pipe Peer for conformance testing.
 *
 * Speaks the same protocol as pipe_peer.py:
 *   stdin/stdout: HDLC-framed RNS packets
 *   stderr: JSON control/status messages (one per line)
 *
 * Environment variables:
 *   PIPE_PEER_ACTION:    announce | listen | link_listen | link_serve | transport
 *   PIPE_PEER_APP_NAME:  app name for destination (default: pipetest)
 *   PIPE_PEER_ASPECTS:   comma-separated aspects (default: routing)
 *   PIPE_PEER_TRANSPORT: true | false (default: false)
 *   PIPE_PEER_MODE:      interface mode: full | ap | roaming | boundary | gateway | p2p
 *   PIPE_PEER_NUM_IFACES:      number of fd-pair interfaces (0 = use stdin/stdout)
 *   PIPE_PEER_IFACE_{n}_FD_IN:  read fd for interface n
 *   PIPE_PEER_IFACE_{n}_FD_OUT: write fd for interface n
 */
fun main() {
    val action = System.getenv("PIPE_PEER_ACTION") ?: "announce"
    val appName = System.getenv("PIPE_PEER_APP_NAME") ?: "pipetest"
    val aspects = (System.getenv("PIPE_PEER_ASPECTS") ?: "routing").split(",").toTypedArray()
    val enableTransport = System.getenv("PIPE_PEER_TRANSPORT")?.lowercase() == "true"
    val modeStr = System.getenv("PIPE_PEER_MODE") ?: "full"

    val mode = when (modeStr.lowercase()) {
        "ap", "access_point" -> InterfaceMode.ACCESS_POINT
        "roaming" -> InterfaceMode.ROAMING
        "boundary" -> InterfaceMode.BOUNDARY
        "gateway" -> InterfaceMode.GATEWAY
        "p2p", "point_to_point" -> InterfaceMode.POINT_TO_POINT
        else -> InterfaceMode.FULL
    }

    // Create temp config
    val configDir = Files.createTempDirectory("rns-kt-pipe-peer-").toFile()

    try {
        // Start Reticulum
        Reticulum.start(
            configDir = configDir.absolutePath,
            enableTransport = enableTransport
        )

        // Create PipeInterface(s)
        val numIfaces = System.getenv("PIPE_PEER_NUM_IFACES")?.toIntOrNull() ?: 0
        if (numIfaces > 0) {
            // Multi-interface mode: create N interfaces from fd pairs
            for (i in 0 until numIfaces) {
                val fdIn = System.getenv("PIPE_PEER_IFACE_${i}_FD_IN")?.toIntOrNull() ?: continue
                val fdOut = System.getenv("PIPE_PEER_IFACE_${i}_FD_OUT")?.toIntOrNull() ?: continue
                val input = FileInputStream("/proc/self/fd/$fdIn")
                val output = FileOutputStream("/proc/self/fd/$fdOut")
                val iface = PipeInterface(
                    name = "Pipe$i",
                    inputStream = input,
                    outputStream = output,
                    interfaceMode = mode
                )
                Transport.registerInterface(iface.toRef())
                iface.start()
            }
        } else {
            // Single interface mode: stdin/stdout
            val pipeInterface = PipeInterface(
                name = "StdioPipe",
                inputStream = System.`in`,
                outputStream = System.out,
                interfaceMode = mode
            )
            Transport.registerInterface(pipeInterface.toRef())
            pipeInterface.start()
        }

        // Register announce handler
        Transport.registerAnnounceHandler(object : AnnounceHandler {
            override fun handleAnnounce(
                destinationHash: ByteArray,
                announcedIdentity: Identity,
                appData: ByteArray?
            ): Boolean {
                val hops = if (Transport.hasPath(destinationHash)) Transport.hopsTo(destinationHash) else -1
                emit(buildJsonObject {
                    put("type", "announce_received")
                    put("destination_hash", destinationHash.toHexString())
                    put("identity_hash", announcedIdentity.hash.toHexString())
                    put("hops", hops)
                })
                return false // allow other handlers to process
            }
        })

        // Emit ready
        val identityHash = Transport.identity?.hash?.toHexString() ?: ""
        emit(buildJsonObject {
            put("type", "ready")
            put("identity_hash", identityHash)
        })

        when (action) {
            "announce" -> {
                val identity = Identity.create()
                val destination = Destination.create(
                    identity = identity,
                    direction = DestinationDirection.IN,
                    type = DestinationType.SINGLE,
                    appName = appName,
                    aspects = aspects
                )

                destination.announce()

                emit(buildJsonObject {
                    put("type", "announced")
                    put("destination_hash", destination.hash.toHexString())
                    put("identity_hash", identity.hash.toHexString())
                    put("identity_public_key", identity.getPublicKey().toHexString())
                })

                pathTableDumper()
            }
            "link_listen" -> {
                val identity = Identity.create()
                val destination = Destination.create(
                    identity = identity,
                    direction = DestinationDirection.IN,
                    type = DestinationType.SINGLE,
                    appName = appName,
                    aspects = aspects
                )
                destination.setLinkEstablishedCallback { linkAny ->
                    val link = linkAny as Link
                    emit(buildJsonObject {
                        put("type", "link_established")
                        put("link_id", link.linkId.toHexString())
                        put("destination_hash", destination.hash.toHexString())
                    })
                    link.setLinkClosedCallback { closedLink ->
                        emit(buildJsonObject {
                            put("type", "link_closed")
                            put("link_id", closedLink.linkId.toHexString())
                            put("destination_hash", destination.hash.toHexString())
                        })
                    }
                    link.setPacketCallback { data, _ ->
                        emit(buildJsonObject {
                            put("type", "link_data")
                            put("link_id", link.linkId.toHexString())
                            put("data_hex", data.toHexString())
                            put("data_utf8", data.decodeToString())
                        })
                    }
                }
                destination.announce()
                emit(buildJsonObject {
                    put("type", "announced")
                    put("destination_hash", destination.hash.toHexString())
                    put("identity_hash", identity.hash.toHexString())
                    put("identity_public_key", identity.getPublicKey().toHexString())
                })
                pathTableDumper()
            }
            "link_serve" -> {
                // Transport node with its own link-accepting destination.
                // Sends a welcome message on link establishment and echoes received data.
                val identity = Identity.create()
                val destination = Destination.create(
                    identity = identity,
                    direction = DestinationDirection.IN,
                    type = DestinationType.SINGLE,
                    appName = appName,
                    aspects = aspects
                )
                destination.setLinkEstablishedCallback { linkAny ->
                    val link = linkAny as Link
                    emit(buildJsonObject {
                        put("type", "link_established")
                        put("link_id", link.linkId.toHexString())
                        put("destination_hash", destination.hash.toHexString())
                    })
                    link.setLinkClosedCallback { closedLink ->
                        emit(buildJsonObject {
                            put("type", "link_closed")
                            put("link_id", closedLink.linkId.toHexString())
                            put("destination_hash", destination.hash.toHexString())
                        })
                    }
                    link.setPacketCallback { data, _ ->
                        emit(buildJsonObject {
                            put("type", "link_data")
                            put("link_id", link.linkId.toHexString())
                            put("data_hex", data.toHexString())
                            put("data_utf8", data.decodeToString())
                        })
                        // Echo data back over the link
                        try {
                            link.send(data)
                            emit(buildJsonObject {
                                put("type", "link_sent")
                                put("link_id", link.linkId.toHexString())
                                put("data_hex", data.toHexString())
                            })
                        } catch (e: Exception) {
                            emit(buildJsonObject {
                                put("type", "error")
                                put("message", "Echo send failed: ${e.message}")
                            })
                        }
                    }
                    // Send welcome message after a short delay
                    Thread {
                        Thread.sleep(500)
                        try {
                            val welcome = "welcome".toByteArray()
                            link.send(welcome)
                            emit(buildJsonObject {
                                put("type", "link_sent")
                                put("link_id", link.linkId.toHexString())
                                put("data_hex", welcome.toHexString())
                            })
                        } catch (e: Exception) {
                            emit(buildJsonObject {
                                put("type", "error")
                                put("message", "Welcome send failed: ${e.message}")
                            })
                        }
                    }.apply { isDaemon = true }.start()
                }
                destination.announce()
                emit(buildJsonObject {
                    put("type", "announced")
                    put("destination_hash", destination.hash.toHexString())
                    put("identity_hash", identity.hash.toHexString())
                    put("identity_public_key", identity.getPublicKey().toHexString())
                })
                pathTableDumper()
            }
            "listen" -> pathTableDumper()
            "transport" -> pathTableDumper()
        }
    } finally {
        Reticulum.stop()
        configDir.deleteRecursively()
    }
}

private fun emit(json: JsonObject) {
    System.err.println(json.toString())
    System.err.flush()
}

private fun pathTableDumper() {
    var lastDump = ""
    try {
        while (true) {
            Thread.sleep(1000)

            val entries = buildJsonArray {
                for ((key, entry) in Transport.pathTable) {
                    add(buildJsonObject {
                        put("destination_hash", key.toString())
                        put("hops", entry.hops)
                        put("next_hop", entry.nextHop.toHexString())
                        put("expired", entry.isExpired())
                    })
                }
            }

            val current = entries.toString()
            if (current != lastDump) {
                emit(buildJsonObject {
                    put("type", "path_table")
                    put("entries", entries)
                })
                lastDump = current
            }
        }
    } catch (_: InterruptedException) {
        // Shutdown
    }
}
