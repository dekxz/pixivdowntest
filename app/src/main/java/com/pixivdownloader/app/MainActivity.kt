package com.pixivdownloader.app

import android.app.DownloadManager
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels

class MainActivity : ComponentActivity() {

    private val vm: MainViewModel by viewModels()
    private var lastClipboard: String? = null

    /** System file picker for "Pick existing history file". */
    private val pickHistoryFile =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) vm.useHistoryFile(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleShare(intent)

        setContent {
            MainScreen(
                vm = vm,
                onOpenFolder = ::openDownloads,
                onPickHistory = { pickHistoryFile.launch(arrayOf("*/*")) },
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    /** Link shared from Pixiv / a browser ("Share to > Pixiv Downloader"). */
    private fun handleShare(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND) {
            Utils.findPixivUrl(intent.getStringExtra(Intent.EXTRA_TEXT))?.let { vm.setUrl(it) }
        }
    }

    /**
     * Android equivalent of the desktop clipboard monitor. Android only lets the focused app read
     * the clipboard, so we check whenever the window gains focus.
     */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) return
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()
        if (text != null && text != lastClipboard) {
            lastClipboard = text
            if (!vm.busy.value) Utils.findPixivUrl(text)?.let { vm.setUrl(it) }
        }
    }

    private fun openDownloads() {
        try {
            startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            vm.message.value = MainViewModel.Msg("Error", "Could not open folder: ${e.message}\nFiles are in Downloads/${MediaExporter.FOLDER}.")
        }
    }
}
