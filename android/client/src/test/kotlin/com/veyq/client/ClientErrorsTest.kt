package com.veyq.client

import com.veyq.api.content.infrastructure.ClientException as ContentClientException
import com.veyq.api.media.infrastructure.ClientException as MediaClientException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** Guards the shared error-mapping seam VeyqClient's ~15 resource calls delegate to (cr-0142). */
class ClientErrorsTest {
    @Test
    fun `clientStatusCode reads any generated package's status, null for others`() {
        assertEquals(404, clientStatusCode(ContentClientException(statusCode = 404)))
        assertEquals(503, clientStatusCode(MediaClientException(statusCode = 503)))
        assertNull(clientStatusCode(IllegalStateException("bug")))
        assertNull(clientStatusCode(RuntimeException("plain")))
    }

    @Test
    fun `mapStatus translates a client error through the caller's map`() {
        val mapped = assertFailsWith<VeyqError.SlugTaken> {
            mapStatus({ status -> if (status == 409) VeyqError.SlugTaken else VeyqError.UnexpectedStatus(status) }) {
                throw ContentClientException(statusCode = 409)
            }
        }
        assertEquals(VeyqError.SlugTaken, mapped)
    }

    @Test
    fun `mapStatus returns the block result on success`() {
        assertEquals(42, mapStatus({ VeyqError.UnexpectedStatus(it) }) { 42 })
    }

    @Test
    fun `mapStatus re-throws a non-client throwable untouched`() {
        assertFailsWith<IllegalArgumentException> {
            mapStatus({ VeyqError.UnexpectedStatus(it) }) { throw IllegalArgumentException("not an API error") }
        }
    }
}
