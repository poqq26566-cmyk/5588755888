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
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var progressBar: ProgressBar

    private val pickConvertLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { convertVideo(it) }
    }

    private val pickMergeLauncher = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        if (uris.size >= 2) {
            mergeVideos(uris)
        } else if (uris.isNotEmpty()) {
            statusText.text = "合并至少需要选择2个文件"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        progressBar = findViewById(R.id.progressBar)

        val pickButton: Button = findViewById(R.id.pickButton)
        val mergeButton: Button = findViewById(R.id.mergeButton)

        pickButton.setOnClickListener {
            pickConvertLauncher.launch(arrayOf("video/*"))
        }

        mergeButton.setOnClickListener {
            pickMergeLauncher.launch(arrayOf("video/*"))
        }
    }

    private fun convertVideo(inputUri: Uri) {
        statusText.text = "准备文件..."
        progressBar.visibility = ProgressBar.VISIBLE

        val inputFile = copyUriToCache(inputUri, "input_${System.currentTimeMillis()}")
        val outputFile = File(cacheDir, "converted_${System.currentTimeMillis()}.mp4")

        statusText.text = "转换中..."

        val command = arrayOf(
            "-y",
            "-i", inputFile.absolutePath,
            "-c:v", "libx264",
            "-preset", "ultrafast",
            "-crf", "23",
            "-c:a", "aac",
            outputFile.absolutePath
        )

        FFmpegKit.executeAsync(toCommandString(command)) { session ->
            runOnUiThread {
                progressBar.visibility = ProgressBar.INVISIBLE
                if (ReturnCode.isSuccess(session.returnCode)) {
                    statusText.text = "转换完成，正在保存..."
                    saveToDownloads(outputFile)
                } else {
    val logs = try { session.allLogsAsString } catch (e: Exception) { "" }
    statusText.text = "转换失败 (返回码 ${session.returnCode})\n\n$logs"
                }
                inputFile.delete()
            }
        }
    }

    private fun mergeVideos(uris: List<Uri>) {
        statusText.text = "准备 ${uris.size} 个文件..."
        progressBar.visibility = ProgressBar.VISIBLE

        val localFiles = uris.mapIndexed { index, uri ->
            copyUriToCache(uri, "merge_${index}_${System.currentTimeMillis()}")
        }

        val listFile = File(cacheDir, "concat_list_${System.currentTimeMillis()}.txt")
        listFile.bufferedWriter().use { writer ->
            localFiles.forEach { f ->
                val escapedPath = f.absolutePath.replace("'", "'\\''")
                writer.write("file '$escapedPath'\n")
            }
        }

        val outputFile = File(cacheDir, "merged_${System.currentTimeMillis()}.mp4")

        statusText.text = "合并中(无损快速拼接)..."

        val copyCommand = arrayOf(
            "-y",
            "-f", "concat",
            "-safe", "0",
            "-i", listFile.absolutePath,
            "-c", "copy",
            outputFile.absolutePath
        )

        FFmpegKit.executeAsync(toCommandString(copyCommand)) { session ->
            runOnUiThread {
                if (ReturnCode.isSuccess(session.returnCode)) {
                    progressBar.visibility = ProgressBar.INVISIBLE
                    statusText.text = "合并完成，正在保存..."
                    saveToDownloads(outputFile)
                    cleanup(localFiles, listFile)
                } else {
                    statusText.text = "格式不完全一致，改用重新编码合并..."
                    mergeWithReencode(localFiles, listFile, outputFile)
                }
            }
        }
    }

    private fun mergeWithReencode(localFiles: List<File>, listFile: File, outputFile: File) {
        val reencodeCommand = arrayOf(
            "-y",
            "-f", "concat",
            "-safe", "0",
            "-i", listFile.absolutePath,
            "-c:v", "libx264",
            "-preset", "ultrafast",
            "-c:a", "aac",
            outputFile.absolutePath
        )

        FFmpegKit.executeAsync(toCommandString(reencodeCommand)) { session ->
            runOnUiThread {
                progressBar.visibility = ProgressBar.INVISIBLE
                if (ReturnCode.isSuccess(session.returnCode)) {
                    statusText.text = "合并完成(已重新编码)，正在保存..."
                    saveToDownloads(outputFile)
                } else {
                    statusText.text = "合并失败 (返回码 ${session.returnCode})"
                }
                cleanup(localFiles, listFile)
            }
        }
    }

    private fun copyUriToCache(uri: Uri, name: String): File {
        val file = File(cacheDir, name)
        contentResolver.openInputStream(uri)?.use { input ->
            file.outputStream().use { output -> input.copyTo(output) }
        }
        return file
    }

    private fun toCommandString(args: Array<String>): String =
        args.joinToString(" ") { arg ->
            if (arg.contains(" ")) "\"$arg\"" else arg
        }

    private fun cleanup(files: List<File>, listFile: File) {
        files.forEach { it.delete() }
        listFile.delete()
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
                Toast.makeText(this, "完成: ${file.name}", Toast.LENGTH_LONG).show()
            } ?: run {
                statusText.text = "保存失败：无法创建目标文件"
            }
        } catch (e: Exception) {
            statusText.text = "保存失败: ${e.message}"
        }
    }
}
