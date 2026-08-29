package com.ecs.core.export

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 把一个导出目录打成一个 zip，好走系统分享送出去。
 *
 * 导出包躺在应用私有目录里，卸载就一起没了，Android 11 之后连文件管理器都不好碰。
 * 一份带不走的备份不叫备份，所以必须有这一步。
 *
 * 纯 java.util.zip，不碰 Android API，可以在 logic-tests 里用临时目录真跑一遍。
 */
object Zip {

    /**
     * 把 [src] 目录下的内容（含子目录）写进 [dest]。
     * 条目名是相对 [src] 的路径——写绝对路径的话，解压出来会带上一长串
     * /storage/emulated/0/Android/data/… 的空目录。
     */
    fun zipDir(src: File, dest: File): File {
        dest.parentFile?.mkdirs()
        ZipOutputStream(dest.outputStream().buffered()).use { out ->
            src.walkTopDown()
                .filter { it.isFile }
                .forEach { file ->
                    out.putNextEntry(ZipEntry(file.relativeTo(src).invariantSeparatorsPath))
                    file.inputStream().buffered().use { it.copyTo(out) }
                    out.closeEntry()
                }
        }
        return dest
    }
}
