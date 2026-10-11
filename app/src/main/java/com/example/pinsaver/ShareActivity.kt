package com.example.pinsaver

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ShareActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val sharedText = intent?.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
        val url = extractPinterestUrl(sharedText)

        if (url == null) {
            Toast.makeText(applicationContext, "Это не ссылка на пин", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val app = application as App
        app.applicationScope.launch {
            app.repository.saveFromUrl(url)
            withContext(Dispatchers.Main) {
                Toast.makeText(applicationContext, "Пин сохранён", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    private fun extractPinterestUrl(text: String): String? {
        val regex = Regex(
            """https?://(?:[a-z0-9-]+\.)*pinterest\.[a-z.]+/\S+|https?://pin\.it/\S+""",
            RegexOption.IGNORE_CASE
        )
        val match = regex.find(text)?.value ?: return null

        val cleaned = match.trimEnd(
            '.', ',', ';', '!', '?', ')', ']', '}', '»', '«', '"', '\''
        )

        val isPin = cleaned.contains("/pin/") || cleaned.contains("pin.it")
        return if (isPin) cleaned else null
    }
}