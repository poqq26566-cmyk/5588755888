package com.example.videoconverter

import android.content.ContentValues
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.transformer.Composition
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var progressBar: ProgressBar
    private var transformer: Transformer? = null

    private val pickFileLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { convertVideo(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        progressBar = findViewById(R.id.progressBar)
        val pickButton: Button = findViewById(R.id.pickButton)

        pickButton.setOnClickListener {
            pickFileLauncher.launch(arrayOf("video/*"))
        }
    }

    private fun convertVideo(inputUri: Uri) {
        statusText.text = "转换中，请稍候..."
        progressBar.visibility = ProgressBar.VISIBLE

        val outputFile = File(cacheDir, "converted_${System.currentTimeMillis()}.mp4")

        transformer = Transformer.Builder(this)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    progressBar.visibility = ProgressBar.INVISIBLE
                    statusText.text = "转换完成，正在保存到 Downloads..."
                    saveToDownloads(outputFile)
                }

                override fun onError(
                    composition: Composition,
                    exportResult: ExportResult,
                    exportException: ExportException
                ) {
                    progressBar.visibility = ProgressBar.INVISIBLE
                    statusText.text = "转换失败: ${exportException.message}"
                }
            })
            .build()

        val mediaItem = MediaItem.Builder()
            .setUri(inputUri)
            .build()

        transformer?.start(mediaItem, outputFile.absolutePath)
    }

    private fun saveToDownloads(file: File) {
        try {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
                put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val resolver = contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            uri?.let {
                resolver.openOutputStream(it)?.use { out ->
                    file.inputStream().use { input -> input.copyTo(out) }
                }
                statusText.text = "已保存到 Downloads/${file.name}"
                Toast.makeText(this, "转换完成: ${file.name}", Toast.LENGTH_LONG).show()
            } ?: run {
                statusText.text = "保存失败：无法创建目标文件"
            }
        } catch (e: Exception) {
            statusText.text = "保存失败: ${e.message}"
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        transformer?.cancel()
    }
}
  
