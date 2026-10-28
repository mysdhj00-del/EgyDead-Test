package com.egydead

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

class EgyDeadProvider : MainAPI() {
    override var mainUrl = "https://tv10.egydead.live"
    override var name = "EgyDead"
    override val hasMainPage = true
    override var lang = "ar"
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime
    )

    private val headersMap = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36",
        "Referer" to "$mainUrl/"
    )

    override val mainPage = mainPageOf(
        "$mainUrl/h3/" to "الرئيسية وأحدث الحلقات",
        "$mainUrl/Series/" to "أحدث المواسم والمسلسلات",
        "$mainUrl/episodes/" to "المضاف حديثا من الحلقات"
    )

    // Real site law (traced live 2026-10-28, all pages HTTP 200):
    // Every listing card is an <a> containing an <img> and an <h1> directly
    // (a > img + h1). The v1 selectors (div.PostItem, h3 a, h2 a) match nothing
    // on this site, which is why v1 home rows were empty.
    // /episode/ redirects to /episodes/, so the third row uses /episodes/.
    // Pagination: h3/ paginates as /page/N/, Series/ as /Series/page/N/,
    // episodes/ as /episodes/page/N/.
    private fun cardElements(doc: org.jsoup.nodes.Document): List<Element> {
        return doc.select("a").filter { a ->
            val href = a.attr("href")
            href.isNotEmpty() && !href.startsWith("#") &&
                a.selectFirst("img") != null &&
                a.selectFirst("h1") != null
        }
    }

    private fun posterFrom(card: Element): String {
        val img = card.selectFirst("img") ?: return ""
        val src = img.attr("src")
            .ifEmpty { img.attr("data-src") }
            .ifEmpty { img.attr("data-lazy-src") }
            .ifEmpty { img.attr("srcset").split(",").firstOrNull()?.trim()?.split(" ")?.firstOrNull() ?: "" }
        return fixUrl(src)
    }

    private fun toSearchResult(card: Element): SearchResponse? {
        val title = card.selectFirst("h1")?.text()?.trim() ?: return null
        if (title.isEmpty()) return null
        val href = fixUrl(card.attr("href"))
        if (href.isEmpty()) return null
        val posterUrl = posterFrom(card)

        return when {
            href.contains("/episodes/") || title.contains("الحلقة") || title.contains("حلقه") -> {
                newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                    this.posterUrl = posterUrl
                }
            }
            href.contains("/Series/") || href.contains("/seasons/") || title.contains("الموسم") -> {
                newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                    this.posterUrl = posterUrl
                }
            }
            else -> {
                newMovieSearchResponse(title, href, TvType.Movie) {
                    this.posterUrl = posterUrl
                }
            }
        }
    }

    private fun pageUrlFor(request: MainPageRequest, page: Int): String {
        if (page <= 1) return request.data
        return if (request.data.contains("/h3/")) {
            "$mainUrl/page/$page/"
        } else {
            "${request.data}page/$page/"
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val targetUrl = pageUrlFor(request, page)
        println("DEBUG: EgyDead getMainPage url=$targetUrl, page=$page")
        val encodedUrl = encodeArabicUrl(targetUrl)

        val doc = app.get(encodedUrl, headers = headersMap).document
        val items = cardElements(doc).mapNotNull { card ->
            toSearchResult(card)
        }.distinctBy { it.url }

        println("DEBUG: getMainPage items count=${items.size}")
        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encodedQuery = URLEncoder.encode(query, "UTF-8").replace("+", "%20")
        val searchUrl = "$mainUrl/?s=$encodedQuery"
        println("DEBUG: EgyDead search url=$searchUrl")

        val doc = app.get(searchUrl, headers = headersMap).document
        val results = cardElements(doc).mapNotNull { card ->
            toSearchResult(card)
        }.distinctBy { it.url }
        println("DEBUG: search results count=${results.size}")
        return results
    }

    private fun seasonNumberFrom(url: String): Int? {
        return Regex("-s(\\d+)").find(url)?.groupValues?.get(1)?.toIntOrNull()
    }

    private fun episodeNumberFrom(url: String): Int? {
        return Regex("-e(\\d+)").find(url)?.groupValues?.get(1)?.toIntOrNull()
            ?: Regex("e(\\d+)$").find(url)?.groupValues?.get(1)?.toIntOrNull()
    }

    override suspend fun load(url: String): LoadResponse {
        println("DEBUG: EgyDead load url=$url")
        val encodedUrl = encodeArabicUrl(url)
        val doc = app.get(encodedUrl, headers = headersMap).document

        val title = doc.selectFirst("h1")?.text()?.trim() ?: "بدون عنوان"
        val posterImg = doc.select("img[src*=/wp-content/uploads/]").firstOrNull {
            val s = it.attr("src")
            !s.contains("-200x280") && !s.contains("-225x280")
        } ?: doc.selectFirst("img[src*=/wp-content/uploads/]")
        val poster = fixUrl(posterImg?.attr("src") ?: "")
        val description = doc.select("h1, h2, h3").firstOrNull { it.text().contains("القصه") }
            ?.parent()?.text()?.trim()
            ?.removePrefix("القصه")?.trim()

        // Series page: seasons are cards (a > img + h1) linking to /seasons/.
        // Flatten every season's episodes into one series load.
        if (url.contains("/Series/")) {
            val seasonLinks = cardElements(doc).mapNotNull { card ->
                val href = fixUrl(card.attr("href"))
                if (href.contains("/seasons/")) href else null
            }.distinct()
            val episodes = ArrayList<Episode>()
            for (seasonUrl in seasonLinks) {
                val seasonNum = seasonNumberFrom(seasonUrl)
                try {
                    val seasonDoc = app.get(encodeArabicUrl(seasonUrl), headers = headersMap).document
                    val epLinks = seasonDoc.select("a[href*=\"/episodes/\"]").filter {
                        it.text().contains("حلقه") || episodeNumberFrom(it.attr("href")) != null
                    }
                    for (ep in epLinks) {
                        val epUrl = fixUrl(ep.attr("href"))
                        val epNum = episodeNumberFrom(epUrl)
                        val epName = ep.text().trim().ifEmpty { "حلقه ${epNum ?: ""}" }
                        episodes.add(
                            newEpisode(epUrl) {
                                this.name = epName
                                this.episode = epNum
                                this.season = seasonNum
                            }
                        )
                    }
                } catch (e: Exception) {
                    println("DEBUG: EgyDead season fetch failed season=$seasonUrl error=${e.message}")
                }
            }
            if (episodes.isNotEmpty()) {
                return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                    this.posterUrl = poster
                    this.plot = description
                }
            }
            // Fall through to a single playable entry if no seasons were found.
        }

        // Season page: text links "حلقه N" to /episodes/ under "حلقات الموسم".
        if (url.contains("/seasons/")) {
            val seasonNum = seasonNumberFrom(url)
            val episodes = ArrayList<Episode>()
            val epLinks = doc.select("a[href*=\"/episodes/\"]").filter {
                it.text().contains("حلقه") || episodeNumberFrom(it.attr("href")) != null
            }
            for (ep in epLinks) {
                val epUrl = fixUrl(ep.attr("href"))
                val epNum = episodeNumberFrom(epUrl)
                val epName = ep.text().trim().ifEmpty { "حلقه ${epNum ?: ""}" }
                episodes.add(
                    newEpisode(epUrl) {
                        this.name = epName
                        this.episode = epNum
                        this.season = seasonNum
                    }
                )
            }
            if (episodes.isNotEmpty()) {
                return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                    this.posterUrl = poster
                    this.plot = description
                }
            }
        }

        // Single episode page: one playable episode.
        if (url.contains("/episodes/")) {
            val epNum = episodeNumberFrom(url)
            val episode = newEpisode(url) {
                this.name = title
                this.episode = epNum
            }
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, listOf(episode)) {
                this.posterUrl = poster
                this.plot = description
            }
        }

        // Movie page (root slug).
        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.plot = description
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        println("DEBUG: EgyDead loadLinks data=$data")
        val encodedUrl = encodeArabicUrl(data)

        val doc = app.post(encodedUrl, headers = headersMap, data = mapOf("View" to "1")).document

        val watchUrl = doc.selectFirst("iframe")?.attr("src")
            ?: doc.selectFirst("a.watch-btn, div.serversList a")?.attr("href")
            ?: data

        println("DEBUG: EgyDead watchUrl/iframe=$watchUrl")

        if (watchUrl.contains("q7v3k8m2p1.top") || watchUrl.contains("MegaMax")) {
            val playerDoc = app.get(fixUrl(watchUrl), headers = headersMap).document

            val mirrors = playerDoc.select("ul.servers-list li, div.mirrors a, iframe, div.server-item, [data-link], [data-url]")
            for (mirror in mirrors) {
                val mirrorLink = mirror.attr("data-link")
                    .ifEmpty { mirror.attr("data-url") }
                    .ifEmpty { mirror.attr("src") }
                    .ifEmpty { mirror.attr("href") }

                if (mirrorLink.isNotEmpty() && !mirrorLink.startsWith("#")) {
                    println("DEBUG: Found MegaMax mirror link: $mirrorLink")
                    loadExtractor(fixUrl(mirrorLink), "$mainUrl/", subtitleCallback, callback)
                }
            }
        } else {
            loadExtractor(fixUrl(watchUrl), "$mainUrl/", subtitleCallback, callback)
        }

        return true
    }

    private fun encodeArabicUrl(url: String): String {
        return try {
            val encodedPath = url.split("/").mapIndexed { index, part ->
                if (index < 3) part else URLEncoder.encode(part, StandardCharsets.UTF_8.toString()).replace("+", "%20")
            }.joinToString("/")

            encodedPath.replace("%3A", ":")
                .replace("%2F", "/")
                .replace("%3F", "?")
                .replace("%3D", "=")
                .replace("%26", "&")
        } catch (e: Exception) {
            url
        }
    }
}
