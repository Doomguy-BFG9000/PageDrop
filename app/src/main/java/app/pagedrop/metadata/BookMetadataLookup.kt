package app.pagedrop.metadata

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

data class BookMetadataCandidate(
    val title: String,
    val author: String,
    val isbn: String? = null,
    val coverUrl: String? = null,
    val publisher: String? = null,
)

/** Lightweight, user-initiated catalog lookup. Results are always confirmed in the UI. */
object BookMetadataLookup {
    private val client = OkHttpClient()
    private val json = Json { ignoreUnknownKeys = true }
    private val isbnPattern = Regex("(?:97[89])?[0-9Xx-]{10,17}")

    suspend fun search(title: String, author: String): Result<List<BookMetadataCandidate>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val isbn = isbnPattern.find(title)?.value?.filter(Char::isDigit)
                val query = if (!isbn.isNullOrBlank()) "isbn:$isbn" else {
                    listOf(title.trim(), author.trim()).filter { it.isNotBlank() }.joinToString(" ")
                }
                require(query.isNotBlank()) { "Enter a title, author, or ISBN to search" }

                googleBooks(query)
            }
        }

    private fun googleBooks(query: String): List<BookMetadataCandidate> {
        val url = "https://www.googleapis.com/books/v1/volumes".toHttpUrl().newBuilder()
            .addQueryParameter("q", query)
            .addQueryParameter("maxResults", "8")
            .addQueryParameter("printType", "books")
            .build()
        val root = getJson(url.toString()) ?: return emptyList()
        return root["items"]?.jsonArray.orEmpty().mapNotNull { element ->
            val info = element.jsonObject["volumeInfo"]?.jsonObject ?: return@mapNotNull null
            val title = info["title"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val authors = info["authors"]?.jsonArray
                ?.mapNotNull { it.jsonPrimitive.contentOrNull }
                ?.joinToString(" & ")
                .orEmpty()
            val isbn = info["industryIdentifiers"]?.jsonArray
                ?.firstNotNullOfOrNull { it.jsonObject["identifier"]?.jsonPrimitive?.contentOrNull }
            val cover = info["imageLinks"]?.jsonObject?.let { images ->
                images["extraLarge"]?.jsonPrimitive?.contentOrNull
                    ?: images["large"]?.jsonPrimitive?.contentOrNull
                    ?: images["thumbnail"]?.jsonPrimitive?.contentOrNull
            }?.replace("http://", "https://")
            BookMetadataCandidate(
                title = title,
                author = authors.ifBlank { "Unknown Author" },
                isbn = isbn,
                coverUrl = cover,
                publisher = info["publisher"]?.jsonPrimitive?.contentOrNull,
            )
        }
    }

    fun downloadCover(coverUrl: String, destination: java.io.File): Boolean = runCatching {
        client.newCall(Request.Builder().url(coverUrl).build()).execute().use { response ->
            if (!response.isSuccessful) return false
            val body = response.body ?: return false
            destination.outputStream().use { output -> body.byteStream().copyTo(output) }
            destination.length() > 0
        }
    }.getOrDefault(false)

    private fun getJson(url: String) = runCatching {
        client.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) return null
            response.body?.string()?.let(json::parseToJsonElement)?.jsonObject
        }
    }.getOrNull()
}
