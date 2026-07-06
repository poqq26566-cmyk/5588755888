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
import com.arthenica.ffmpegkit.FFmpegSessionCompleteCallback
import com.arthenica.ffmpegkit.FFprobeKit
import com.arthenica.ffmpegkit.LogCallback
import com.arthenica.ffmpegkit.ReturnCode
import com.arthenica.ffmpegkit.StatisticsCallback
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
        progressBar.isIndeterminate = false
        progressBar.max = 100
        progressBar.progress = 0

        val inputFile = copyUriToCache(inputUri, "input_${System.currentTimeMillis()}")
        val outputFile = File(cacheDir, "converted_${System.currentTimeMillis()}.mp4")

        statusText.text = "先尝试快速换容器(不重新编码)..."

        val remuxCommand = arrayOf(
            "-y",
            "-i", inputFile.absolutePath,
            "-c", "copy",
            outputFile.absolutePath
        )

        FFmpegKit.executeAsync(toCommandString(remuxCommand)) { remuxSession ->
            runOnUiThread {
                if (ReturnCode.isSuccess(remuxSession.returnCode)) {
                    progressBar.visibility = ProgressBar.INVISIBLE
                    statusText.text = "快速换壳成功，正在保存..."
                    saveToDownloads(outputFile)
                    inputFile.delete()
                } else {
                    statusText.text = "该格式不能直接换壳，改用硬件加速编码..."
                    reencodeVideo(inputFile, outputFile)
                }
            }
        }
    }

    private fun reencodeVideo(inputFile: File, outputFile: File) {
        val durationMs = getDurationMs(inputFile.absolutePath)

        statusText.text = "转换中(硬件加速编码)... 0%"

        val command = arrayOf(
            "-y",
            "-i", inputFile.absolutePath,
            "-c:v", "h264_mediacodec",
            "-b:v", "4M",
            "-profile:v", "baseline",
            "-level", "3.0",
            "-pix_fmt", "yuv420p",
            "-c:a", "aac",
            "-ar", "44100",
            outputFile.absolutePath
        )

        FFmpegKit.executeAsync(
            toCommandString(command),
            FFmpegSessionCompleteCallback { session ->
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
            },
            LogCallback { },
            StatisticsCallback { stats ->
                if (durationMs > 0) {
                    val percent = ((stats.time / durationMs) * 100).toInt().coerceIn(0, 100)
                    runOnUiThread {
                        progressBar.progress = percent
                        statusText.text = "转换中(硬件加速编码)... $percent%"
                    }
                }
            }
        )
    }

    private fun getDurationMs(path: String): Double {
        return try {
            val session = FFprobeKit.getMediaInformation(path)
            val durationStr = session.mediaInformation?.duration
            (durationStr?.toDoubleOrNull() ?: 0.0) * 1000
        } catch (e: Exception) {
            0.0
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
    "-movflags", "+faststart",
    outputFile.absolutePath
)

val expectedDurationMs = localFiles.sumOf { getDurationMs(it.absolutePath) }

FFmpegKit.executeAsync(toCommandString(copyCommand)) { session ->
    runOnUiThread {
        val actualDurationMs = getDurationMs(outputFile.absolutePath)
        // ffmpeg 的 concat+copy 即使源文件格式不完全一致，也可能返回"成功"，
        // 但生成的文件实际无法播放。这里额外用时长做一次合理性校验：
        // 如果输出时长明显小于各段之和，说明拼接结果不可信，强制回退重新编码。
        val durationLooksValid = expectedDurationMs <= 0 ||
            actualDurationMs >= expectedDurationMs * 0.9

        if (ReturnCode.isSuccess(session.returnCode) && durationLooksValid) {
            progressBar.visibility = ProgressBar.INVISIBLE
            statusText.text = "合并完成，正在保存..."
            saveToDownloads(outputFile)
            cleanup(localFiles, listFile)
        } else {
            statusText.text = "快速拼接结果不可靠，改用重新编码合并..."
            mergeWithReencode(localFiles, listFile, outputFile)
        }
    }
}
    }

    private fun mergeWithReencode(localFiles: List<File>, listFile: File, outputFile: File) {
        val totalDurationMs = localFiles.sumOf { getDurationMs(it.absolutePath) }

        progressBar.progress = 0
        statusText.text = "重新编码合并中... 0%"

        val reencodeCommand = arrayOf(
            "-y",
            "-f", "concat",
            "-safe", "0",
            "-i", listFile.absolutePath,
            "-c:v", "h264_mediacodec",
            "-b:v", "4M",
            "-profile:v", "baseline",
            "-level", "3.0",
            "-pix_fmt", "yuv420p",
            "-c:a", "aac",
            "-ar", "44100",
            "-movflags", "+faststart",   // 新增
            outputFile.absolutePath
        )

        FFmpegKit.executeAsync(
            toCommandString(reencodeCommand),
            FFmpegSessionCompleteCallback { session ->
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
            },
            LogCallback { },
            StatisticsCallback { stats ->
                if (totalDurationMs > 0) {
                    val percent = ((stats.time / totalDurationMs) * 100).toInt().coerceIn(0, 100)
                    runOnUiThread {
                        progressBar.progress = percent
                        statusText.text = "重新编码合并中... $percent%"
                    }
                }
            }
        )
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
