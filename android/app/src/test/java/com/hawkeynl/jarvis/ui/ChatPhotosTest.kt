package com.hawkeynl.jarvis.ui

import com.hawkeynl.jarvis.network.ConversationMessage
import com.hawkeynl.jarvis.network.PhotoMeta
import java.time.ZoneOffset
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Mirrors desktop tests/chat-photos.test.mjs.
class ChatPhotosTest {
    @Test fun `photos shrink to 2048 px on the long side and never grow`() {
        assertEquals(2048 to 1536, fitWithin(4032, 3024))
        assertEquals(1536 to 2048, fitWithin(3024, 4032))
        assertEquals(800 to 600, fitWithin(800, 600))
        assertEquals(2048 to 1, fitWithin(100000, 1))
        assertEquals(512 to 384, fitWithin(2048, 1536, 512))
        assertEquals(2048, MAX_SIDE)
        assertEquals(88, JPEG_QUALITY)
        assertEquals(30L * 1024 * 1024, MAX_INPUT_BYTES)
    }

    @Test fun `upload refusals have their own words and 404 means an older Core`() {
        assertEquals(CORE_TOO_OLD, uploadFailure(404))
        assertEquals("Your Home Node needs Jarvis Core v0.0.83 to receive photos.", CORE_TOO_OLD)
        assertEquals(UNSUPPORTED, uploadFailure(415))
        // A 400 is any unreadable body, not only a format: no "export as JPEG".
        assertEquals("This photo could not be read.", uploadFailure(400))
        assertEquals(TOO_LARGE, uploadFailure(413))
        assertEquals("Too many photos for now; try again later.", uploadFailure(429))
        assertEquals("Photos are busy or unavailable on your Home Node; try again.", uploadFailure(503))
        assertEquals("Photo storage on your Home Node is full.", uploadFailure(507))
        assertEquals("The photo could not be uploaded.", uploadFailure(500))
    }

    @Test fun `only a 400 or 404 on submit is a definite refusal`() {
        assertTrue(runRefusal(400)!!.endsWith("Nothing was sent."))
        assertTrue(runRefusal(404)!!.endsWith("Nothing was sent."))
        for (status in listOf(null, 0, 401, 409, 500, 503)) assertNull("$status", runRefusal(status))
    }

    @Test fun `a photo older than 23 hours is not sent`() {
        val now = 1_794_484_800_000L
        assertEquals(23L * 3600 * 1000, SEND_WITHIN_MS)
        assertFalse(tooOldToSend(now - SEND_WITHIN_MS, now))
        assertTrue(tooOldToSend(now - SEND_WITHIN_MS - 1, now))
        assertFalse(tooOldToSend(null, now))
        val chips = listOf(
            PhotoChip(1, ChipState.READY, id = "a", at = now - SEND_WITHIN_MS - 1),
            PhotoChip(2, ChipState.READY, id = "b", at = now),
            PhotoChip(3, ChipState.UPLOADING),
        )
        val expired = expireStale(chips, now)
        assertEquals(ChipState.FAILED, expired[0].state)
        assertEquals("This photo waited too long; remove it and attach it again.", expired[0].error)
        assertEquals(chips.drop(1), expired.drop(1))
    }

    @Test fun `photos a message names but the conversation lacks are shown as removed`() {
        fun message(vararg ids: String) = ConversationMessage("user", "", at = "x", attachments = ids.toList())
        val messages = listOf(message("a", "b"), message(), message("c"))
        assertEquals(setOf("b"), missingPhotos(messages, listOf(PhotoMeta("a", false), PhotoMeta("c", true))))
        assertEquals(setOf("a", "b", "c"), missingPhotos(messages, emptyList()))
        assertTrue(missingPhotos(listOf(message()), emptyList()).isEmpty())
    }

    @Test fun `the full-screen view says how long a photo stays`() {
        fun hint(meta: PhotoMeta?) = expiryHint(meta, ZoneOffset.UTC, Locale.UK)
        assertEquals("Kept until you delete it.", hint(PhotoMeta("a", true, null)))
        assertEquals("Photos are kept 90 days unless you keep them.", hint(null))
        assertEquals("Photos are kept 90 days unless you keep them.", hint(PhotoMeta("a", false, "nonsense")))
        assertEquals("Removed on 10 February 2027 unless you keep it.", hint(PhotoMeta("a", false, "2027-02-10T12:00:00Z")))
        // Kept but not sent yet: Core still removes it 24 h after the upload.
        assertEquals("Removed on 10 February 2027.", hint(PhotoMeta("a", true, "2027-02-10T12:00:00Z")))
    }

    @Test fun `send waits until every photo is uploaded and a photo alone is a message`() {
        val ready = PhotoChip(1, ChipState.READY, id = "a")
        assertTrue(canSend("hi", emptyList(), busy = false))
        assertTrue(canSend("", listOf(ready), busy = false))
        assertFalse(canSend("  ", emptyList(), busy = false))
        assertFalse(canSend("hi", emptyList(), busy = true))
        for (state in listOf(ChipState.PREPARING, ChipState.UPLOADING, ChipState.FAILED)) {
            assertFalse("$state", canSend("hi", listOf(ready, PhotoChip(2, state)), busy = false))
        }
        assertTrue(uploading(listOf(ready, PhotoChip(2, ChipState.PREPARING))))
        assertFalse(uploading(listOf(ready, PhotoChip(2, ChipState.FAILED))))
        assertEquals(listOf("a"), chipIds(listOf(ready, PhotoChip(2, ChipState.FAILED, id = "b"))))
    }

    @Test fun `a refused message comes back ahead of photos added since, as far as there is room`() {
        val taken = listOf(PhotoChip(1, ChipState.READY, id = "a"), PhotoChip(2, ChipState.READY, id = "b"))
        val added = listOf(PhotoChip(3))
        assertEquals(Restored(taken + added, emptyList(), null), restoreChips(added, taken))
        val full = List(3) { PhotoChip(10 + it) }
        val back = restoreChips(full, taken)
        assertEquals(listOf(1, 10, 11, 12), back.chips.map { it.key })
        assertEquals(listOf("b"), back.dropped)
        assertEquals("Up to 4 photos per message.", back.notice)
    }

    @Test fun `a late answer from another sign-in restores and deletes nothing`() {
        assertEquals(SubmitOutcome.REFUSED, submitFailure(400, sameSignIn = true))
        assertEquals(SubmitOutcome.REFUSED, submitFailure(404, sameSignIn = true))
        assertEquals(SubmitOutcome.UNCONFIRMED, submitFailure(503, sameSignIn = true))
        assertEquals(SubmitOutcome.UNCONFIRMED, submitFailure(null, sameSignIn = true))
        for (status in listOf(400, 404, 503, null)) assertEquals(SubmitOutcome.IGNORE, submitFailure(status, sameSignIn = false))

        // Only a photo whose chip was removed meanwhile, in the same sign-in, is deleted.
        assertTrue(forgetAfterUpload(chipShown = false, id = "a", sameSignIn = true))
        assertFalse(forgetAfterUpload(chipShown = true, id = "a", sameSignIn = true))
        assertFalse(forgetAfterUpload(chipShown = false, id = null, sameSignIn = true))
        assertFalse(forgetAfterUpload(chipShown = false, id = "a", sameSignIn = false))
    }

    @Test fun `photos picked while locked are attached after unlock only in the same sign-in`() {
        assertEquals(listOf("a", "b"), pickedToAttach(3, 3, listOf("a", "b")))
        assertEquals(emptyList<String>(), pickedToAttach(3, 4, listOf("a", "b")))
    }
}
