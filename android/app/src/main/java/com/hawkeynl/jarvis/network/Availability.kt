package com.hawkeynl.jarvis.network

/** Why an optional Core read failed. */
enum class LoadFailure { SIGNIN, FORBIDDEN, NETWORK, FAILED }

/**
 * Result of an optional, read-only Core call for the hub and node pages.
 * [Unsupported] means this Core does not have the route (an older version);
 * the UI shows it as "Requires newer Core", never as an error.
 */
sealed interface Availability<out T> {
    data object Loading : Availability<Nothing>
    data class Ok<out T>(val value: T) : Availability<T>
    data object Unsupported : Availability<Nothing>
    data class Failed(val reason: LoadFailure) : Availability<Nothing>
}

val <T> Availability<T>.valueOrNull: T?
    get() = (this as? Availability.Ok)?.value

fun <T, R> Availability<T>.map(transform: (T) -> R): Availability<R> = when (this) {
    is Availability.Ok -> Availability.Ok(transform(value))
    Availability.Loading -> Availability.Loading
    Availability.Unsupported -> Availability.Unsupported
    is Availability.Failed -> this
}

/** Availability of a failed call: 404/405 = older Core, 401 = session to renew, 403 = turned off in Core;
 *  only a request that never got an answer is a connection problem. */
fun ApiResult<*>.failure(): Availability<Nothing> = when (this) {
    is ApiResult.Success -> throw IllegalStateException("Success is not a failure")
    ApiResult.Unauthorized -> Availability.Failed(LoadFailure.SIGNIN)
    is ApiResult.HttpError -> when (status) {
        404, 405 -> Availability.Unsupported
        401 -> Availability.Failed(LoadFailure.SIGNIN)
        403 -> Availability.Failed(LoadFailure.FORBIDDEN)
        else -> Availability.Failed(LoadFailure.FAILED)
    }
    is ApiResult.Unreachable -> Availability.Failed(LoadFailure.NETWORK)
    is ApiResult.InvalidResponse -> Availability.Failed(LoadFailure.FAILED)
}
