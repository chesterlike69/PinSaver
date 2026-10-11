package com.example.pinsaver

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

class PinRepository(private val dao: PinDao) {

    private val client = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val ua = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

    val pins: Flow<List<Pin>> = dao.observeAll()

    suspend fun saveFromUrl(rawUrl: String) = withContext(Dispatchers.IO) {
        val cleaned = cleanUrl(rawUrl) ?: return@withContext
        Log.d("PinSaver", "saving: $cleaned")

        val resolved = resolveShortUrl(cleaned) ?: cleaned
        Log.d("PinSaver", "resolved: $resolved")

        var meta = runCatching { fetchOEmbed(resolved) }
            .onFailure { Log.w("PinSaver", "oembed failed", it) }
            .getOrNull()

        if (meta == null || meta.thumbnail == null) {
            Log.d("PinSaver", "falling back to HTML parse")
            meta = runCatching { parseHtml(resolved) }
                .onFailure { Log.w("PinSaver", "html parse failed", it) }
                .getOrNull() ?: meta
        }

        // Автор: сначала из meta, если пусто — вытаскиваем из title
        val author = meta?.author?.takeIf { it.isNotBlank() }
            ?: extractAuthorFromTitle(meta?.title)

        // Заголовок: убираем префикс "Взгляните на этот пин от ..."
        val cleanTitle = stripPinterestPrefix(meta?.title)

        dao.insert(
            Pin(
                url = resolved,
                title = cleanTitle,
                author = author,
                thumbnailUrl = meta?.thumbnail,
                thumbWidth = meta?.width ?: 0,
                thumbHeight = meta?.height ?: 0,
                savedAt = System.currentTimeMillis()
            )
        )
    }

    suspend fun mergePins(newPins: List<Pin>): Int = withContext(Dispatchers.IO) {
        if (newPins.isEmpty()) return@withContext 0
        dao.insertAll(newPins)
        newPins.size
    }

    // ---------- URL ----------

    private fun cleanUrl(raw: String): String? {
        val trimmed = raw.trim()
        if (!trimmed.startsWith("http")) return null
        return trimmed.substringBefore('#').substringBefore('?')
    }

    private fun resolveShortUrl(url: String): String? {
        if (!url.contains("pin.it")) return url
        return try {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", ua)
                .build()
            client.newCall(req).execute().use { resp ->
                resp.request.url.toString()
            }
        } catch (e: Exception) {
            Log.w("PinSaver", "resolve failed", e)
            null
        }
    }

    // ---------- Метаданные ----------

    private data class Meta(
        val title: String?,
        val author: String?,
        val thumbnail: String?,
        val width: Int,
        val height: Int
    )

    private fun fetchOEmbed(url: String): Meta? {
        val enc = URLEncoder.encode(url, StandardCharsets.UTF_8.toString())
        val req = Request.Builder()
            .url("https://www.pinterest.com/oembed.json?url=$enc")
            .header("User-Agent", ua)
            .header("Accept", "application/json")
            .build()
        client.newCall(req).execute().use { resp ->
            Log.d("PinSaver", "oembed response: ${resp.code}")
            if (!resp.isSuccessful) return null
            val body = resp.body?.string().orEmpty()
            if (body.isBlank()) return null
            val j = runCatching { JSONObject(body) }.getOrNull() ?: return null
            return Meta(
                title = j.optString("title").ifBlank { null },
                author = j.optString("author_name").ifBlank { null },
                thumbnail = j.optString("thumbnail_url").ifBlank { null },
                width = j.optInt("thumbnail_width"),
                height = j.optInt("thumbnail_height")
            )
        }
    }

    private fun parseHtml(url: String): Meta? {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", ua)
            .header("Accept", "text/html")
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val html = resp.body?.string().orEmpty()
            if (html.isBlank()) return null

            val image = extractOg(html, "og:image")
            val title = extractOg(html, "og:title")
            val desc = extractOg(html, "og:description")
            val width = extractOg(html, "og:image:width")?.toIntOrNull() ?: 0
            val height = extractOg(html, "og:image:height")?.toIntOrNull() ?: 0

            return Meta(
                title = title,
                author = desc,
                thumbnail = image,
                width = width,
                height = height
            )
        }
    }

    private fun extractOg(html: String, property: String): String? {
        val regex = Regex(
            """<meta[^>]+property=["']${Regex.escape(property)}["'][^>]+content=["']([^"']+)["']""",
            RegexOption.IGNORE_CASE
        )
        return regex.find(html)?.groupValues?.get(1)
            ?: Regex(
                """<meta[^>]+content=["']([^"']+)["'][^>]+property=["']${Regex.escape(property)}["']""",
                RegexOption.IGNORE_CASE
            ).find(html)?.groupValues?.get(1)
    }

    // ---------- Парсинг Pinterest-boilerplate ----------

    /**
     * Pinterest подставляет в og:title шаблонные фразы вида:
     *   "Взгляните на этот пин от Chester"
     *   "Check out this Pin from Chester"
     *
     * Если строка начинается с такого префикса — возвращаем null
     * (заголовок бесполезен как название, а имя автора уже вытащим отдельно).
     */
    private fun stripPinterestPrefix(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        for (prefix in pinterestTitlePrefixes) {
            if (raw.startsWith(prefix, ignoreCase = true)) {
                return null
            }
        }
        return raw
    }

    /**
     * Достаёт имя автора из boilerplate-заголовка.
     * "Взгляните на этот пин от Chester" -> "Chester"
     */
    private fun extractAuthorFromTitle(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        for (prefix in pinterestTitlePrefixes) {
            if (raw.startsWith(prefix, ignoreCase = true)) {
                return raw.substring(prefix.length).trim().ifBlank { null }
            }
        }
        return null
    }

    companion object {
        private val pinterestTitlePrefixes = listOf(
            "Взгляните на этот пин от ",
            "Смотрите этот пин от ",
            "Посмотрите этот пин от ",
            "Check out this Pin from ",
            "Look at this Pin from ",
            "See this Pin from "
        )
    }
}