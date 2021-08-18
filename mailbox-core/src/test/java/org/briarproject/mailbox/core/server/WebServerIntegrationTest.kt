package org.briarproject.mailbox.core.server

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.readText
import kotlinx.coroutines.runBlocking
import org.briarproject.mailbox.core.DaggerTestComponent
import org.briarproject.mailbox.core.server.WebServerManager.Companion.PORT
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestInstance.Lifecycle
import kotlin.test.assertEquals

@TestInstance(Lifecycle.PER_CLASS)
class WebServerIntegrationTest {

    private val testComponent = DaggerTestComponent.builder().build()
    private val lifecycleManager = testComponent.getLifecycleManager()
    private val httpClient = HttpClient(CIO) {
        expectSuccess = false // prevents exceptions on non-success responses
    }
    private val baseUrl = "http://127.0.0.1:$PORT"

    @BeforeAll
    fun setUp() {
        testComponent.injectCoreEagerSingletons()
        lifecycleManager.startServices()
        lifecycleManager.waitForStartup()
    }

    @AfterAll
    fun tearDown() {
        lifecycleManager.stopServices()
        lifecycleManager.waitForShutdown()
    }

    @Test
    fun routeRespondsWithHelloWorldString(): Unit = runBlocking {
        val response: HttpResponse = httpClient.get("$baseUrl/")
        assertEquals(200, response.status.value)
        assertEquals("Hello world!", response.readText())
    }

    @Test
    fun routeNotFound(): Unit = runBlocking {
        val response: HttpResponse = httpClient.get("$baseUrl/404")
        assertEquals(404, response.status.value)
    }

}
