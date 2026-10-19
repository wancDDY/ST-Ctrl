package com.tavern.app.node

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The health probe decides whether a restarted service is up, and its answer is what the console
 * shows while an update or a cancel finishes. It reads the stock answer SillyTavern gives an
 * unauthenticated POST, so both renderings of that answer have to be recognised.
 */
class NodeHealthTest {

    @Test
    fun jsonRejectionCountsAsAnAnsweringCore() {
        assertTrue(
            NodeHealth.isHealthyResponse(403, """{"error":{"message":"Invalid CSRF token"}}""")
        )
    }

    @Test
    fun htmlRejectionPageCountsAsAnAnsweringCore() {
        // The same rejection, rendered as the HTML error page: the marker is on a later line,
        // which is why reading a single line made a healthy core look dead for the whole wait.
        val html = """
            <!DOCTYPE html>
            <html lang="en">
            <head>
            <meta charset="utf-8">
            <title>Error</title>
            </head>
            <body>
            <pre>ForbiddenError: Invalid CSRF token. Please refresh the page and try again.</pre>
            </body>
            </html>
        """.trimIndent()

        assertTrue(NodeHealth.isHealthyResponse(403, html))
    }

    @Test
    fun aServingCoreIsHealthyWithoutReadingTheBody() {
        assertTrue(NodeHealth.isHealthyResponse(200, ""))
        assertTrue(NodeHealth.isHealthyResponse(302, ""))
    }

    @Test
    fun anUnrelatedProcessOnThePortIsNotHealthy() {
        assertFalse(NodeHealth.isHealthyResponse(403, "<html>nginx</html>"))
        assertFalse(NodeHealth.isHealthyResponse(404, "Not found"))
        assertFalse(NodeHealth.isHealthyResponse(500, "boom"))
    }
}

