package io.legado.app.help.config

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** Standalone albums do not go through the 64 MiB theme-package importer. */
internal object NgCoverAlbumArchive {
    const val MAX_TOTAL = 512L * 1024 * 1024
    const val MAX_IMAGE = 32L * 1024 * 1024
    const val MAX_ENTRIES = 4096
    private const val FORMAT = "reading-ng-cover-album"

    internal fun copyBounded(input: InputStream, output: OutputStream, limit: Long): Long {
        var total = 0L
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            require(total <= limit) { "图片或图集超过大小限制" }
            output.write(buffer, 0, count)
        }
        return total
    }

    internal fun safeEntry(name: String): Boolean =
        name.isNotBlank() && !name.startsWith('/') && '\\' !in name && ':' !in name &&
            name.split('/').none { it == ".." || it == "." || it.isEmpty() }

    fun addImages(context: Context, id: String, dark: Boolean, uris: List<Uri>, optimize: Boolean) {
        require(uris.size in 1..MAX_ENTRIES) { "一次最多添加 $MAX_ENTRIES 张图片" }
        val staging = File(context.cacheDir, "album-images-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            var total = 0L
            val files = uris.mapIndexed { index, uri ->
                val original = File(staging, "$index.img")
                requireNotNull(context.contentResolver.openInputStream(uri)) { "无法读取图片" }.use { input ->
                    original.outputStream().use { total += copyBounded(input, it, minOf(MAX_IMAGE, MAX_TOTAL - total)) }
                }
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(original.path, bounds)
                require(bounds.outWidth in 1..30000 && bounds.outHeight in 1..30000) { "图片无法解码或尺寸过大" }
                if (!optimize) original else {
                    val options = BitmapFactory.Options().apply {
                        inSampleSize = 1
                        while (maxOf(bounds.outWidth, bounds.outHeight) / inSampleSize > 1600) inSampleSize *= 2
                    }
                    val bitmap = requireNotNull(BitmapFactory.decodeFile(original.path, options)) { "图片解码失败" }
                    try {
                        val alpha = bitmap.hasAlpha()
                        val converted = File(staging, "$index.${if (alpha) "png" else "jpg"}")
                        converted.outputStream().use {
                            check(bitmap.compress(if (alpha) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 88, it)) { "图片压缩失败" }
                        }
                        converted
                    } finally { bitmap.recycle() }
                }
            }
            NgCoverAlbumStore.append(context, id, dark, files)
        } finally { staging.deleteRecursively() }
    }

    fun importAlbum(context: Context, uri: Uri): String {
        val staging = File(context.cacheDir, "album-import-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val archive = File(staging, "input.zip")
            requireNotNull(context.contentResolver.openInputStream(uri)).use { input ->
                archive.outputStream().use { copyBounded(input, it, MAX_TOTAL) }
            }
            val extracted = File(staging, "content").apply { mkdirs() }
            val manifest = ZipFile(archive).use { zip ->
                require(zip.size() in 1..MAX_ENTRIES) { "图集文件数量过多" }
                val names = hashSetOf<String>()
                var total = 0L
                zip.entries().asSequence().forEach { entry ->
                    val name = entry.name.removeSuffix("/")
                    require(safeEntry(name) && names.add(name)) { "图集路径无效或重复" }
                    require(name == "manifest.json" || name == "assets" || name.startsWith("assets/")) { "图集包含未知文件" }
                    if (!entry.isDirectory) {
                        val limit = if (name == "manifest.json") 1024L * 1024 else MAX_IMAGE
                        val file = File(extracted, name)
                        file.parentFile?.mkdirs()
                        zip.getInputStream(entry).use { input ->
                            file.outputStream().use { total += copyBounded(input, it, minOf(limit, MAX_TOTAL - total)) }
                        }
                    }
                }
                JSONObject(File(extracted, "manifest.json").readText())
            }
            require(manifest.getString("format") == FORMAT && manifest.getInt("version") == 1) { "请选择独立封面图集 ZIP；主题包请在主题管理中导入" }
            val name = manifest.getString("name").trim()
            require(name.length in 1..120) { "图集名称无效" }
            fun paths(key: String): List<String> {
                val array = manifest.getJSONArray(key)
                require(array.length() <= MAX_ENTRIES) { "图片数量过多" }
                return List(array.length()) { array.getString(it) }.onEach {
                    require(safeEntry(it) && it.startsWith("assets/")) { "图片路径无效" }
                }
            }
            val light = paths("lightImages").distinct()
            val dark = paths("darkImages").distinct()
            require(light.isNotEmpty() || dark.isNotEmpty()) { "图集没有图片" }
            require((light + dark).sumOf { File(extracted, it).length() } <= MAX_TOTAL) { "图集安装内容不能超过 512 MiB" }
            return NgCoverAlbumStore.importFromPackage(context, extracted, "", listOf(
                NgCoverAlbumImport("album", name, light, dark),
            )).single().id
        } finally { staging.deleteRecursively() }
    }

    fun exportAlbum(context: Context, id: String, uri: Uri) {
        val album = requireNotNull(NgCoverAlbumStore.current(context).albums.find { it.id == id }) { "图集不存在" }
        val paths = (album.lightImages + album.darkImages).distinct()
        require(paths.isNotEmpty()) { "请先添加图片" }
        require(paths.size < MAX_ENTRIES) { "图集图片数量超过 $MAX_ENTRIES" }
        val assets = paths.mapIndexed { index, path ->
            val file = NgCoverAlbumStore.packageImage(context, id, path)
            require(file.length() <= MAX_IMAGE) { "单张图片不能超过 32 MiB" }
            path to ("assets/$index.${file.extension}" to file)
        }.toMap()
        val manifest = JSONObject().put("format", FORMAT).put("version", 1).put("name", album.name)
            .put("lightImages", JSONArray(album.lightImages.map { assets.getValue(it).first }))
            .put("darkImages", JSONArray(album.darkImages.map { assets.getValue(it).first }))
            .toString(2).toByteArray(Charsets.UTF_8)
        require(manifest.size <= 1024 * 1024 && assets.values.sumOf { it.second.length() } + manifest.size <= MAX_TOTAL) { "图集解压内容不能超过 512 MiB" }
        val file = File(context.cacheDir, "album-export-${UUID.randomUUID()}.zip")
        try {
            ZipOutputStream(file.outputStream().buffered()).use { zip ->
                zip.putNextEntry(ZipEntry("manifest.json")); zip.write(manifest); zip.closeEntry()
                assets.values.forEach { (name, image) ->
                    zip.putNextEntry(ZipEntry(name))
                    image.inputStream().use { copyBounded(it, zip, MAX_IMAGE) }
                    zip.closeEntry()
                }
            }
            require(file.length() <= MAX_TOTAL) { "图集 ZIP 不能超过 512 MiB" }
            requireNotNull(context.contentResolver.openOutputStream(uri, "wt")).use { output ->
                file.inputStream().use { copyBounded(it, output, MAX_TOTAL) }
            }
        } finally { file.delete() }
    }
}
