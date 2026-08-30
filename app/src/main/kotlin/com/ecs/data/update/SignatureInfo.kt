package com.ecs.data.update

import android.content.Context
import android.content.pm.PackageManager
import java.security.MessageDigest

/**
 * 当前这个安装包是谁签的。
 *
 * Android 拒绝跨签名覆盖安装，而在装不上之前用户看不到任何线索。这里把指纹
 * 摆到设置页上，和发版说明里那一行、以及仓库钉住的 [PINNED] 对得上，就知道
 * 下一版能直接覆盖；对不上（v4.0.1 及更早的 debug 包）就得先导出再卸载。
 */
class SignatureInfo(private val context: Context) {

    /** 已安装包的签名证书 SHA-256，小写十六进制无冒号；读不到返回 null。 */
    fun fingerprint(): String? = runCatching {
        @Suppress("DEPRECATION")
        val info = context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.GET_SIGNING_CERTIFICATES,
        )
        // 轮换过密钥时 signingInfo 会给出历史证书，当前生效的是 apkContentsSigners
        val cert = info.signingInfo?.apkContentsSigners?.firstOrNull() ?: return@runCatching null
        MessageDigest.getInstance("SHA-256")
            .digest(cert.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }.getOrNull()

    /** 是不是 v4.1.0 起那张固定证书。读不到指纹时按「不是」处理，宁可多提醒一次。 */
    fun matchesPinned(): Boolean = fingerprint() == PINNED

    companion object {
        /**
         * 与 `signing/release-cert-sha256.txt` 同一个值，发版流程逐字比对，不一致就不发。
         * 改这里等于宣布换密钥，所有老用户必须卸载重装。
         */
        const val PINNED = "c659446f910f8b43b0b5843f10fa3e1fd2d5782b07bbeedf452cecb27bc095e3"

        /** 指纹很长，屏幕上显示成首尾各六位。 */
        fun short(fingerprint: String?): String =
            if (fingerprint == null || fingerprint.length < 16) "读不到"
            else "${fingerprint.take(6)}…${fingerprint.takeLast(6)}"
    }
}
