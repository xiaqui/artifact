package jp.xiaqui.artifact

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.DigestInputStream
import java.security.MessageDigest

internal data class ReleaseItem(val id: Long, val tag: String)
internal data class ReleaseApk(val id: Long, val name: String, val size: Long, val digest: String?, val updated: String = "")

internal class GitHubReleases(private val auth: ReleaseAuth,
    private val connect: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection }) {

    private fun <T> read(link: ReleaseLink, endpoint: String, binary: Boolean, consume: (InputStream) -> T): T {
        var url = URL(endpoint)
        check(url.protocol == "https" && url.host == "api.github.com" && url.port == -1 && url.userInfo == null)
        var hop = 0
        var common: String? = null
        while (hop <= 5) {
            if (Thread.currentThread().isInterrupted) throw InterruptedException()
            val connection = connect(url)
            try {
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 10000
                connection.readTimeout = 15000
                connection.useCaches = false
                val token = if (hop == 0) auth.tokenFor(link.repository) ?: common else null
                if (hop == 0) {
                    token?.let { connection.setRequestProperty("Authorization", "Bearer $it") }
                    connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
                }
                connection.setRequestProperty("Accept", if (binary) "application/octet-stream" else "application/vnd.github+json")
                when (val status = connection.responseCode) {
                    200 -> return connection.inputStream.use(consume)
                    301, 302, 303, 307, 308 -> {
                        if (!binary) throw ImportFailure("repo移転の同一性を追跡できません。新しい名前で別登録してください。旧登録・履歴は保持しています。")
                        val location = connection.getHeaderField("Location") ?: throw ImportFailure("転送先がありません。")
                        hop++
                        url = URL(url, location)
                        rejectCredentialUrl(url.toString(), signedRedirect = true)
                        if (url.protocol != "https" || url.userInfo != null || url.host.isEmpty() || url.port !in listOf(-1, 443))
                            throw ImportFailure("安全なHTTPS転送先を取得できません。")
                    }
                    else -> {
                        if (hop == 0 && token == null && status in listOf(401,403,404,429)) {
                            common = auth.commonToken()
                            if (common != null) continue
                        }
                        val path = URL(endpoint).path.removePrefix("/repos/${link.repository}/releases")
                        val stage = when {
                            hop > 0 -> "APK転送先"
                            binary -> "Release Asset本体"
                            path.isEmpty() -> "Release一覧"
                            path.endsWith("/assets") -> "Asset一覧"
                            else -> "Release指定"
                        }
                        val mode = when { token == null -> "匿名"; common == null -> "認証付き・個別PAT"; else -> "認証付き・共通PAT" }
                        val reason = when {
                            status == 429 || status == 403 && (connection.getHeaderField("X-RateLimit-Remaining") == "0" ||
                                connection.getHeaderField("Retry-After") != null) -> "API利用制限。時間をおいて再試行してください。"
                            hop > 0 -> "転送先で取得できませんでした。リンクを再取得してください。"
                            status == 401 -> "認証が拒否されました。PATの有効期限を確認して再認証してください。"
                            status == 403 -> "Contents読み取り権限と組織の承認を確認してください。"
                            status == 404 -> "404だけでは不存在と閲覧権限不足を区別できません。repo・公開範囲・PAT対象repo・Release/Assetを確認してください。"
                            else -> "通信先と取得対象を確認してください。"
                        }
                        throw ImportFailure("HTTP $status（$stage・$mode）。$reason")
                    }
                }
            } finally { connection.disconnect() }
        }
        throw ImportFailure("転送回数の上限を超えました。")
    }

    private fun json(link: ReleaseLink, endpoint: String) = read(link, endpoint, false) {
        val buffer = ByteArrayOutputStream()
        transfer(it, buffer, 4L * 1024 * 1024)
        buffer.toString("UTF-8")
    }

    private fun releaseRows(link: ReleaseLink, visit: (JSONObject) -> Unit) {
        var page = 1
        while (true) {
            val rows = JSONArray(json(link, "${link.apiBase}?per_page=30&page=$page"))
            for (i in 0 until rows.length()) {
                val row = rows.getJSONObject(i)
                if (!row.optBoolean("draft")) visit(row)
            }
            if (rows.length() < 30) return
            page++
            if (page > 100) {
                if (JSONArray(json(link, "${link.apiBase}?per_page=30&page=$page")).length() == 0) return
                throw ImportFailure("Release履歴が上限（3,000件）を超えています。")
            }
        }
    }

    fun confirmRelease(link: ReleaseLink, id: Long) {
        val row = JSONObject(json(link, "${link.apiBase}/$id"))
        if (row.getLong("id") != id) throw ImportFailure("旧Release履歴との同一性を確認できません。移行を保留しました。")
    }

    fun releases(link: ReleaseLink): List<ReleaseItem> = buildList {
        releaseRows(link) { add(ReleaseItem(it.getLong("id"), it.getString("tag_name"))) }
    }

    fun history(link: ReleaseLink): List<StoreRelease> = buildList {
        var retainedBytes = 0L
        releaseRows(link) { row ->
            val id = row.getLong("id")
            val embedded = row.optJSONArray("assets")
            val apks = if (embedded == null || embedded.length() >= 30) assets(link, id)
                else (0 until embedded.length()).mapNotNull { apk(embedded.getJSONObject(it), null) }
            retainedBytes += 128L + 4L * row.getString("tag_name").length +
                apks.sumOf { 192L + 4L * (it.name.length + it.digest.orEmpty().length + it.updated.length) }
            if (retainedBytes > 512L * 1024) throw ImportFailure("履歴の端末保存上限を超えています。以前の取得情報を保持します。")
            if (apks.isNotEmpty()) add(StoreRelease(id, row.getString("tag_name"),
                row.getString("published_at"), row.optBoolean("prerelease"), apks,
                row.optString("target_commitish").takeIf { it.matches(Regex("[0-9a-fA-F]{40}")) }.orEmpty()))
        }
    }.distinctBy { it.id }.sortedByDescending { it.published }

    private fun apk(row: JSONObject, wanted: String?): ReleaseApk? {
        val name = row.getString("name")
        if (!name.endsWith(".apk", true) || wanted != null && name != wanted) return null
        val digest = row.optString("digest").takeIf { it.startsWith("sha256:") }?.removePrefix("sha256:")
        return ReleaseApk(row.getLong("id"), name, row.getLong("size"), digest, row.optString("updated_at"))
    }

    fun assets(link: ReleaseLink, releaseId: Long? = null): List<ReleaseApk> {
        val id = releaseId ?: JSONObject(json(link, link.metadata)).let {
            if (it.optBoolean("draft")) throw ImportFailure("下書きReleaseは対象外です。")
            it.getLong("id")
        }
        val result = mutableListOf<ReleaseApk>()
        for (page in 1..10) {
            val rows = JSONArray(json(link, "${link.apiBase}/$id/assets?per_page=100&page=$page"))
            for (index in 0 until rows.length()) {
                apk(rows.getJSONObject(index), link.name)?.let(result::add)
            }
            if (rows.length() < 100 || (link.name != null && result.isNotEmpty())) return result
        }
        val overflow = JSONArray(json(link, "${link.apiBase}/$id/assets?per_page=100&page=11"))
        if (overflow.length() == 0) return result
        throw ImportFailure("このReleaseのAsset数が上限（1,000件）を超えています。")
    }

    fun download(link: ReleaseLink, apk: ReleaseApk, target: File, allowed: () -> Unit = {}) {
        if (apk.size !in 1..MAX_INPUT) throw ImportFailure("APKがサイズ上限外です。")
        val sha = MessageDigest.getInstance("SHA-256")
        allowed()
        read(link, "${link.apiBase}/assets/${apk.id}", true) { input ->
            val stream = object : java.io.FilterInputStream(input) {
                override fun read(b: ByteArray, off: Int, len: Int): Int { allowed(); return super.read(b,off,len) }
                override fun read(): Int { allowed(); return super.read() }
            }
            val count = target.outputStream().use { transfer(DigestInputStream(stream, sha), it, apk.size) }
            if (count != apk.size) throw ImportFailure("APKの取得が途中で終了しました。")
        }
        val digest = sha.digest().joinToString("") { "%02x".format(it) }
        if (apk.digest != null && !digest.equals(apk.digest, true)) throw ImportFailure("APKのSHA-256がReleaseと一致しません。")
    }
}
