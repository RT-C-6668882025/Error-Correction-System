package com.ecs.ui.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import com.ecs.agent.AgentClient
import com.ecs.core.image.ImageBudget
import java.io.ByteArrayOutputStream

/**
 * 把相册里的 Uri 变成可以塞进请求体的 base64。
 *
 * 两步降：先按 [ImageBudget.sampleSize] 降采样解码（原图直接进内存会 OOM），
 * 再按 [ImageBudget.targetSize] 精缩到长边上限，最后统一压成 JPEG。
 * 调用方必须在 IO 线程上跑——大图解码放主线程会 ANR。
 */
object ImageLoader {

    fun load(context: Context, uri: Uri): AgentClient.Image? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val opts = BitmapFactory.Options().apply {
            inSampleSize = ImageBudget.sampleSize(bounds.outWidth, bounds.outHeight)
        }
        val decoded = context.contentResolver.openInputStream(uri)
            ?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return null

        val (w, h) = ImageBudget.targetSize(decoded.width, decoded.height)
        val scaled =
            if (w == decoded.width && h == decoded.height) decoded
            else Bitmap.createScaledBitmap(decoded, w, h, true).also { if (it != decoded) decoded.recycle() }

        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, ImageBudget.QUALITY, out)
        scaled.recycle()

        // 压过之后就不是原格式了，media type 一律按 JPEG 报
        AgentClient.Image(
            mediaType = "image/jpeg",
            base64 = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP),
        )
    }.getOrNull()

    fun loadAll(context: Context, uris: List<Uri>): List<AgentClient.Image> =
        uris.mapNotNull { load(context, it) }
}
