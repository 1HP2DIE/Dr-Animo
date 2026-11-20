package com.animo.app.net.webview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The attach/detach decision of the warm-up, with the window replaced by a fake.
 *
 * The real host ([DecorWindowHost]) needs a live Activity, a decor and a WebView, so it is
 * device-only. What runs here is the rule that matters everywhere: attach at most once, detach
 * exactly what was attached, and treat "no window" as "warm up unattached" rather than a failure.
 */
class WindowSlotTest {

    private class FakeView(val name: String)

    private class FakeHost(private val attachResult: Boolean = true) : WebViewHost<FakeView> {
        val attached = mutableListOf<FakeView>()
        val detached = mutableListOf<FakeView>()
        var failure: Throwable? = null

        override fun attach(view: FakeView): Boolean {
            failure?.let { throw it }
            if (!attachResult) return false
            attached += view
            return true
        }

        override fun detach(view: FakeView) {
            detached += view
        }
    }

    @Test
    fun `attaches once, then detaches the same view exactly once`() {
        val view = FakeView("warmup")
        val host = FakeHost()
        val slot = WindowSlot(view)

        assertTrue(slot.attach(host))
        assertTrue(slot.isAttached)
        // A second attach would put a second copy in the tree; it is ignored.
        assertFalse(slot.attach(host))

        slot.detach()
        slot.detach()

        assertEquals(listOf(view), host.attached)
        assertEquals(listOf(view), host.detached)
        assertFalse(slot.isAttached)
    }

    @Test
    fun `no window, a refused attach or a throwing host leaves nothing to detach`() {
        val view = FakeView("warmup")
        val slot = WindowSlot(view)

        assertFalse(slot.attach(null))

        val refusing = FakeHost(attachResult = false)
        assertFalse(slot.attach(refusing))
        assertTrue(refusing.attached.isEmpty())

        val throwing = FakeHost().apply { failure = IllegalStateException("decor gone") }
        val thrown = runCatching { slot.attach(throwing) }.exceptionOrNull()
        assertTrue(thrown is IllegalStateException)
        assertFalse(slot.isAttached)

        // Nothing was attached, so nothing may be pulled out of the hierarchy: the caller's
        // fallback is the plain unattached warm-up, not a detach of a view that never got in.
        slot.detach()
        assertTrue(refusing.detached.isEmpty())
        assertTrue(throwing.detached.isEmpty())
    }
}
