package io.legado.app.help.config

import android.app.Application
import android.net.Uri
import io.legado.app.utils.defaultSharedPreferences
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, manifest = Config.NONE, sdk = [35])
class NgCoverAlbumStoreTest {
    private lateinit var context: Application
    private lateinit var image: File

    @Before fun setup() {
        context = RuntimeEnvironment.getApplication()
        context.defaultSharedPreferences.edit().clear().commit()
        File(context.filesDir, "ng_cover_albums").deleteRecursively()
        NgCoverAlbumStore.reloadAfterRestore(context)
        image = File(context.cacheDir, "sample.png").apply {
            writeBytes(Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR4nGP4z8DwHwAFAAH/iZk9HQAAAABJRU5ErkJggg=="))
        }
    }

    @Test fun sharedDayNightImageSurvivesUntilBothReferencesAreRemoved() {
        val id = NgCoverAlbumStore.create(context, "My album")
        NgCoverAlbumStore.append(context, id, false, listOf(image))
        NgCoverAlbumStore.copyAppearance(context, id, false)
        val path = NgCoverAlbumStore.current(context).albums.single().lightImages.single()
        NgCoverAlbumStore.removeImage(context, id, false, path)
        assertTrue(File(path).isFile)
        assertEquals(listOf(path), NgCoverAlbumStore.current(context).albums.single().darkImages)
        NgCoverAlbumStore.removeImage(context, id, true, path)
        assertFalse(File(path).exists())
    }

    @Test fun invalidBatchDoesNotPublishOrLeaveCopiedImages() {
        val id = NgCoverAlbumStore.create(context, "Album")
        val invalid = File(context.cacheDir, "invalid.jpg").apply { writeText("not an image") }
        assertThrows(IllegalArgumentException::class.java) {
            NgCoverAlbumStore.append(context, id, false, listOf(image, invalid))
        }
        assertTrue(NgCoverAlbumStore.current(context).albums.single().lightImages.isEmpty())
        assertTrue(File(context.filesDir, "ng_cover_albums/$id").listFiles().orEmpty().isEmpty())
    }

    @Test fun independentArchiveRoundTripAndReloadKeepImagesAndSelection() {
        val id = NgCoverAlbumStore.create(context, "我的封面")
        NgCoverAlbumStore.append(context, id, false, listOf(image))
        NgCoverAlbumStore.copyAppearance(context, id, false)
        val zip = File(context.cacheDir, "album.zip")
        NgCoverAlbumArchive.exportAlbum(context, id, Uri.fromFile(zip))
        val imported = NgCoverAlbumArchive.importAlbum(context, Uri.fromFile(zip))
        NgCoverAlbumStore.rename(context, imported, "导入图集")
        NgCoverAlbumStore.select(context, imported)
        NgCoverAlbumStore.reloadAfterRestore(context)
        val state = NgCoverAlbumStore.current(context)
        assertEquals(imported, state.selectedAlbumId)
        val album = state.albums.single { it.id == imported }
        assertEquals("导入图集", album.name)
        assertArrayEquals(image.readBytes(), File(album.lightImages.single()).readBytes())
        assertArrayEquals(image.readBytes(), File(album.darkImages.single()).readBytes())
        NgCoverAlbumStore.remove(context, imported)
        assertNull(NgCoverAlbumStore.current(context).selectedAlbumId)
    }

    @Test fun restoredSharedResourcesAreCopiedIntoEditableAlbumWithoutDeletingSource() {
        val id = NgCoverAlbumStore.create(context, "Restore")
        NgCoverAlbumStore.append(context, id, false, listOf(image))
        NgCoverAlbumStore.copyAppearance(context, id, false)
        val old = NgCoverAlbumStore.current(context).albums.single().lightImages.single()
        val restored = File(context.filesDir, "ng_backup_resources/restored.png")
        restored.parentFile!!.mkdirs()
        image.copyTo(restored, overwrite = true)
        val prefs = context.defaultSharedPreferences
        prefs.edit().putString("ngCoverAlbumLibrary.v1",
            prefs.getString("ngCoverAlbumLibrary.v1", "")!!.replace(old, restored.absolutePath)).commit()
        File(old).delete()
        NgCoverAlbumStore.reloadAfterRestore(context)
        val album = NgCoverAlbumStore.current(context).albums.single()
        assertEquals(album.lightImages, album.darkImages)
        assertTrue(restored.isFile)
        assertTrue(NgCoverAlbumStore.packageImage(context, id, album.lightImages.single()).isFile)
        NgCoverAlbumStore.removeImage(context, id, false, album.lightImages.single())
        assertTrue(File(album.darkImages.single()).isFile)
    }

    @Test fun rejectsTraversalWithoutChangingTheLibrary() {
        val zip = File(context.cacheDir, "unsafe.zip")
        ZipOutputStream(zip.outputStream()).use {
            it.putNextEntry(ZipEntry("../outside")); it.write(byteArrayOf(1)); it.closeEntry()
        }
        assertThrows(IllegalArgumentException::class.java) { NgCoverAlbumArchive.importAlbum(context, Uri.fromFile(zip)) }
        assertTrue(NgCoverAlbumStore.current(context).albums.isEmpty())
        assertFalse(File(context.cacheDir, "outside").exists())
    }

    @Test fun checksActualBytesRatherThanTrustingZipMetadata() {
        assertThrows(IllegalArgumentException::class.java) {
            NgCoverAlbumArchive.copyBounded(ByteArrayInputStream(ByteArray(100)), ByteArrayOutputStream(), 99)
        }
        listOf("../x", "/x", "a/../x", "a\\x", "C:/x", "a//x").forEach {
            assertFalse(it, NgCoverAlbumArchive.safeEntry(it))
        }
        assertTrue(NgCoverAlbumArchive.safeEntry("assets/day/1.png"))
        assertTrue(NgCoverAlbumArchive.MAX_TOTAL > 64L * 1024 * 1024)
    }
}
