package org.briarproject.mailbox.core.files

import io.ktor.application.ApplicationCall
import io.ktor.auth.principal
import io.ktor.features.BadRequestException
import io.ktor.http.HttpStatusCode
import io.ktor.request.receiveStream
import io.ktor.response.respond
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import kotlinx.coroutines.runBlocking
import org.briarproject.mailbox.core.TestUtils.getNewRandomId
import org.briarproject.mailbox.core.db.Database
import org.briarproject.mailbox.core.server.AuthManager
import org.briarproject.mailbox.core.server.MailboxPrincipal
import org.briarproject.mailbox.core.server.MailboxPrincipal.OwnerPrincipal
import org.briarproject.mailbox.core.system.RandomIdManager
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.File
import kotlin.random.Random
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FileRouteManagerTest {

    private val db: Database = mockk()
    private val authManager: AuthManager = mockk()
    private val fileProvider: FileProvider = mockk()
    private val randomIdManager = RandomIdManager()

    private val fileRouteManager = FileRouteManager(db, authManager, fileProvider, randomIdManager)

    private val call: ApplicationCall = mockk()
    private val id = getNewRandomId()
    private val bytes = Random.nextBytes(2048)

    init {
        mockkStatic("io.ktor.auth.AuthenticationKt")
        mockkStatic("io.ktor.request.ApplicationReceiveFunctionsKt")
        mockkStatic("io.ktor.response.ApplicationResponseFunctionsKt")
    }

    @Test
    fun `post new file stores file correctly`(@TempDir tempDir: File) = runBlocking {
        postFile(tempDir, bytes)
    }

    @Test
    fun `post a new file with max size gets accepted`(@TempDir tempDir: File) = runBlocking {
        val maxBytes = Random.nextBytes(MAX_FILE_SIZE)
        postFile(tempDir, maxBytes)
    }

    @Test
    fun `post file above max size gets rejected`(@TempDir tempDir: File) = runBlocking {
        val maxBytes = Random.nextBytes(MAX_FILE_SIZE + 1)
        assertFailsWith<BadRequestException> {
            postFile(tempDir, maxBytes)
        }
        Unit
    }

    private suspend fun postFile(tempDir: File, bytes: ByteArray) {
        val tmpFile = File(tempDir, "tmp")
        val finalFile = File(tempDir, "final")

        every { call.principal<MailboxPrincipal>() } returns OwnerPrincipal
        every { authManager.assertCanPostToFolder(OwnerPrincipal, id) } just Runs
        every { fileProvider.getTemporaryFile(any()) } returns tmpFile
        coEvery { call.receiveStream() } returns ByteArrayInputStream(bytes)
        every { fileProvider.getFile(id, any()) } returns finalFile
        coEvery { call.respond(HttpStatusCode.OK) } just Runs

        fileRouteManager.postFile(call, id)

        assertFalse(tmpFile.exists())
        assertTrue(finalFile.exists())
        assertArrayEquals(bytes, finalFile.readBytes())
    }

}
