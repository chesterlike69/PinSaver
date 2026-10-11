package com.example.pinsaver

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewModelScope
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val appCtx = app as App

    val pins = appCtx.repository.pins.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList()
    )
}

@OptIn(ExperimentalMaterial3Api::class)
class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    private val exportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri -> uri?.let { performExport(it) } }

    private val importLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { performImport(it) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val pins by viewModel.pins.collectAsStateWithLifecycle()

                    Scaffold(
                        topBar = {
                            TopAppBar(
                                title = { Text("PinSaver") },
                                actions = {
                                    TextButton(onClick = {
                                        val name = "pins-" + SimpleDateFormat(
                                            "yyyy-MM-dd-HHmm", Locale.US
                                        ).format(Date()) + ".db"
                                        exportLauncher.launch(name)
                                    }) { Text("Экспорт") }

                                    TextButton(onClick = {
                                        importLauncher.launch(arrayOf("*/*"))
                                    }) { Text("Импорт") }
                                }
                            )
                        }
                    ) { padding ->
                        Box(Modifier.padding(padding)) {
                            PinGrid(pins = pins, onOpen = { pin ->
                                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(pin.url)))
                            })
                        }
                    }
                }
            }
        }
    }

    // ---------- ЭКСПОРТ ----------

    private fun performExport(uri: Uri) {
        lifecycleScope.launch(Dispatchers.IO) {
            val ok = runCatching { exportDatabase(uri) }.getOrDefault(false)
            withContext(Dispatchers.Main) {
                Toast.makeText(
                    this@MainActivity,
                    if (ok) "База экспортирована" else "Не удалось экспортировать",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun exportDatabase(uri: Uri): Boolean {
        val app = application as App
        app.closeDatabase()
        try {
            val dbFile = app.dbFile()
            if (!dbFile.exists()) return false

            contentResolver.openOutputStream(uri)?.use { out ->
                dbFile.inputStream().use { it.copyTo(out) }
                return true
            } ?: return false
        } finally {
            app.reopenDatabase()
        }
    }

    // ---------- ИМПОРТ (слияние) ----------

    private fun performImport(uri: Uri) {
        lifecycleScope.launch(Dispatchers.IO) {
            val result = runCatching { mergeImport(uri) }.getOrNull()

            withContext(Dispatchers.Main) {
                val message = when {
                    result == null -> "Ошибка импорта"
                    result.total == 0 -> "Импортированы 0 пинов (файл пуст?)"
                    else -> "Импортировано: ${result.total}, добавлено: ${result.added}, " +
                            "уже было: ${result.existing}. Бэкап: ${result.backupName ?: "—"}"
                }
                Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
            }
        }
    }

    private data class ImportResult(
        val total: Int,
        val added: Int,
        val existing: Int,
        val backupName: String?
    )

    private fun mergeImport(uri: Uri): ImportResult {
        val app = application as App

        val backupName = backupCurrentDatabase(app)

        val tmp = File(cacheDir, "import-${System.currentTimeMillis()}.db")
        try {
            contentResolver.openInputStream(uri)?.use { input ->
                tmp.outputStream().use { input.copyTo(it) }
            } ?: return ImportResult(0, 0, 0, backupName)
        } catch (e: Exception) {
            tmp.delete()
            throw e
        }

        val imported: List<Pin>
        try {
            imported = readPinsFromDbFile(tmp)
        } finally {
            tmp.delete()
        }

        val beforeCount = runBlocking { app.database.pinDao().count() }
        runBlocking { app.repository.mergePins(imported) }
        val afterCount = runBlocking { app.database.pinDao().count() }
        val added = afterCount - beforeCount

        return ImportResult(
            total = imported.size,
            added = added,
            existing = imported.size - added,
            backupName = backupName
        )
    }

    private fun backupCurrentDatabase(app: App): String? {
        val dbFile = app.dbFile()
        if (!dbFile.exists()) return null

        app.closeDatabase()
        try {
            val backupDir = File(app.getExternalFilesDir(null), "backups")
            backupDir.mkdirs()
            val ts = SimpleDateFormat("yyyy-MM-dd-HHmmss", Locale.US).format(Date())
            val backupFile = File(backupDir, "pins-backup-$ts.db")
            dbFile.copyTo(backupFile, overwrite = true)
            return backupFile.name
        } catch (e: Exception) {
            return null
        } finally {
            app.reopenDatabase()
        }
    }

    private fun readPinsFromDbFile(file: File): List<Pin> {
        val db = android.database.sqlite.SQLiteDatabase.openDatabase(
            file.path, null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY
        )

        val pins = mutableListOf<Pin>()
        try {
            val hasTable = db.rawQuery(
                "SELECT name FROM sqlite_master WHERE type='table' AND name='pins'", null
            ).use { it.moveToFirst() }

            if (!hasTable) return emptyList()

            db.rawQuery(
                "SELECT url, title, author, thumbnailUrl, thumbWidth, thumbHeight, savedAt FROM pins",
                null
            ).use { c ->
                val iUrl = c.getColumnIndex("url")
                val iTitle = c.getColumnIndex("title")
                val iAuthor = c.getColumnIndex("author")
                val iThumb = c.getColumnIndex("thumbnailUrl")
                val iW = c.getColumnIndex("thumbWidth")
                val iH = c.getColumnIndex("thumbHeight")
                val iSaved = c.getColumnIndex("savedAt")

                if (iUrl < 0 || iSaved < 0) return emptyList()

                while (c.moveToNext()) {
                    val url = c.getString(iUrl) ?: continue
                    pins += Pin(
                        url = url,
                        title = if (iTitle >= 0) c.getString(iTitle) else null,
                        author = if (iAuthor >= 0) c.getString(iAuthor) else null,
                        thumbnailUrl = if (iThumb >= 0) c.getString(iThumb) else null,
                        thumbWidth = if (iW >= 0) c.getInt(iW) else 0,
                        thumbHeight = if (iH >= 0) c.getInt(iH) else 0,
                        savedAt = c.getLong(iSaved)
                    )
                }
            }
        } finally {
            db.close()
        }
        return pins
    }
}

// ---------- UI ----------

@Composable
fun PinGrid(pins: List<Pin>, onOpen: (Pin) -> Unit) {
    if (pins.isEmpty()) {
        Box(Modifier.fillMaxSize(), Alignment.Center) {
            Text(
                text = "Пока пусто.\nПоделитесь пином из Pinterest 😉",
                style = MaterialTheme.typography.bodyLarge
            )
        }
    } else {
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            contentPadding = PaddingValues(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            items(pins, key = { it.url }) { pin ->
                PinCard(pin = pin, onOpen = onOpen)
            }
        }
    }
}

@Composable
fun PinCard(pin: Pin, onOpen: (Pin) -> Unit) {
    Card(onClick = { onOpen(pin) }) {
        Column {
            val ratio = if (pin.thumbHeight > 0 && pin.thumbWidth > 0) {
                pin.thumbWidth.toFloat() / pin.thumbHeight.toFloat()
            } else 1f

            AsyncImage(
                model = pin.thumbnailUrl,
                contentDescription = pin.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(ratio)
            )

            Column(Modifier.padding(8.dp)) {
                Text(
                    text = pin.title ?: "Без названия",
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                pin.author?.let { author ->
                    Text(
                        text = author,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}