package io.legado.app.help.config

import android.content.Context
import android.graphics.BitmapFactory
import androidx.annotation.Keep
import com.google.gson.annotations.SerializedName
import io.legado.app.utils.GSON
import io.legado.app.utils.defaultSharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

@Keep
internal data class NgCoverAlbum(
    @SerializedName("id") val id: String,
    @SerializedName("name") val name: String,
    @SerializedName("sourceThemeName") val sourceThemeName: String,
    @SerializedName("lightImages") val lightImages: List<String>,
    @SerializedName("darkImages") val darkImages: List<String>,
)

@Keep
internal data class NgCoverAlbumImport(
    @SerializedName("ref") val ref: String,
    @SerializedName("name") val name: String,
    @SerializedName("lightImages") val lightImages: List<String>,
    @SerializedName("darkImages") val darkImages: List<String>,
)

internal data class NgImportedCoverAlbum(
    val ref: String,
    val id: String,
)

internal data class NgCoverAlbumLibraryState(
    val albums: List<NgCoverAlbum> = emptyList(),
    val selectedAlbumId: String? = null,
)

@Keep
private data class StoredNgCoverAlbumLibrary(
    @SerializedName("schemaVersion") val schemaVersion: Int = SCHEMA_VERSION,
    @SerializedName("albums") val albums: List<NgCoverAlbum> = emptyList(),
    @SerializedName("selectedAlbumId") val selectedAlbumId: String? = null,
) {
    companion object {
        const val SCHEMA_VERSION = 1
    }
}

/**
 * 独立于主题记录的封面图集仓库。
 *
 * 图集资产独立于主题安装目录；主题显式携带封面 Profile 时，可以在应用主题时选择对应图集。
 * 用户仍可在封面设置中独立切换或删除；主题删除只清理由该主题拥有且没有其它主题引用的图集。
 */
internal object NgCoverAlbumStore {

    private const val STATE_KEY = "ngCoverAlbumLibrary.v1"
    private const val ROOT_DIR = "ng_cover_albums"
    private val lock = Any()
    private var initialized = false
    private val mutableState = MutableStateFlow(NgCoverAlbumLibraryState())

    fun observe(context: Context): StateFlow<NgCoverAlbumLibraryState> {
        ensureInitialized(context)
        return mutableState.asStateFlow()
    }

    fun current(context: Context): NgCoverAlbumLibraryState {
        ensureInitialized(context)
        return mutableState.value
    }

    fun selectedImagePaths(context: Context, isDark: Boolean): List<String> {
        val state = current(context)
        val album = state.albums.firstOrNull { it.id == state.selectedAlbumId } ?: return emptyList()
        return (if (isDark) album.darkImages else album.lightImages)
            .filter { File(it).isFile }
    }

    fun select(context: Context, albumId: String?): Boolean = synchronized(lock) {
        ensureInitialized(context)
        val current = mutableState.value
        if (albumId != null && current.albums.none { it.id == albumId }) {
            return@synchronized false
        }
        val updated = current.copy(selectedAlbumId = albumId)
        persist(context, updated)
        mutableState.value = updated
        true
    }

    internal fun packageImage(context: Context, albumId: String, path: String): File {
        val directory = requireNotNull(albumDirectory(context, albumId)) { "封面图集目录无效" }
        val file = File(path).canonicalFile
        require(file.parentFile == directory && file.isFile) { "封面图集图片已丢失或越界" }
        return file
    }

    fun create(context: Context, name: String): String = synchronized(lock) {
        ensureInitialized(context)
        require(name.trim().length in 1..120) { "图集名称应为 1 至 120 个字符" }
        val id = UUID.randomUUID().toString()
        val directory = File(File(context.filesDir, ROOT_DIR), id)
        check(directory.mkdirs()) { "无法创建图集目录" }
        try {
            val album = NgCoverAlbum(id, uniqueName(name, mutableState.value.albums.map { it.name.lowercase() }.toSet()), "", emptyList(), emptyList())
            publish(context, mutableState.value.copy(albums = mutableState.value.albums + album))
            id
        } catch (error: Throwable) {
            directory.deleteRecursively()
            throw error
        }
    }

    fun rename(context: Context, id: String, name: String) = synchronized(lock) {
        ensureInitialized(context)
        require(name.trim().length in 1..120) { "图集名称应为 1 至 120 个字符" }
        val album = requireNotNull(mutableState.value.albums.find { it.id == id }) { "图集已不存在" }
        val names = mutableState.value.albums.filterNot { it.id == id }.map { it.name.lowercase() }.toSet()
        updateAlbum(context, album.copy(name = uniqueName(name, names)))
    }

    /** Files are staged by the editor; publish only after every copy succeeds. */
    fun append(context: Context, id: String, dark: Boolean, files: List<File>) = synchronized(lock) {
        ensureInitialized(context)
        val album = requireNotNull(mutableState.value.albums.find { it.id == id }) { "图集已不存在" }
        val directory = requireNotNull(albumDirectory(context, id)) { "图集目录不存在" }
        val copied = mutableListOf<File>()
        try {
            files.forEach { source ->
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(source.path, bounds)
                require(bounds.outWidth > 0 && bounds.outHeight > 0) { "图片无法解码" }
                val extension = source.extension.takeIf { it.matches(Regex("[a-zA-Z0-9]{1,8}")) } ?: "img"
                val target = File(directory, "${UUID.randomUUID()}.$extension")
                copied += target
                source.copyTo(target)
            }
            val paths = copied.map { it.absolutePath }
            updateAlbum(context, if (dark) album.copy(darkImages = album.darkImages + paths)
                else album.copy(lightImages = album.lightImages + paths))
        } catch (error: Throwable) {
            copied.forEach { it.delete() }
            throw error
        }
    }

    fun removeImage(context: Context, id: String, dark: Boolean, path: String) = synchronized(lock) {
        ensureInitialized(context)
        val album = requireNotNull(mutableState.value.albums.find { it.id == id }) { "图集已不存在" }
        val updated = if (dark) album.copy(darkImages = album.darkImages - path)
            else album.copy(lightImages = album.lightImages - path)
        updateAlbum(context, updated)
        if (path !in updated.lightImages && path !in updated.darkImages) {
            runCatching { packageImage(context, id, path).delete() }
        }
    }

    fun copyAppearance(context: Context, id: String, fromDark: Boolean) = synchronized(lock) {
        ensureInitialized(context)
        val album = requireNotNull(mutableState.value.albums.find { it.id == id }) { "图集已不存在" }
        val updated = if (fromDark) album.copy(lightImages = album.darkImages)
            else album.copy(darkImages = album.lightImages)
        updateAlbum(context, updated)
        ((album.lightImages + album.darkImages).toSet() - (updated.lightImages + updated.darkImages).toSet()).forEach {
            runCatching { packageImage(context, id, it).delete() }
        }
    }

    private fun updateAlbum(context: Context, album: NgCoverAlbum) {
        publish(context, mutableState.value.copy(albums = mutableState.value.albums.map { if (it.id == album.id) album else it }))
    }

    private fun publish(context: Context, state: NgCoverAlbumLibraryState) {
        persist(context, state)
        mutableState.value = state
    }

    fun importFromPackage(
        context: Context,
        packageRoot: File,
        sourceThemeName: String,
        imports: List<NgCoverAlbumImport>,
    ): List<NgImportedCoverAlbum> = synchronized(lock) {
        ensureInitialized(context)
        if (imports.isEmpty()) return@synchronized emptyList()

        val canonicalPackageRoot = packageRoot.canonicalFile
        val libraryRoot = File(context.filesDir, ROOT_DIR).canonicalFile.apply { mkdirs() }
        val stagingRoot = File(libraryRoot, ".staging-${UUID.randomUUID()}")
        val movedDirectories = mutableListOf<File>()
        try {
            val existingNames = mutableState.value.albums
                .mapTo(hashSetOf()) { it.name.lowercase() }
            val pending = imports.mapNotNull { albumImport ->
                if (albumImport.lightImages.isEmpty() && albumImport.darkImages.isEmpty()) {
                    return@mapNotNull null
                }
                val id = UUID.randomUUID().toString()
                val stagingAlbum = File(stagingRoot, id).apply { mkdirs() }
                val lightNames = copyImages(
                    canonicalPackageRoot,
                    albumImport.lightImages,
                    stagingAlbum,
                    "light",
                )
                val darkNames = copyImages(
                    canonicalPackageRoot,
                    albumImport.darkImages,
                    stagingAlbum,
                    "dark",
                )
                PendingAlbum(
                    ref = albumImport.ref,
                    id = id,
                    name = uniqueName(
                        albumImport.name.ifBlank { "$sourceThemeName 封面" },
                        existingNames,
                    ),
                    lightFileNames = lightNames,
                    darkFileNames = darkNames,
                ).also { existingNames += it.name.lowercase() }
            }

            val imported = pending.map { album ->
                val source = File(stagingRoot, album.id)
                val target = File(libraryRoot, album.id)
                moveIntoPlace(source, target)
                movedDirectories += target
                NgCoverAlbum(
                    id = album.id,
                    name = album.name,
                    sourceThemeName = sourceThemeName,
                    lightImages = album.lightFileNames.map { File(target, it).absolutePath },
                    darkImages = album.darkFileNames.map { File(target, it).absolutePath },
                )
            }
            if (imported.isEmpty()) return@synchronized emptyList()
            val updated = mutableState.value.copy(albums = mutableState.value.albums + imported)
            persist(context, updated)
            mutableState.value = updated
            imported.mapIndexed { index, album ->
                NgImportedCoverAlbum(ref = pending[index].ref, id = album.id)
            }
        } catch (error: Throwable) {
            movedDirectories.forEach(File::deleteRecursively)
            throw error
        } finally {
            stagingRoot.deleteRecursively()
        }
    }

    fun removeImported(context: Context, albumIds: Collection<String>) = synchronized(lock) {
        ensureInitialized(context)
        removeAlbumsLocked(context, albumIds)
    }

    fun remove(context: Context, albumId: String): Boolean = synchronized(lock) {
        ensureInitialized(context)
        if (mutableState.value.albums.none { it.id == albumId }) return@synchronized false
        removeAlbumsLocked(context, setOf(albumId))
        true
    }

    private fun removeAlbumsLocked(context: Context, albumIds: Collection<String>) {
        if (albumIds.isEmpty()) return
        val removed = mutableState.value.albums.filter { it.id in albumIds }
        if (removed.isEmpty()) return
        val updated = mutableState.value.copy(
            albums = mutableState.value.albums.filterNot { it.id in albumIds },
            selectedAlbumId = mutableState.value.selectedAlbumId.takeUnless { it in albumIds },
        )
        persist(context, updated)
        mutableState.value = updated
        removed.forEach { album ->
            albumDirectory(context, album.id)?.deleteRecursively()
        }
    }

    private fun copyImages(
        packageRoot: File,
        relativePaths: List<String>,
        targetDirectory: File,
        prefix: String,
    ): List<String> = relativePaths.mapIndexed { index, relativePath ->
        val normalizedPath = relativePath.replace('\\', '/').replace('/', File.separatorChar)
        val source = File(packageRoot, normalizedPath).canonicalFile
        require(source.toPath().startsWith(packageRoot.toPath()) && source.isFile) {
            "封面图集资源越界或不存在: $relativePath"
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.absolutePath, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) {
            "封面图集资源无法解码: $relativePath"
        }
        val extension = source.extension.lowercase()
            .takeIf { it.matches(Regex("[a-z0-9]{1,8}")) }
            ?: "img"
        val fileName = "$prefix-${index + 1}.$extension"
        source.copyTo(File(targetDirectory, fileName), overwrite = false)
        fileName
    }

    private fun uniqueName(requested: String, existingNames: Set<String>): String {
        val base = requested.trim().ifEmpty { "封面图集" }
        if (base.lowercase() !in existingNames) return base
        var index = 2
        while ("$base $index".lowercase() in existingNames) index++
        return "$base $index"
    }

    private fun moveIntoPlace(source: File, target: File) {
        require(!target.exists()) { "封面图集目录冲突" }
        runCatching {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
        }.getOrElse {
            Files.move(source.toPath(), target.toPath())
        }
    }

    internal fun reloadAfterRestore(context: Context) {
        synchronized(lock) { initialized = false }
        ensureInitialized(context)
        synchronized(lock) {
            // Backups install shared resources outside album directories. Copy, do not move:
            // another restored book or theme may still reference the same backup image.
            val backupRoot = File(context.filesDir, "ng_backup_resources").canonicalFile
            val libraryRoot = File(context.filesDir, ROOT_DIR).canonicalFile.apply { mkdirs() }
            val copied = mutableListOf<File>()
            try {
                val albums = mutableState.value.albums.map { album ->
                    val directory = File(libraryRoot, album.id).canonicalFile
                    require(directory.parentFile == libraryRoot) { "图集目录无效" }
                    check(directory.isDirectory || directory.mkdirs()) { "无法恢复图集目录" }
                    val mapping = (album.lightImages + album.darkImages).distinct().associateWith { path ->
                        val source = File(path).canonicalFile
                        if (source.isFile && source.toPath().startsWith(backupRoot.toPath())) {
                            val target = File(directory, "${UUID.randomUUID()}.img")
                            copied += target
                            source.copyTo(target)
                            target.absolutePath
                        } else path
                    }
                    album.copy(lightImages = album.lightImages.map { mapping.getValue(it) },
                        darkImages = album.darkImages.map { mapping.getValue(it) })
                }
                if (albums != mutableState.value.albums) publish(context, mutableState.value.copy(albums = albums))
            } catch (error: Throwable) {
                copied.forEach { it.delete() }
                throw error
            }
        }
    }

    private fun ensureInitialized(context: Context) {
        if (initialized) return
        synchronized(lock) {
            if (initialized) return
            val stored = context.defaultSharedPreferences.getString(STATE_KEY, null)?.let { raw ->
                runCatching { GSON.fromJson(raw, StoredNgCoverAlbumLibrary::class.java) }
                    .getOrNull()
                    ?.takeIf { it.schemaVersion == StoredNgCoverAlbumLibrary.SCHEMA_VERSION }
            }
            val albums = stored?.albums.orEmpty().filter { album ->
                album.id.isNotBlank() && album.name.isNotBlank()
            }
            mutableState.value = NgCoverAlbumLibraryState(
                albums = albums,
                selectedAlbumId = stored?.selectedAlbumId?.takeIf { id ->
                    albums.any { it.id == id }
                },
            )
            initialized = true
        }
    }

    private fun persist(context: Context, state: NgCoverAlbumLibraryState) {
        val stored = StoredNgCoverAlbumLibrary(
            albums = state.albums,
            selectedAlbumId = state.selectedAlbumId,
        )
        check(
            context.defaultSharedPreferences.edit()
                .putString(STATE_KEY, GSON.toJson(stored))
                .commit()
        ) { "无法保存封面图集" }
    }

    private fun albumDirectory(context: Context, albumId: String): File? = runCatching {
        val root = File(context.filesDir, ROOT_DIR).canonicalFile
        File(root, albumId).canonicalFile.takeIf { directory ->
            directory.parentFile == root && directory.isDirectory
        }
    }.getOrNull()

    private data class PendingAlbum(
        val ref: String,
        val id: String,
        val name: String,
        val lightFileNames: List<String>,
        val darkFileNames: List<String>,
    )
}
