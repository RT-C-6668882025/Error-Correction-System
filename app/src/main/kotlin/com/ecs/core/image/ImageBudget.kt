package com.ecs.core.image

/**
 * 上传前的图片预算。
 *
 * 一张随手拍的卷子照片 4–8 MB，base64 再涨三分之一，一次最多五张——
 * 请求体几十兆，任何一条移动网络都发不完。压到长边 1600、质量 82 之后
 * 单张 200–400 KB，而印刷体题干在这个分辨率上没有可感知的识别损失。
 *
 * 这一层是纯计算，不碰 Android API：解码策略要能单独测。
 */
object ImageBudget {

    /** 长边上限。印刷体 OCR 够用，再高只是白传字节。 */
    const val MAX_EDGE = 1600

    /** JPEG 质量。再低会开始伤到小字号的字母边缘。 */
    const val QUALITY = 82

    /**
     * BitmapFactory 的 inSampleSize 只在 2 的幂上生效，
     * 取「降采样后仍不低于目标分辨率」的最大那一档——先粗降到内存里放得下，
     * 剩下的零头交给 [targetSize] 精缩。
     */
    fun sampleSize(width: Int, height: Int, maxEdge: Int = MAX_EDGE): Int {
        if (width <= 0 || height <= 0 || maxEdge <= 0) return 1
        var sample = 1
        while (maxOf(width, height) / (sample * 2) >= maxEdge) sample *= 2
        return sample
    }

    /**
     * 等比缩到长边不超过 [maxEdge]。本来就小的图原样返回——
     * 放大不会让模型多认出一个字母，只会让请求变大。
     */
    fun targetSize(width: Int, height: Int, maxEdge: Int = MAX_EDGE): Pair<Int, Int> {
        if (width <= 0 || height <= 0) return width to height
        val longest = maxOf(width, height)
        if (longest <= maxEdge) return width to height
        val ratio = maxEdge.toDouble() / longest
        return maxOf(1, Math.round(width * ratio).toInt()) to
            maxOf(1, Math.round(height * ratio).toInt())
    }
}
