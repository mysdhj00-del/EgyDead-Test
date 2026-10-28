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
        "$mainUrl/episode/" to "المضاف حديثا من الحلقات"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        println("DEBUG: EgyDead getMainPage url=${request.data}, page=$page")
        val targetUrl = if (page > 1) "${request.data}page/$page/" else request.data
        val encodedUrl = encodeArabicUrl(targetUrl)

        val doc = app.get(encodedUrl, headers = headersMap).document
        val items = doc.select("div.PostItem, div.item, article").mapNotNull { element ->
            toSearchResult(element)
        }

        println("DEBUG: getMainPage items count=${items.size}")
        return newHomePageResponse(request.name, items)
    }

    private fun toSearchResult(element: Element): SearchResponse? {
        val titleElement = element.selectFirst("h3 a, h2 a, a.title") ?: return null
        val title = titleElement.text().trim()
        val href = fixUrl(titleElement.attr("href"))
        val posterUrl = fixUrl(element.selectFirst("img")?.attr("src") ?: element.selectFirst("img")?.attr("data-src") ?: "")

        val isMovie = !title.contains("الحلقة") && !title.contains("الموسم")

        return if (isMovie) {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
            }
        } else {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encodedQuery = URLEncoder.encode(query, "UTF-8").replace("+", "%20")
        val searchUrl = "$mainUrl/?s=$encodedQuery"
        println("DEBUG: EgyDead search url=$searchUrl")

        val doc = app.get(searchUrl, headers = headersMap).document
        val results = doc.select("div.PostItem, div.item, article").mapNotNull { element ->
            toSearchResult(element)
        }
        println("DEBUG: search results count=${results.size}")
        return results
    }

    override suspend fun load(url: String): LoadResponse {
        println("DEBUG: EgyDead load url=$url")
        val encodedUrl = encodeArabicUrl(url)
        val doc = app.get(encodedUrl, headers = headersMap).document

        val title = doc.selectFirst("h1.Title, h1")?.text()?.trim() ?: "بدون عنوان"
        val poster = fixUrl(doc.selectFirst("div.Poster img, div.thumb img")?.attr("src") ?: "")
        val description = doc.selectFirst("div.Story, div.desc")?.text()?.trim()

        val episodes = ArrayList<Episode>()
        val episodeElements = doc.select("ul.EpisodesList li a, div.episodes-list a")

        if (episodeElements.isNotEmpty()) {
            for (ep in episodeElements) {
                val epUrl = fixUrl(ep.attr("href"))
                val epName = ep.text().trim()
                val epNum = epName.filter { it.isDigit() }.toIntOrNull()
                episodes.add(
                    newEpisode(epUrl) {
                        this.name = epName
                        this.episode = epNum
                    }
                )
            }
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = description
            }
        } else {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = description
            }
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
