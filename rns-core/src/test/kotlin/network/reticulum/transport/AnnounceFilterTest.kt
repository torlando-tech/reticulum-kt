package network.reticulum.transport

import io.kotest.matchers.shouldBe
import network.reticulum.common.InterfaceMode
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("AnnounceFilter")
class AnnounceFilterTest {

    @Nested
    @DisplayName("shouldForward")
    inner class ShouldForward {

        // --- ACCESS_POINT outgoing: always blocks ---

        @Test
        fun `AP blocks even for local destinations`() {
            AnnounceFilter.shouldForward(
                outgoingMode = InterfaceMode.ACCESS_POINT,
                isLocalDestination = true,
                sourceMode = InterfaceMode.FULL
            ) shouldBe false
        }

        @Test
        fun `AP blocks for remote destinations`() {
            AnnounceFilter.shouldForward(
                outgoingMode = InterfaceMode.ACCESS_POINT,
                isLocalDestination = false,
                sourceMode = InterfaceMode.FULL
            ) shouldBe false
        }

        @Test
        fun `AP blocks with null source mode`() {
            AnnounceFilter.shouldForward(
                outgoingMode = InterfaceMode.ACCESS_POINT,
                isLocalDestination = false,
                sourceMode = null
            ) shouldBe false
        }

        // --- ROAMING outgoing ---

        @Test
        fun `ROAMING allows local destinations`() {
            AnnounceFilter.shouldForward(
                outgoingMode = InterfaceMode.ROAMING,
                isLocalDestination = true,
                sourceMode = InterfaceMode.ROAMING
            ) shouldBe true
        }

        @Test
        fun `ROAMING blocks when source is ROAMING`() {
            AnnounceFilter.shouldForward(
                outgoingMode = InterfaceMode.ROAMING,
                isLocalDestination = false,
                sourceMode = InterfaceMode.ROAMING
            ) shouldBe false
        }

        @Test
        fun `ROAMING blocks when source is BOUNDARY`() {
            AnnounceFilter.shouldForward(
                outgoingMode = InterfaceMode.ROAMING,
                isLocalDestination = false,
                sourceMode = InterfaceMode.BOUNDARY
            ) shouldBe false
        }

        @Test
        fun `ROAMING allows when source is FULL`() {
            AnnounceFilter.shouldForward(
                outgoingMode = InterfaceMode.ROAMING,
                isLocalDestination = false,
                sourceMode = InterfaceMode.FULL
            ) shouldBe true
        }

        @Test
        fun `ROAMING allows when source is POINT_TO_POINT`() {
            AnnounceFilter.shouldForward(
                outgoingMode = InterfaceMode.ROAMING,
                isLocalDestination = false,
                sourceMode = InterfaceMode.POINT_TO_POINT
            ) shouldBe true
        }

        @Test
        fun `ROAMING allows when source is ACCESS_POINT`() {
            AnnounceFilter.shouldForward(
                outgoingMode = InterfaceMode.ROAMING,
                isLocalDestination = false,
                sourceMode = InterfaceMode.ACCESS_POINT
            ) shouldBe true
        }

        @Test
        fun `ROAMING allows when source is GATEWAY`() {
            AnnounceFilter.shouldForward(
                outgoingMode = InterfaceMode.ROAMING,
                isLocalDestination = false,
                sourceMode = InterfaceMode.GATEWAY
            ) shouldBe true
        }

        @Test
        fun `ROAMING blocks with null source mode`() {
            AnnounceFilter.shouldForward(
                outgoingMode = InterfaceMode.ROAMING,
                isLocalDestination = false,
                sourceMode = null
            ) shouldBe false
        }

        // --- BOUNDARY outgoing ---

        @Test
        fun `BOUNDARY allows local destinations`() {
            AnnounceFilter.shouldForward(
                outgoingMode = InterfaceMode.BOUNDARY,
                isLocalDestination = true,
                sourceMode = InterfaceMode.ROAMING
            ) shouldBe true
        }

        @Test
        fun `BOUNDARY blocks when source is ROAMING`() {
            AnnounceFilter.shouldForward(
                outgoingMode = InterfaceMode.BOUNDARY,
                isLocalDestination = false,
                sourceMode = InterfaceMode.ROAMING
            ) shouldBe false
        }

        @Test
        fun `BOUNDARY allows when source is BOUNDARY`() {
            AnnounceFilter.shouldForward(
                outgoingMode = InterfaceMode.BOUNDARY,
                isLocalDestination = false,
                sourceMode = InterfaceMode.BOUNDARY
            ) shouldBe true
        }

        @Test
        fun `BOUNDARY allows when source is FULL`() {
            AnnounceFilter.shouldForward(
                outgoingMode = InterfaceMode.BOUNDARY,
                isLocalDestination = false,
                sourceMode = InterfaceMode.FULL
            ) shouldBe true
        }

        @Test
        fun `BOUNDARY allows when source is POINT_TO_POINT`() {
            AnnounceFilter.shouldForward(
                outgoingMode = InterfaceMode.BOUNDARY,
                isLocalDestination = false,
                sourceMode = InterfaceMode.POINT_TO_POINT
            ) shouldBe true
        }

        @Test
        fun `BOUNDARY allows when source is ACCESS_POINT`() {
            AnnounceFilter.shouldForward(
                outgoingMode = InterfaceMode.BOUNDARY,
                isLocalDestination = false,
                sourceMode = InterfaceMode.ACCESS_POINT
            ) shouldBe true
        }

        @Test
        fun `BOUNDARY allows when source is GATEWAY`() {
            AnnounceFilter.shouldForward(
                outgoingMode = InterfaceMode.BOUNDARY,
                isLocalDestination = false,
                sourceMode = InterfaceMode.GATEWAY
            ) shouldBe true
        }

        @Test
        fun `BOUNDARY blocks with null source mode`() {
            AnnounceFilter.shouldForward(
                outgoingMode = InterfaceMode.BOUNDARY,
                isLocalDestination = false,
                sourceMode = null
            ) shouldBe false
        }

        // --- FULL, POINT_TO_POINT, GATEWAY: always allow ---

        @Test
        fun `FULL always allows`() {
            AnnounceFilter.shouldForward(
                outgoingMode = InterfaceMode.FULL,
                isLocalDestination = false,
                sourceMode = InterfaceMode.ROAMING
            ) shouldBe true
        }

        @Test
        fun `FULL allows with null source mode`() {
            AnnounceFilter.shouldForward(
                outgoingMode = InterfaceMode.FULL,
                isLocalDestination = false,
                sourceMode = null
            ) shouldBe true
        }

        @Test
        fun `POINT_TO_POINT always allows`() {
            AnnounceFilter.shouldForward(
                outgoingMode = InterfaceMode.POINT_TO_POINT,
                isLocalDestination = false,
                sourceMode = InterfaceMode.ROAMING
            ) shouldBe true
        }

        @Test
        fun `GATEWAY always allows`() {
            AnnounceFilter.shouldForward(
                outgoingMode = InterfaceMode.GATEWAY,
                isLocalDestination = false,
                sourceMode = InterfaceMode.ROAMING
            ) shouldBe true
        }
    }

    @Nested
    @DisplayName("pathExpiryForMode")
    inner class PathExpiry {

        @Test
        fun `AP returns 24 hour expiry`() {
            AnnounceFilter.pathExpiryForMode(InterfaceMode.ACCESS_POINT) shouldBe
                TransportConstants.AP_PATH_TIME
        }

        @Test
        fun `ROAMING returns 6 hour expiry`() {
            AnnounceFilter.pathExpiryForMode(InterfaceMode.ROAMING) shouldBe
                TransportConstants.ROAMING_PATH_TIME
        }

        @Test
        fun `FULL returns 7 day expiry`() {
            AnnounceFilter.pathExpiryForMode(InterfaceMode.FULL) shouldBe
                TransportConstants.PATHFINDER_E
        }

        @Test
        fun `POINT_TO_POINT returns 7 day expiry`() {
            AnnounceFilter.pathExpiryForMode(InterfaceMode.POINT_TO_POINT) shouldBe
                TransportConstants.PATHFINDER_E
        }

        @Test
        fun `BOUNDARY returns 7 day expiry`() {
            AnnounceFilter.pathExpiryForMode(InterfaceMode.BOUNDARY) shouldBe
                TransportConstants.PATHFINDER_E
        }

        @Test
        fun `GATEWAY returns 7 day expiry`() {
            AnnounceFilter.pathExpiryForMode(InterfaceMode.GATEWAY) shouldBe
                TransportConstants.PATHFINDER_E
        }

        @Test
        fun `AP expiry is 24 hours in milliseconds`() {
            TransportConstants.AP_PATH_TIME shouldBe (24L * 60 * 60 * 1000)
        }

        @Test
        fun `ROAMING expiry is 6 hours in milliseconds`() {
            TransportConstants.ROAMING_PATH_TIME shouldBe (6L * 60 * 60 * 1000)
        }
    }
}
