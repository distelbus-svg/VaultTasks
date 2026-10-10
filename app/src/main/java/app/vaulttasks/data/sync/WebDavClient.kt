package app.vaulttasks.data.sync

import app.vaulttasks.domain.sync.PropfindParser
import app.vaulttasks.domain.sync.PutResult
import app.vaulttasks.domain.sync.RemoteData
import app.vaulttasks.domain.sync.RemoteStat
import app.vaulttasks.domain.sync.RemoteStore
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

data class WebDavConfig(val url: String, val user: String, val password: String, val baseFolder: String)

/**
 * Minimal WebDAV client (spec §9): PROPFIND depth 0, GET, conditional PUT, MKCOL. Redirects are not followed: OkHttp
 * would turn a redirected PUT into a GET, so a redirect is reported as an error and the user fixes the URL.
 */
class WebDavClient(private val config: WebDavConfig) : RemoteStore {
    private val http = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()
    private val auth = Credentials.basic(config.user, config.password, Charsets.UTF_8)
    private val root: HttpUrl = config.url.trim().toHttpUrl()
    private val baseSegments = config.baseFolder.split('/').filter { it.isNotBlank() }
    private val knownDirs = mutableSetOf<List<String>>()

    /** [collection] adds the trailing slash servers expect on directories (they redirect without it). */
    private fun url(segments: List<String>, collection: Boolean = false): HttpUrl =
        root.newBuilder().apply {
            (baseSegments + segments).forEach { addPathSegment(it) }
            if (collection) addPathSegment("")
        }.build()

    private fun request(segments: List<String>, collection: Boolean = false) =
        Request.Builder().url(url(segments, collection)).header("Authorization", auth)

    private fun Response.check(allowed: Set<Int> = emptySet()): Response {
        if (isSuccessful || code in allowed) return this
        close()
        throw IOException(
            when {
                code == 401 || code == 403 -> "Not authorized (HTTP $code), check user and password"
                isRedirect -> "Server redirected (HTTP $code), use the final URL"
                else -> "Server answered HTTP $code"
            },
        )
    }

    private fun synthetic(lastModified: String?, size: Long?) = "lm|${lastModified.orEmpty()}|${size ?: -1}"

    private fun propfind(segments: List<String>, collection: Boolean = false): PropfindParser.Entry? {
        val body = """<?xml version="1.0"?><d:propfind xmlns:d="DAV:"><d:prop><d:getetag/><d:getlastmodified/><d:getcontentlength/><d:resourcetype/></d:prop></d:propfind>"""
        val req = request(segments, collection).header("Depth", "0")
            .method("PROPFIND", body.toRequestBody("application/xml".toMediaType())).build()
        http.newCall(req).execute().use { r ->
            if (r.code == 404) return null
            r.check()
            return PropfindParser.parse(r.body.string()).firstOrNull()
        }
    }

    private fun millis(httpDate: String?): Long? = httpDate?.let {
        runCatching { ZonedDateTime.parse(it, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }.getOrNull()
    }

    private fun tokenOf(e: PropfindParser.Entry) = e.etag ?: synthetic(e.lastModified, e.size)

    /** Connection check on the base folder. Returns a human-readable result; throws on failure. */
    fun test(): String {
        val e = propfind(emptyList(), collection = true) ?: return "Connected. The folder doesn't exist yet and will be created on the first push."
        return if (e.isCollection) "Connected." else "The remote folder path points to a file."
    }

    override fun stat(path: String): RemoteStat? =
        propfind(path.split('/'))?.takeIf { !it.isCollection }?.let { RemoteStat(tokenOf(it), millis(it.lastModified)) }

    override fun get(path: String): RemoteData? {
        val segments = path.split('/')
        // Token first, content second: if the file changes in between, the recorded token is older than the
        // content, which only makes the next check report "changed on server" (the safe direction).
        val entry = propfind(segments)?.takeIf { !it.isCollection } ?: return null
        http.newCall(request(segments).get().build()).execute().use { r ->
            if (r.code == 404) return null
            r.check()
            return RemoteData(r.body.bytes(), tokenOf(entry), millis(entry.lastModified))
        }
    }

    override fun put(path: String, bytes: ByteArray, expectedToken: String?): PutResult {
        val segments = path.split('/')
        val builder = request(segments).put(bytes.toRequestBody("text/markdown".toMediaType()))
        when {
            expectedToken == null -> builder.header("If-None-Match", "*")
            expectedToken.startsWith("\"") -> builder.header("If-Match", expectedToken) // strong ETag
            else -> { // no usable ETag on this server: check by hand (small race, unavoidable)
                if (stat(path)?.token != expectedToken) return PutResult.PreconditionFailed
            }
        }
        ensureDirs(segments.dropLast(1))
        http.newCall(builder.build()).execute().use { r ->
            if (r.code == 412) return PutResult.PreconditionFailed
            r.check()
            val etag = r.header("ETag")
            if (etag != null && etag.startsWith("\"")) return PutResult.Ok(etag)
        }
        val now = stat(path) ?: return PutResult.Failed("Uploaded, but the file is not visible on the server")
        return PutResult.Ok(now.token)
    }

    /** MKCOL the base folder and every directory below it that the file needs (405 = already exists). */
    private fun ensureDirs(dirs: List<String>) {
        val all = baseSegments + dirs
        for (i in all.indices) mkcol(all.take(i + 1))
    }

    private fun mkcol(segments: List<String>) {
        if (segments in knownDirs) return
        val target = root.newBuilder().apply { segments.forEach { addPathSegment(it) }; addPathSegment("") }.build()
        val req = Request.Builder().url(target).header("Authorization", auth).method("MKCOL", null).build()
        http.newCall(req).execute().use { it.check(allowed = setOf(405)) }
        knownDirs += segments
    }
}
