/**
 * Session isolation for the behavioral dedup budget.
 *
 * `behavioral_hashlist_limit` sets the process-wide Transport budget, so it
 * must not outlive the session that asked for it: a small limit left behind
 * would make every later session rotate at that limit instead of the platform
 * recommendation, and dedup assertions would depend on test order.
 */

import com.google.gson.JsonObject
import network.reticulum.Reticulum
import network.reticulum.common.Platform
import network.reticulum.transport.Transport
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BehavioralHashlistSessionTest {

    private var handle: String? = null

    @AfterTest
    fun stopSession() {
        handle?.let {
            val stop = JsonObject().apply { addProperty("handle", it) }
            handleBehavioralCommand("behavioral_stop", stop)
        }
        handle = null
        Reticulum.stop()
        Transport.setHashlistLimitForTest(null)
    }

    @Test
    fun freshSessionResetsTheBudgetToThePlatformRecommendation() {
        Transport.setHashlistLimitForTest(10)
        assertEquals(10, Transport.hashlistBudgetForTest(), "the override must be in force before the session starts")

        val started = handleBehavioralCommand("behavioral_start", JsonObject())
        val session = started.get("handle").asString
        handle = session

        assertTrue(started.get("identity_hash").asString.isNotEmpty(), "start must report an identity hash")
        assertEquals(
            Platform.recommendedHashlistSize,
            Transport.hashlistBudgetForTest(),
            "a fresh session must rotate at the platform recommendation, not at the previous session's test override",
        )

        val params = JsonObject().apply { addProperty("handle", session) }
        val fresh = handleBehavioralCommand("behavioral_hashlist_stats", params)
        assertEquals(
            Platform.recommendedHashlistSize,
            fresh.get("limit").asInt,
            "behavioral_hashlist_stats must report the budget actually in force",
        )

        handleBehavioralCommand(
            "behavioral_hashlist_limit",
            JsonObject().apply {
                addProperty("handle", session)
                addProperty("limit", 8)
            },
        )
        val limited = handleBehavioralCommand("behavioral_hashlist_stats", params)
        assertEquals(8, limited.get("limit").asInt, "behavioral_hashlist_limit must take effect for its session")
    }
}
