package jp.xiaqui.artifact

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File
import java.security.MessageDigest

class ApkProvider : FileProvider()

internal object Installation {
    @Suppress("DEPRECATION")
    private fun flags() = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES

    @Suppress("DEPRECATION")
    private fun info(record: android.content.pm.PackageInfo, label: String): ApkInfo {
        val signers = if (Build.VERSION.SDK_INT >= 28) record.signingInfo?.apkContentsSigners else record.signatures
        val fingerprints = signers.orEmpty().map { signer ->
            MessageDigest.getInstance("SHA-256").digest(signer.toByteArray()).joinToString(":") { "%02X".format(it) }
        }.sorted().joinToString("\n")
        val code = if (Build.VERSION.SDK_INT >= 28) record.longVersionCode else record.versionCode.toLong()
        return ApkInfo(record.packageName, label, record.versionName.orEmpty(), code, fingerprints)
    }

    @Suppress("DEPRECATION")
    fun describe(context: Context, apk: File): ApkInfo {
        val pm = context.packageManager
        // Android 10 collects archive certificates only when GET_SIGNATURES is also set.
        val archiveFlags = flags() or PackageManager.GET_SIGNATURES
        val record = pm.getPackageArchiveInfo(apk.path, archiveFlags) ?: throw ImportFailure("破損または非対応のAPKです。")
        val application = record.applicationInfo
        application?.sourceDir = apk.path
        application?.publicSourceDir = apk.path
        val label = application?.let { runCatching { it.loadLabel(pm).toString() }.getOrNull() } ?: record.packageName
        return info(record, label).copy(sdk = ApkSdkReader.read(apk), sdkChecked=true)
    }

    @Suppress("DEPRECATION")
    fun installed(context: Context, pkg: String): ApkInfo? {
        val pm = context.packageManager
        return try { info(pm.getPackageInfo(pkg, flags()), pkg) }
        catch (_: PackageManager.NameNotFoundException) { null }
    }

    fun status(context: Context, meta: ApkInfo): String {
        val local = installed(context, meta.pkg)
        val comparison = compareInstalled(meta, local, Build.VERSION.SDK_INT <= 29)
        return if (local == null) comparison else "$comparison · 導入済み ${local.version} (${local.code})"
    }

    fun inspect(context: Context, apk: File): String {
        val meta = describe(context, apk)
        return "${meta.summary}\n${meta.sdk.label}\n${meta.sdk.detail}\n${status(context, meta)}\n署名 SHA-256\n${meta.signer.ifEmpty { "取得不能" }}\n旧版や異なる署名はOSが拒否する場合があります。アンインストールすると対象アプリのデータが消えます。"
    }

    fun permissionSettings(context: Context): Intent? {
        if (Build.VERSION.SDK_INT >= 26 && !context.packageManager.canRequestPackageInstalls())
            return Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
        return null
    }

    @Suppress("DEPRECATION")
    fun installer(context: Context, file: File): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val prompt = Intent(Intent.ACTION_INSTALL_PACKAGE, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val target = context.packageManager.queryIntentActivities(prompt, PackageManager.MATCH_DEFAULT_ONLY)
            .map { it.activityInfo }
            .firstOrNull { it.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM != 0 }
            ?: throw ImportFailure("端末の標準インストーラが見つかりません。")
        return prompt.setClassName(target.packageName, target.name).putExtra(Intent.EXTRA_RETURN_RESULT, true)
    }
}
