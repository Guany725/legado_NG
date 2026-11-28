package io.legado.app.ui.config

import android.os.Bundle
import android.view.View
import android.widget.ImageView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import io.legado.app.R
import io.legado.app.base.BaseFragment
import io.legado.app.constant.EventBus
import io.legado.app.help.config.NgCoverAlbum
import io.legado.app.help.config.NgCoverAlbumArchive
import io.legado.app.help.config.NgCoverAlbumStore
import io.legado.app.help.config.NgThemeLibraryStore
import io.legado.app.model.BookCover
import io.legado.app.ui.design.theme.NgAppTheme
import io.legado.app.utils.postEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class CoverAlbumManagerFragment : BaseFragment(R.layout.fragment_cover_config), ConfigBackHandler {
    private var selectedId by mutableStateOf<String?>(null)
    private var dark by mutableStateOf(false)
    private var optimize by mutableStateOf(true)
    private var busy by mutableStateOf(false)
    private var notice by mutableStateOf<String?>(null)
    private var pendingId: String? = null
    private var pendingDark = false

    private val importPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) runOperation {
            val id = NgCoverAlbumArchive.importAlbum(requireContext(), uri)
            withContext(Dispatchers.Main) { selectedId = id }
        }
    }
    private val imagesPicker = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        val id = pendingId
        val night = pendingDark
        if (id != null && uris.isNotEmpty()) runOperation {
            NgCoverAlbumArchive.addImages(requireContext(), id, night, uris, optimize)
        }
    }
    private val exportPicker = registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        val id = pendingId
        if (uri != null && id != null) runOperation { NgCoverAlbumArchive.exportAlbum(requireContext(), id, uri) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        savedInstanceState?.let {
            selectedId = it.getString("album")
            dark = it.getBoolean("dark")
            optimize = it.getBoolean("optimize", true)
            pendingId = it.getString("pendingAlbum")
            pendingDark = it.getBoolean("pendingDark")
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("album", selectedId)
        outState.putBoolean("dark", dark)
        outState.putBoolean("optimize", optimize)
        outState.putString("pendingAlbum", pendingId)
        outState.putBoolean("pendingDark", pendingDark)
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        activity?.setTitle(R.string.album_manager_title)
        (view as ComposeView).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent { NgAppTheme { Content() } }
        }
    }

    override fun onResume() {
        super.onResume()
        activity?.setTitle(R.string.album_manager_title)
    }

    override fun onConfigBackPressed(): Boolean {
        if (busy) return true
        if (selectedId != null) { selectedId = null; return true }
        return false
    }

    private fun runOperation(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        // Keep an operation alive when only the Fragment's view is recreated.
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { block() }
                BookCover.upDefaultCover()
                postEvent(EventBus.BOOKSHELF_REFRESH, "")
                notice = getString(R.string.album_operation_complete)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                notice = error.localizedMessage ?: error.javaClass.simpleName
            } finally { busy = false }
        }
    }

    @Composable
    private fun Content() {
        val library by NgCoverAlbumStore.observe(requireContext()).collectAsState()
        val album = library.albums.find { it.id == selectedId }
        var editName by remember { mutableStateOf<String?>(null) }
        var editingId by remember { mutableStateOf<String?>(null) }
        var confirm by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }
        var preview by remember { mutableStateOf<String?>(null) }
        val ctx = requireContext().applicationContext
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 24.dp)) {
                if (album == null) {
                    item {
                        Row {
                            TextButton(enabled = !busy, onClick = { editingId = null; editName = "" }) { Text(stringResource(R.string.album_create)) }
                            TextButton(enabled = !busy, onClick = { importPicker.launch(arrayOf("application/zip", "application/octet-stream")) }) { Text(stringResource(R.string.album_import)) }
                        }
                        Text(stringResource(R.string.album_manager_hint), style = MaterialTheme.typography.bodyMedium)
                        if (library.albums.isEmpty()) Text(stringResource(R.string.album_empty), modifier = Modifier.padding(vertical = 24.dp))
                    }
                    items(library.albums, key = { it.id }) { entry ->
                        ListItem(
                            headlineContent = { Text(entry.name) },
                            supportingContent = { Text(stringResource(R.string.ng_cover_album_count, entry.lightImages.size, entry.darkImages.size)) },
                            trailingContent = { if (library.selectedAlbumId == entry.id) Text(stringResource(R.string.album_in_use)) },
                            modifier = Modifier.clickable(enabled = !busy) { selectedId = entry.id },
                        )
                    }
                } else {
                    item {
                        TextButton(enabled = !busy, onClick = { selectedId = null }) { Text(stringResource(R.string.album_back)) }
                        Text(album.name, style = MaterialTheme.typography.titleLarge)
                        Row {
                            TextButton(enabled = !busy, onClick = { runOperation { NgCoverAlbumStore.select(ctx, album.id) } }) { Text(stringResource(R.string.album_use)) }
                            TextButton(enabled = !busy, onClick = { editingId = album.id; editName = album.name }) { Text(stringResource(R.string.album_rename)) }
                            TextButton(enabled = !busy, onClick = { pendingId = album.id; exportPicker.launch("cover-album-${System.currentTimeMillis()}.zip") }) { Text(stringResource(R.string.album_export)) }
                        }
                        Row {
                            FilterChip(selected = !dark, enabled = !busy, onClick = { dark = false }, label = { Text(stringResource(R.string.day)) })
                            Spacer(Modifier.width(12.dp))
                            FilterChip(selected = dark, enabled = !busy, onClick = { dark = true }, label = { Text(stringResource(R.string.night)) })
                        }
                        Row {
                            Checkbox(checked = optimize, enabled = !busy, onCheckedChange = { optimize = it })
                            Text(stringResource(R.string.album_optimize), modifier = Modifier.padding(top = 12.dp))
                        }
                        TextButton(enabled = !busy, onClick = { pendingId = album.id; pendingDark = dark; imagesPicker.launch(arrayOf("image/*")) }) { Text(stringResource(R.string.album_add_images)) }
                        TextButton(enabled = !busy, onClick = {
                            val fromDark = dark
                            confirm = getString(R.string.album_copy_confirm) to { runOperation { NgCoverAlbumStore.copyAppearance(ctx, album.id, fromDark) } }
                        }) { Text(stringResource(R.string.album_copy_appearance)) }
                        HorizontalDivider()
                    }
                    val paths = if (dark) album.darkImages else album.lightImages
                    items(paths, key = { it }) { path ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                            AlbumImage(path, Modifier.width(84.dp).height(112.dp).clickable(enabled = !busy) { preview = path })
                            Text(File(path).name, modifier = Modifier.weight(1f).padding(8.dp), maxLines = 2)
                            TextButton(enabled = !busy, onClick = {
                                val fromDark = dark
                                confirm = getString(R.string.album_delete_image_confirm) to { runOperation { NgCoverAlbumStore.removeImage(ctx, album.id, fromDark, path) } }
                            }) { Text(stringResource(R.string.delete)) }
                        }
                    }
                    item {
                        if (paths.isEmpty()) Text(stringResource(R.string.album_no_images), modifier = Modifier.padding(vertical = 16.dp))
                        TextButton(enabled = !busy, onClick = {
                            confirm = getString(R.string.ng_cover_album_delete_message, album.name) to {
                                runOperation { NgThemeLibraryStore.detachCoverAlbum(ctx, album.id); NgCoverAlbumStore.remove(ctx, album.id) }
                            }
                        }) { Text(stringResource(R.string.album_delete)) }
                    }
                }
            }
        }
        editName?.let { value ->
            AlertDialog(onDismissRequest = { editName = null },
                title = { Text(stringResource(R.string.album_name)) },
                text = { OutlinedTextField(value = value, onValueChange = { if (it.length <= 120) editName = it }, singleLine = true) },
                confirmButton = { TextButton(enabled = value.isNotBlank(), onClick = {
                    val id = editingId; editName = null
                    runOperation {
                        if (id != null) NgCoverAlbumStore.rename(ctx, id, value)
                        else { val created = NgCoverAlbumStore.create(ctx, value); withContext(Dispatchers.Main) { selectedId = created } }
                    }
                }) { Text(stringResource(R.string.ok)) } },
                dismissButton = { TextButton(onClick = { editName = null }) { Text(stringResource(R.string.cancel)) } },
            )
        }
        confirm?.let { (message, action) ->
            AlertDialog(onDismissRequest = { confirm = null }, text = { Text(message) },
                confirmButton = { TextButton(onClick = { confirm = null; action() }) { Text(stringResource(R.string.ok)) } },
                dismissButton = { TextButton(onClick = { confirm = null }) { Text(stringResource(R.string.cancel)) } })
        }
        preview?.let { path ->
            AlertDialog(onDismissRequest = { preview = null }, text = { AlbumImage(path, Modifier.fillMaxWidth().height(420.dp)) },
                confirmButton = { TextButton(onClick = { preview = null }) { Text(stringResource(R.string.ok)) } })
        }
        notice?.let { message ->
            AlertDialog(onDismissRequest = { notice = null }, text = { Text(message) },
                confirmButton = { TextButton(onClick = { notice = null }) { Text(stringResource(R.string.ok)) } })
        }
    }
}

@Composable
private fun AlbumImage(path: String, modifier: Modifier) {
    val description = stringResource(R.string.album_preview)
    AndroidView(
        factory = { context -> ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_CENTER } },
        modifier = modifier,
        update = { view -> view.contentDescription = description; Glide.with(view).load(File(path)).into(view) },
        onRelease = { view -> Glide.with(view).clear(view) },
    )
}
