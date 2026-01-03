package network.reticulum.integration

import network.reticulum.test.main as tunnelTestMain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable

/**
 * Test for interoperability with Python Reticulum.
 * Requires Python tunnel test server to be running on port 4244.
 */
class PythonInteropTest {

    @Test
    @EnabledIfEnvironmentVariable(named = "PYTHON_SERVER_RUNNING", matches = "true")
    fun `Kotlin client connects to Python server and receives announces through tunnel`() {
        tunnelTestMain()
    }
}
