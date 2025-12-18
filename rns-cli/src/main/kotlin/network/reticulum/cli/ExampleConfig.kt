package network.reticulum.cli

/**
 * Example Reticulum configuration matching Python rnsd --exampleconfig output.
 */
val EXAMPLE_RNS_CONFIG = """
# This is an example Reticulum config file.
# You should probably edit it to include any additional,
# interfaces and settings you might need.

[reticulum]

# If you enable Transport, your system will route traffic
# for other peers, pass announces and serve path requests.
# This should be done for systems that are suited to act
# as transport nodes, ie. if they are stationary and
# always-on. This directive is optional and can be removed
# for brevity.

enable_transport = No


# By default, the first program to launch the Reticulum
# Network Stack will create a shared instance, that other
# programs can communicate with. Only the shared instance
# opens all the configured interfaces directly, and other
# local programs communicate with the shared instance over
# a local socket. This is completely transparent to the
# user, and should generally be turned on. This directive
# is optional and can be removed for brevity.

share_instance = Yes


# If you want to run multiple *different* shared instances
# on the same system, you will need to specify different
# instance names for each. On platforms supporting domain
# sockets, this can be done with the instance_name option:

instance_name = default

# Some platforms don't support domain sockets, and if that
# is the case, you can isolate different instances by
# specifying a unique set of ports for each:

# shared_instance_port = 37428
# instance_control_port = 37429


# If you want to explicitly use TCP for shared instance
# communication, instead of domain sockets, this is also
# possible, by using the following option:

# shared_instance_type = tcp


# On systems where running instances may not have access
# to the same shared Reticulum configuration directory,
# it is still possible to allow full interactivity for
# running instances, by manually specifying a shared RPC
# key. In almost all cases, this option is not needed, but
# it can be useful on operating systems such as Android.
# The key must be specified as bytes in hexadecimal.

# rpc_key = e5c032d3ec4e64a6aca9927ba8ab73336780f6d71790


# It is possible to allow remote management of Reticulum
# systems using the various built-in utilities, such as
# rnstatus and rnpath. You will need to specify one or
# more Reticulum Identity hashes for authenticating the
# queries from client programs. For this purpose, you can
# use existing identity files, or generate new ones with
# the rnid utility.

# enable_remote_management = yes
# remote_management_allowed = 9fb6d773498fb3feda407ed8ef2c3229, 2d882c5586e548d79b5af27bca1776dc


# You can configure Reticulum to panic and forcibly close
# if an unrecoverable interface error occurs, such as the
# hardware device for an interface disappearing. This is
# an optional directive, and can be left out for brevity.
# This behaviour is disabled by default.

# panic_on_interface_error = No


# When Transport is enabled, it is possible to allow the
# Transport Instance to respond to probe requests from
# the rnprobe utility. This can be a useful tool to test
# connectivity. When this option is enabled, the probe
# destination will be generated from the Identity of the
# Transport Instance, and printed to the log at startup.
# Optional, and disabled by default.

# respond_to_probes = No


[logging]
# Valid log levels are 0 through 7:
#   0: Log only critical information
#   1: Log errors and lower log levels
#   2: Log warnings and lower log levels
#   3: Log notices and lower log levels
#   4: Log info and lower (this is the default)
#   5: Verbose logging
#   6: Debug logging
#   7: Extreme logging

loglevel = 4


# The interfaces section defines the physical and virtual
# interfaces Reticulum will use to communicate on. This
# section will contain examples for a variety of interface
# types. You can modify these or use them as a basis for
# your own config, or simply remove the unused ones.

[interfaces]

  # This interface enables communication with other
  # link-local Reticulum nodes over UDP. It does not
  # need any functional IP infrastructure like routers
  # or DHCP servers, but will require that at least link-
  # local IPv6 is enabled in your operating system, which
  # should be enabled by default in almost any OS. See
  # the Reticulum Manual for more configuration options.

  [[Default Interface]]
    type = AutoInterface
    enabled = yes


  # The following example enables communication with other
  # local Reticulum peers using UDP broadcasts.

  [[UDP Interface]]
    type = UDPInterface
    enabled = no
    listen_ip = 0.0.0.0
    listen_port = 4242
    forward_ip = 255.255.255.255
    forward_port = 4242


  # This example demonstrates a TCP server interface.
  # It will listen for incoming connections on the
  # specified IP address and port number.

  [[TCP Server Interface]]
    type = TCPServerInterface
    enabled = no
    listen_ip = 0.0.0.0
    listen_port = 4242


  # To connect to a TCP server interface, you would
  # naturally use the TCP client interface. Here's
  # an example. The target_host can either be an IP
  # address or a hostname

  [[TCP Client Interface]]
    type = TCPClientInterface
    enabled = no
    target_host = 127.0.0.1
    target_port = 4242


  # This example shows how to make your Reticulum
  # instance available over I2P, and connect to
  # another I2P peer.

  [[I2P]]
    type = I2PInterface
    enabled = no
    connectable = yes
    peers = ykzlw5ujbaqc2xkec4cpvgyxj257wcrmmgkuxqmqcur7cq3w3lha.b32.i2p


  # Here's an example of how to add a LoRa interface
  # using the RNode LoRa transceiver.

  [[RNode LoRa Interface]]
    type = RNodeInterface
    enabled = no
    port = /dev/ttyUSB0
    frequency = 867200000
    bandwidth = 125000
    txpower = 7
    spreadingfactor = 8
    codingrate = 5
    flow_control = False


  # An example KISS modem interface. Useful for running
  # Reticulum over packet radio hardware.

  [[Packet Radio KISS Interface]]
    type = KISSInterface
    enabled = no
    port = /dev/ttyUSB1
    speed = 115200
    databits = 8
    parity = none
    stopbits = 1
    preamble = 150
    txtail = 10
    persistence = 200
    slottime = 20
    flow_control = false

""".trimIndent()
