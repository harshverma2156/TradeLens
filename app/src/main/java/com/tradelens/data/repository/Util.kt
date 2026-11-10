package com.tradelens.data.repository

import kotlinx.coroutines.CancellationException
import retrofit2.HttpException
import java.io.IOException

/** Like runCatching, but never swallows coroutine cancellation. */
inline fun <T> runCatchingCancellable(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (e: CancellationException) {
    throw e
} catch (e: Throwable) {
    Result.failure(e)
}

fun Throwable.friendlyMessage(): String = when (this) {
    is HttpException -> when (code()) {
        404 -> "Symbol not found. Check the ticker (NSE stocks end in .NS, BSE in .BO)."
        401, 403 -> "The data provider refused the request (not authorised)."
        422 -> "The provider doesn't have data for this range at this interval."
        429 -> "The data server is limiting requests. Wait a minute and try again."
        else -> "The data server returned an error (${code()}). Try again shortly."
    }
    is DataException -> message ?: "No data available."
    is com.tradelens.marketdata.UnsupportedCapabilityException -> message ?: "Not available from this data provider."
    is IOException -> "No internet connection, or the data server didn't respond."
    else -> message ?: "Something went wrong."
}
