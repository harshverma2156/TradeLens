package com.tradelens.research

import com.tradelens.flow.DataPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.StringReader
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import javax.xml.parsers.DocumentBuilderFactory

/** One item as the feed reported it, before any processing. */
data class RawItem(val title: String, val link: String, val published: Long?, val publisher: String?)

/** Company news from a public feed. Returns [DataPoint.Unavailable] with the reason rather than an empty list. */
interface NewsSource {
    val name: String
    suspend fun search(company: String): DataPoint<List<RawItem>>
}

/** Exchange and company filings (NSE / BSE announcements). None is connected: see [UnavailableExchangeSource]. */
interface ExchangeAnnouncementSource {
    suspend fun announcements(symbol: String): DataPoint<List<RawItem>>
}

object UnavailableExchangeSource : ExchangeAnnouncementSource {
    override suspend fun announcements(symbol: String): DataPoint<List<RawItem>> = DataPoint.Unavailable(
        "No official exchange-announcement feed is connected. The exchanges' own announcement pages need a browser session " +
            "and are not read by this app. A licensed filings feed, via the backend, would supply them."
    )
}

/**
 * Google News search RSS (public, no key). It aggregates many publishers, so each item's publisher is read from its
 * <source> element and the publisher suffix is removed from the headline.
 */
class GoogleNewsRss(private val http: OkHttpClient) : NewsSource {
    override val name = "Google News (search feed)"

    override suspend fun search(company: String): DataPoint<List<RawItem>> = withContext(Dispatchers.IO) {
        val url = okhttp3.HttpUrl.Builder().scheme("https").host("news.google.com").addPathSegments("rss/search")
            .addQueryParameter("q", "$company NSE India stock").addQueryParameter("hl", "en-IN")
            .addQueryParameter("gl", "IN").addQueryParameter("ceid", "IN:en").build()
        try {
            http.newCall(Request.Builder().url(url).header("Accept", "application/rss+xml").build()).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext DataPoint.Unavailable("The news feed answered HTTP ${resp.code}; try again later.")
                val body = resp.body?.string().orEmpty()
                val items = parseRss(body)
                if (items.isEmpty()) DataPoint.Unavailable("The news feed returned no items for this company.")
                else DataPoint.Available(items, com.tradelens.flow.Provenance(name, items.mapNotNull { it.published }.maxOrNull(), System.currentTimeMillis(),
                    com.tradelens.marketdata.Freshness.LIVE, official = false, note = "Aggregated headlines; each links to its publisher."))
            }
        } catch (e: Exception) {
            DataPoint.Unavailable("Could not reach the news feed (${e.javaClass.simpleName}). Check the connection and refresh.")
        }
    }

    companion object {
        /** Parses an RSS 2.0 document into items. Malformed items are skipped; a malformed document gives an empty list. */
        fun parseRss(xml: String): List<RawItem> = try {
            val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(InputSource(StringReader(xml)))
            val nodes = doc.getElementsByTagName("item")
            (0 until nodes.length).mapNotNull { i ->
                val el = nodes.item(i) as? Element ?: return@mapNotNull null
                val title = text(el, "title") ?: return@mapNotNull null
                val link = text(el, "link") ?: return@mapNotNull null
                val publisher = text(el, "source")
                // Google appends " - Publisher" to every headline; remove it so the headline reads as written.
                val headline = if (publisher != null && title.endsWith(" - $publisher")) title.removeSuffix(" - $publisher") else title
                RawItem(headline.trim(), link.trim(), text(el, "pubDate")?.let { parseDate(it) }, publisher)
            }
        } catch (e: Exception) {
            emptyList()
        }

        private fun text(el: Element, tag: String): String? =
            el.getElementsByTagName(tag).item(0)?.textContent?.trim()?.takeIf { it.isNotEmpty() }

        /** RFC 822 dates, e.g. "Wed, 12 Nov 2026 10:00:00 GMT". */
        fun parseDate(s: String): Long? = try {
            SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.ENGLISH).apply { timeZone = TimeZone.getTimeZone("GMT") }.parse(s)?.time
        } catch (e: Exception) {
            null
        }
    }
}
