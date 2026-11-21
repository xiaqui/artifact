package jp.xiaqui.artifact

internal data class StoreRow(val name: String, val versions: String, val status: String, val outcome: String)

internal fun rowFor(r: Registration, absenceKnown: Boolean, api: Int = 0, installed: (String) -> ApkInfo?): StoreRow {
    val assets = r.visible.firstOrNull()?.assets.orEmpty()
    val metadata = assets.mapNotNull { r.inspected[it.identity()] }
    val name = metadata.map { it.label }.distinct().singleOrNull() ?: r.repo
    val selected = metadata.singleOrNull()?.takeIf { assets.size == 1 }
    val pkg = metadata.map { it.pkg }.distinct().singleOrNull()?.takeIf { metadata.size == assets.size }
    var queryFailed = false
    val local = try { pkg?.let(installed) } catch (_: Exception) { queryFailed=true; null }
    // versionCode remains the comparison key and is available on the detail screen.
    fun version(info: ApkInfo) = info.version.ifBlank { "名称不明" }
    val left = local?.let(::version) ?: if (pkg != null && absenceKnown && !queryFailed) "未導入" else "未確認"
    val right = selected?.let(::version) ?: "未確認"
    val comparison = when {
        api > 0 && selected != null && selected.sdk.min > 0 && !selected.sdk.supports(api) -> "端末OS非対応"
        selected != null -> compareInstalled(selected, local, absenceKnown && !queryFailed)
        assets.size > 1 -> "複数APK"
        assets.size == 1 && r.fetched != 0L -> "APK要確認"
        assets.isEmpty() && r.fetched != 0L -> "配布なし"
        else -> "未確認"
    }
    val failed = r.error.isNotEmpty()
    val http = Regex("HTTP [0-9]{3}").find(r.error)?.value
    val status = if (failed) listOfNotNull("確認失敗", http).joinToString(" ") else comparison
    val source = if (failed && r.fetched != 0L) "前回" else if (failed) "参考" else "最新"
    val installedText = if (left == "未導入") left else "導入 $left"
    return StoreRow(name + " · " + (selected?.sdk?.label ?: "Android 要確認"), "$installedText → $source $right", status, if (failed) "失敗" else comparison)
}

internal fun batchSummary(repos: List<String>, check: (String) -> StoreRow?): String {
    var ok = 0
    var failed = 0
    var updates = 0
    var same = 0
    for (repo in repos) {
        if (Thread.currentThread().isInterrupted) throw InterruptedException()
        val status = check(repo)?.outcome ?: continue
        if (status == "失敗") failed++ else {
            ok++
            when (status) { "更新候補" -> updates++; "同一版" -> same++ }
        }
    }
    if (Thread.currentThread().isInterrupted) throw InterruptedException()
    return "成功 $ok（更新 $updates・同一 $same）／失敗 $failed"
}

