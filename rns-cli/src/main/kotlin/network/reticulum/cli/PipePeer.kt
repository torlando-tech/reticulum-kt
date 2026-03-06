package network.reticulum.cli

import kotlinx.serialization.json.*
import network.reticulum.Reticulum
import network.reticulum.common.DestinationDirection
import network.reticulum.common.DestinationType
import network.reticulum.common.InterfaceMode
import network.reticulum.common.toHexString
import network.reticulum.destination.Destination
import network.reticulum.identity.Identity
import network.reticulum.interfaces.local.LocalServerInterface
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
/** Resolve an fd number to a filesystem path, portable across Linux and macOS. */
private fun fdPath(fd: Int): String = when {
    System.getProperty("os.name").startsWith("Mac", ignoreCase = true) -> "/dev/fd/$fd"
    else -> "/proc/self/fd/$fd"
}

fun main() {
    val action = System.getenv("PIPE_PEER_ACTION") ?: "announce"
    val appName = System.getenv("PIPE_PEER_APP_NAME") ?: "pipetest"
    val aspects = (System.getenv("PIPE_PEER_ASPECTS") ?: "routing").split(",").toTypedArray()
    val enableTransport = System.getenv("PIPE_PEER_TRANSPORT")?.lowercase() == "true"
    val modeStr = System.getenv("PIPE_PEER_MODE") ?: "full"
    val sharedPort = System.getenv("PIPE_PEER_SHARED_PORT")?.toIntOrNull() ?: 0

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

        // Create interfaces: shared instance server or pipe-based
        if (sharedPort > 0) {
            // Shared instance server mode: start LocalServerInterface on TCP port
            val server = LocalServerInterface(name = "SharedInstance", tcpPort = sharedPort)
            Transport.registerInterface(server.toRef())
            server.start()
        } else {
            // Pipe interface mode
            val numIfaces = System.getenv("PIPE_PEER_NUM_IFACES")?.toIntOrNull() ?: 0
            if (numIfaces > 0) {
                // Multi-interface mode: create N interfaces from fd pairs
                for (i in 0 until numIfaces) {
                    val fdIn = System.getenv("PIPE_PEER_IFACE_${i}_FD_IN")?.toIntOrNull() ?: continue
                    val fdOut = System.getenv("PIPE_PEER_IFACE_${i}_FD_OUT")?.toIntOrNull() ?: continue
                    val input = FileInputStream(fdPath(fdIn))
                    val output = FileOutputStream(fdPath(fdOut))
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
            "self_link" -> {
                // Create a destination AND a link to it within the same process.
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
                        })
                    }
                    link.setPacketCallback { data, _ ->
                        emit(buildJsonObject {
                            put("type", "link_data")
                            put("link_id", link.linkId.toHexString())
                            put("data_hex", data.toHexString())
                            put("data_utf8", data.decodeToString())
                            put("side", "responder")
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

                // Give the announce time to propagate through the shared instance.
                // Transport.outbound() broadcasts the LINKREQUEST on all interfaces
                // (no path_table entry needed); the shared instance routes it back.
                Thread.sleep(2000)

                // Create a link to our own destination.
                // The LINKREQUEST goes out via LocalClientInterface to the shared
                // instance, which forwards it back. Both endpoints are in this process.
                var selfLinkActive = false
                val selfLink = Link.create(
                    destination = destination,
                    establishedCallback = { link ->
                        selfLinkActive = true
                        emit(buildJsonObject {
                            put("type", "self_link_active")
                            put("link_id", link.linkId.toHexString())
                        })
                        // Set up data callback on the initiator link
                        link.setPacketCallback { data, _ ->
                            emit(buildJsonObject {
                                put("type", "self_link_data_received")
                                put("link_id", link.linkId.toHexString())
                                put("data_hex", data.toHexString())
                                put("data_utf8", data.decodeToString())
                                put("side", "initiator")
                            })
                        }
                        // Send test data on the link
                        val testData = "self-link-test-data".toByteArray()
                        link.send(testData)
                        emit(buildJsonObject {
                            put("type", "self_link_data_sent")
                            put("link_id", link.linkId.toHexString())
                            put("data_hex", testData.toHexString())
                        })
                    }
                )
                emit(buildJsonObject {
                    put("type", "self_link_initiated")
                    put("destination_hash", destination.hash.toHexString())
                    put("link_id", selfLink.linkId.toHexString())
                })

                // Wait for link to become active
                val linkDeadline = System.currentTimeMillis() + 20_000
                while (System.currentTimeMillis() < linkDeadline && !selfLinkActive) {
                    Thread.sleep(100)
                }

                if (!selfLinkActive) {
                    emit(buildJsonObject {
                        put("type", "error")
                        put("message", "Self-link did not become active, status=${selfLink.status}")
                    })
                }
                pathTableDumper()
            }
            "link_initiate" -> {
                // Wait for a destination to appear, then create a link to it.
                emit(buildJsonObject { put("type", "waiting_for_destination") })

                // Wait for a path to any destination
                var destHash: ByteArray? = null
                var destIdentity: Identity? = null
                val deadline = System.currentTimeMillis() + 20_000
                while (System.currentTimeMillis() < deadline) {
                    for ((key, _) in Transport.pathTable) {
                        val hash = key.bytes
                        val id = Identity.recall(hash)
                        if (id != null) {
                            destHash = hash
                            destIdentity = id
                            break
                        }
                    }
                    if (destIdentity != null) break
                    Thread.sleep(200)
                }

                if (destIdentity == null || destHash == null) {
                    emit(buildJsonObject {
                        put("type", "error")
                        put("message", "No destination found within timeout")
                    })
                    pathTableDumper()
                } else {
                    val destHashHex = destHash.toHexString()
                    emit(buildJsonObject {
                        put("type", "destination_found")
                        put("destination_hash", destHashHex)
                    })

                    val outDest = Destination.create(
                        identity = destIdentity,
                        direction = DestinationDirection.OUT,
                        type = DestinationType.SINGLE,
                        appName = appName,
                        aspects = aspects
                    )

                    var linkActive = false
                    val link = Link.create(
                        destination = outDest,
                        establishedCallback = { lnk ->
                            linkActive = true
                            emit(buildJsonObject {
                                put("type", "link_established")
                                put("link_id", lnk.linkId.toHexString())
                                put("destination_hash", destHashHex)
                            })
                            lnk.setPacketCallback { data, _ ->
                                emit(buildJsonObject {
                                    put("type", "link_data")
                                    put("link_id", lnk.linkId.toHexString())
                                    put("data_hex", data.toHexString())
                                    put("data_utf8", data.decodeToString())
                                })
                            }
                            lnk.setLinkClosedCallback { closedLink ->
                                emit(buildJsonObject {
                                    put("type", "link_closed")
                                    put("link_id", closedLink.linkId.toHexString())
                                })
                            }
                            // Send test data
                            val testData = "hello-from-initiator".toByteArray()
                            lnk.send(testData)
                            emit(buildJsonObject {
                                put("type", "link_sent")
                                put("link_id", lnk.linkId.toHexString())
                                put("data_hex", testData.toHexString())
                            })
                        }
                    )
                    emit(buildJsonObject {
                        put("type", "link_initiated")
                        put("destination_hash", destHashHex)
                        put("link_id", link.linkId.toHexString())
                    })

                    // Wait for link to become active
                    val linkDeadline = System.currentTimeMillis() + 20_000
                    while (System.currentTimeMillis() < linkDeadline && !linkActive) {
                        Thread.sleep(100)
                    }

                    if (!linkActive) {
                        emit(buildJsonObject {
                            put("type", "error")
                            put("message", "Link did not become active, status=${link.status}")
                        })
                    }
                    pathTableDumper()
                }
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
