package com.hawkeynl.jarvis.ui

import androidx.compose.ui.graphics.ImageBitmap
import com.hawkeynl.jarvis.network.ConversationMessage
import com.hawkeynl.jarvis.network.PhotoMeta
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

// Chat photos before upload and the words around them, as in desktop chatPhotos.ts.
// Pure, so unit tests run them without a device.

const val MAX_PHOTOS = 4
/** Long side sent to Core; Core shrinks further to 1600 px and re-encodes. */
const val MAX_SIDE = 2048
const val JPEG_QUALITY = 88
/** Larger files are refused before decoding (a phone photo is far smaller). */
const val MAX_INPUT_BYTES = 30L * 1024 * 1024
const val UNSUPPORTED = "This photo format isn't supported; export it as JPEG."
const val CORE_TOO_OLD = "Your Home Node needs Jarvis Core v0.0.83 to receive photos."
const val TOO_LARGE = "This photo is too large."
const val UNREADABLE = "This photo could not be read."
const val TOO_MANY_PHOTOS = "Up to $MAX_PHOTOS photos per message."
/** Core attaches an unsent photo only within 23 h of its upload. */
const val SEND_WITHIN_MS = 23L * 60 * 60 * 1000
const val TOO_OLD_TO_SEND = "This photo waited too long; remove it and attach it again."
const val PHOTO_DISCLOSURE = "Photos go to your Claude/Codex subscription to be read; never to web search."

/** [width]×[height] scaled down (never up) to fit [max] on the long side. */
fun fitWithin(width: Int, height: Int, max: Int = MAX_SIDE): Pair<Int, Int> {
    val scale = minOf(1.0, max.toDouble() / maxOf(width, height))
    return maxOf(1, Math.round(width * scale).toInt()) to maxOf(1, Math.round(height * scale).toInt())
}

/** What to tell the owner when Core refuses an upload with [status]. */
fun uploadFailure(status: Int): String = when (status) {
    404 -> CORE_TOO_OLD
    400 -> UNREADABLE
    415 -> UNSUPPORTED
    413 -> TOO_LARGE
    429 -> "Too many photos for now; try again later."
    503 -> "Photos are busy or unavailable on your Home Node; try again."
    507 -> "Photo storage on your Home Node is full."
    else -> "The photo could not be uploaded."
}

fun tooOldToSend(uploadedAt: Long?, now: Long): Boolean = uploadedAt != null && now - uploadedAt > SEND_WITHIN_MS

/** What to say when Core refused a new run outright (it stored nothing), or null when
 *  the outcome is unknown. A 404 is a deleted conversation or a photo that is gone,
 *  already sent or too old. */
fun runRefusal(status: Int?): String? = when (status) {
    400 -> "Your Home Node refused this message. Nothing was sent."
    404 -> "Your Home Node no longer has this conversation or one of these photos. Nothing was sent."
    else -> null
}

/** Photo ids the messages carry that the conversation no longer has (deleted or
 *  expired): shown as placeholders without a download. */
fun missingPhotos(messages: List<ConversationMessage>, photos: List<PhotoMeta>): Set<String> {
    val live = photos.mapTo(HashSet()) { it.id }
    return messages.flatMap { it.attachments }.filterNot { it in live }.toSet()
}

/** How long a photo stays, for the full-screen view. Without metadata (a photo just
 *  sent) the general rule. Core reports a date for a kept photo too while no message
 *  carries it yet (upload + 24 h): then that date counts. */
fun expiryHint(meta: PhotoMeta?, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String {
    if (meta?.saved == true && meta.expires_at == null) return "Kept until you delete it."
    val at = meta?.expires_at?.let { runCatching { Instant.parse(it) }.getOrNull() }
        ?: return "Photos are kept 90 days unless you keep them."
    val day = DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG).withLocale(locale).format(at.atZone(zone))
    return if (meta.saved) "Removed on $day." else "Removed on $day unless you keep it."
}

enum class ChipState { PREPARING, UPLOADING, READY, FAILED }

/** A photo in the composer. [at] is when its upload finished (ms since epoch). */
data class PhotoChip(
    val key: Int,
    val state: ChipState = ChipState.PREPARING,
    val preview: ImageBitmap? = null,
    val id: String? = null,
    val error: String? = null,
    val at: Long? = null,
)

/** Photos must all be uploaded first; a photo alone is a message too. */
fun canSend(text: String, chips: List<PhotoChip>, busy: Boolean): Boolean =
    (text.isNotBlank() || chips.isNotEmpty()) && chips.all { it.state == ChipState.READY } && !busy

fun uploading(chips: List<PhotoChip>): Boolean = chips.any { it.state == ChipState.PREPARING || it.state == ChipState.UPLOADING }

/** The uploaded photos' ids, in order. */
fun chipIds(chips: List<PhotoChip>): List<String> = chips.mapNotNull { if (it.state == ChipState.READY) it.id else null }

/** Photos Core would no longer attach (uploaded over 23 h ago) fail in place. */
fun expireStale(chips: List<PhotoChip>, now: Long): List<PhotoChip> = chips.map {
    if (it.state == ChipState.READY && tooOldToSend(it.at, now)) it.copy(state = ChipState.FAILED, error = TOO_OLD_TO_SEND) else it
}

/** The composer after a refused message: its photos come back ahead of any added since,
 *  as far as there is room; [dropped] are the ids to delete on the Home Node. */
data class Restored(val chips: List<PhotoChip>, val dropped: List<String>, val notice: String?)

fun restoreChips(current: List<PhotoChip>, taken: List<PhotoChip>): Restored {
    val room = maxOf(MAX_PHOTOS - current.size, 0)
    return Restored(
        taken.take(room) + current,
        taken.drop(room).mapNotNull { it.id },
        if (taken.size > room) TOO_MANY_PHOTOS else null,
    )
}
