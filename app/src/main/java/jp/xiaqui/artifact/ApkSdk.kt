package jp.xiaqui.artifact

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipFile

internal data class ApkSdk(val min: Int = 0, val target: Int = 0, val max: Int = 0) {
    fun supports(api: Int) = min > 0 && api >= min && (max == 0 || api <= max)
    val label get() = if (min <= 0) "Android 要確認" else "Android ${androidVersion(min)}" +
        if (max > 0) "〜${androidVersion(max)}" else "+"
    val detail get() = "minSdk=${min.takeIf { it > 0 } ?: "未確認"} / targetSdk=${target.takeIf { it > 0 } ?: "未確認"} / maxSdk=${max.takeIf { it > 0 } ?: "指定なし・上限不明"}\n出典：APK AndroidManifest.xml（旧キャッシュは未確認）。targetSdkは対応上限ではありません。"
}
internal fun androidVersion(api: Int): String = when (api) {
    21 -> "5.0"; 22 -> "5.1"; 23 -> "6"; 24 -> "7.0"; 25 -> "7.1"; 26 -> "8.0"; 27 -> "8.1"
    28 -> "9"; 29 -> "10"; 30 -> "11"; 31,32 -> "12"; 33 -> "13"; 34 -> "14"; 35 -> "15"
    else -> "API $api"
}

/** Bounded AXML reader for uses-sdk. No hidden framework APIs; usable on API 23. */
internal object ApkSdkReader {
    fun read(file: File): ApkSdk = runCatching {
        ZipFile(file).use { zip ->
            val entry = zip.getEntry("AndroidManifest.xml") ?: return ApkSdk()
            val out = java.io.ByteArrayOutputStream()
            zip.getInputStream(entry).use { transfer(it,out,1024*1024) }
            parse(out.toByteArray())
        }
    }.getOrDefault(ApkSdk())
    fun parse(bytes: ByteArray): ApkSdk {
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        fun u16(at: Int) = b.getShort(at).toInt() and 65535
        fun i32(at: Int) = b.getInt(at)
        require(bytes.size >= 8 && u16(0) == 3 && i32(4) == bytes.size)
        var strings = emptyList<String>()
        var pos = u16(2)
        var result = ApkSdk(1,1,0)
        var sawManifest = false
        while (pos < bytes.size) {
            require(pos <= bytes.size - 8)
            val type=u16(pos); val header=u16(pos+2); val size=i32(pos+4)
            require(header >= 8 && size >= header && size <= bytes.size-pos)
            if (type == 1) {
                require(header >= 28)
                val count=i32(pos+8); val utf8=i32(pos+16) and 256 != 0; val start=i32(pos+20)
                require(count in 0..16000 && header.toLong()+count*4L <= size && start in header..size)
                strings=(0 until count).map { index ->
                    var cursor=pos+start+i32(pos+header+index*4)
                    val end=pos+size
                    fun length(): Int {
                        if (utf8) {
                            require(cursor < end); val first=bytes[cursor++].toInt() and 255
                            return if (first and 128 == 0) first else {
                                require(cursor < end); ((first and 127) shl 8) or (bytes[cursor++].toInt() and 255)
                            }
                        }
                        require(cursor+2 <= end); val first=u16(cursor); cursor+=2
                        return if (first and 32768 == 0) first else {
                            require(cursor+2 <= end); val n=((first and 32767) shl 16) or u16(cursor); cursor+=2; n
                        }
                    }
                    require(cursor in (pos+start) until end)
                    if (utf8) length()
                    val length=length()
                    val n=length.toLong()*(if(utf8)1 else 2)
                    require(n >= 0 && cursor.toLong()+n+(if(utf8)1 else 2) <= end)
                    String(bytes,cursor,n.toInt(),if(utf8) Charsets.UTF_8 else Charsets.UTF_16LE)
                }
            } else if (type == 0x102) {
                require(header >= 16 && size >= header+20)
                val ext=pos+header
                val name=strings[i32(ext+4)]
                if(name == "manifest") sawManifest=true
                if(name == "uses-sdk") {
                    val offset=u16(ext+8); val stride=u16(ext+10); val count=u16(ext+12)
                    require(offset >= 20 && stride >= 20 && offset.toLong()+stride.toLong()*count <= size-header)
                    var min=1; var target=0; var max=0
                    for(i in 0 until count) {
                        val at=ext+offset+i*stride
                        val ns=i32(at)
                        if(ns < 0 || strings[ns] != "http://schemas.android.com/apk/res/android") continue
                        val attr=strings[i32(at+4)]
                        if(attr !in listOf("minSdkVersion","targetSdkVersion","maxSdkVersion")) continue
                        val dataType=bytes[at+15].toInt() and 255
                        require(dataType == 0x10 || dataType == 0x11) // Codenames/resources are unknown, never guessed.
                        val value=i32(at+16); require(value > 0)
                        when(attr) { "minSdkVersion" -> min=value; "targetSdkVersion" -> target=value; else -> max=value }
                    }
                    require(max == 0 || max >= min)
                    result=ApkSdk(min,if(target==0)min else target,max)
                }
            }
            pos+=size
        }
        require(sawManifest)
        return result
    }
}
