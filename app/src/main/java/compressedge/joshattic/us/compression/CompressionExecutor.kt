package compressedge.joshattic.us.compression

import android.content.Context
import android.net.Uri
import android.os.Build
import androidx.annotation.OptIn
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.common.util.Clock
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.FrameDropEffect
import androidx.media3.effect.Presentation
import androidx.media3.transformer.AudioEncoderSettings
import androidx.media3.transformer.Codec
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultAssetLoaderFactory
import androidx.media3.transformer.DefaultDecoderFactory
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.DefaultMuxer
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import compressedge.joshattic.us.R
import compressedge.joshattic.us.utils.VolumeAudioProcessor
import java.io.File
import android.os.Handler
import android.os.Looper
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Runs a single Media3 Transformer export for either the foreground or background path. */
@OptIn(UnstableApi::class)
object CompressionExecutor {

    data class Params(
        val inputUri: Uri,
        val outputPath: String,
        val videoMimeType: String,
        val outputHeight: Int,
        val outputFps: Int,
        val originalWidth: Int,
        val originalHeight: Int,
        val originalFps: Float,
        val targetBitrate: Int,
        val audioBitrate: Int,
        val audioCodec: String,
        val removeAudio: Boolean,
        val audioVolume: Float,
        val onHdrToneMap: (() -> Unit)? = null,
        /** Force a video encoder bitrate mode (BITRATE_MODE_CBR / BITRATE_MODE_VBR); null = SoC heuristic. */
        val bitrateModeOverride: Int? = null
    )

    fun execute(
        context: Context,
        params: Params,
        onCompleted: (Long) -> Unit,
        onError: (ExportException) -> Unit
    ): Transformer {
        val videoMimeType = params.videoMimeType

        val decoderFactory = DefaultDecoderFactory.Builder(context)
            .setEnableDecoderFallback(true)
            .build()

        val cbrEncoderFactory = DefaultEncoderFactory.Builder(context)
            .setEnableFallback(true)
            .setRequestedVideoEncoderSettings(
                VideoEncoderSettings.Builder()
                    .setBitrate(params.targetBitrate)
                    .setBitrateMode(android.media.MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
                    .build()
            )
            .setRequestedAudioEncoderSettings(
                AudioEncoderSettings.Builder()
                    .setBitrate(params.audioBitrate)
                    .build()
            )
            .build()

        val vbrEncoderFactory = DefaultEncoderFactory.Builder(context)
            .setEnableFallback(true)
            .setRequestedVideoEncoderSettings(
                VideoEncoderSettings.Builder()
                    .setBitrate(params.targetBitrate)
                    .setBitrateMode(android.media.MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
                    .build()
            )
            .setRequestedAudioEncoderSettings(
                AudioEncoderSettings.Builder()
                    .setBitrate(params.audioBitrate)
                    .build()
            )
            .build()

        val isMediaTek = isMediaTekDeviceOrEncoder(videoMimeType)
        val primaryIsVbr = when (params.bitrateModeOverride) {
            android.media.MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR -> true
            android.media.MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR -> false
            else -> isMediaTek
        }
        val primaryEncoderFactory = if (primaryIsVbr) vbrEncoderFactory else cbrEncoderFactory
        val fallbackEncoderFactory = if (primaryIsVbr) cbrEncoderFactory else vbrEncoderFactory

        val encoderFactory = object : Codec.EncoderFactory {
            override fun createForAudioEncoding(
                format: androidx.media3.common.Format,
                logSessionId: android.media.metrics.LogSessionId?
            ): Codec {
                return primaryEncoderFactory.createForAudioEncoding(format, logSessionId)
            }

            override fun createForVideoEncoding(
                format: androidx.media3.common.Format,
                logSessionId: android.media.metrics.LogSessionId?
            ): Codec {
                val targetFps = if (params.outputFps > 0) params.outputFps.toFloat() else params.originalFps
                var modifiedFormatBuilder = format.buildUpon()
                if (targetFps > 0f) {
                    modifiedFormatBuilder.setFrameRate(targetFps)
                }
                if (format.colorInfo == null || !androidx.media3.common.ColorInfo.isTransferHdr(format.colorInfo)) {
                    modifiedFormatBuilder.setColorInfo(null)
                }
                val modifiedFormat = modifiedFormatBuilder.build()

                return try {
                    primaryEncoderFactory.createForVideoEncoding(modifiedFormat, logSessionId)
                } catch (e: Exception) {
                    fallbackEncoderFactory.createForVideoEncoding(modifiedFormat, logSessionId)
                }
            }

            override fun audioNeedsEncoding(): Boolean = primaryEncoderFactory.audioNeedsEncoding()
            override fun videoNeedsEncoding(): Boolean = primaryEncoderFactory.videoNeedsEncoding()
        }

        val audioMimeType = when (params.audioCodec) {
            MimeTypes.AUDIO_OPUS -> MimeTypes.AUDIO_OPUS
            else -> MimeTypes.AUDIO_AAC
        }

        val transformerBuilder = Transformer.Builder(context)
            .setLooper(Looper.getMainLooper())
            // Default is 10s; some chipsets' MediaCodec encoder is slow to
            // produce its first samples, so give it more room before the
            // muxer watchdog declares the pipeline stuck.
            .setMaxDelayBetweenMuxerSamplesMs(30_000)
            .setVideoMimeType(videoMimeType)
            .setAudioMimeType(audioMimeType)
            .apply {
                if (audioMimeType == MimeTypes.AUDIO_OPUS) {
                    setMuxerFactory(DefaultMuxer.Factory())
                }
            }
            .setAssetLoaderFactory(DefaultAssetLoaderFactory(context, decoderFactory, Clock.DEFAULT, android.media.metrics.LogSessionId.LOG_SESSION_ID_NONE))
            .setEncoderFactory(encoderFactory)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    onCompleted(File(params.outputPath).length())
                }

                override fun onError(
                    composition: Composition,
                    exportResult: ExportResult,
                    exportException: ExportException
                ) {
                    onError(exportException)
                }
            })

        val transformer = transformerBuilder.build()

        val effectsList = mutableListOf<Effect>()

        if (params.outputHeight > 0 && params.outputHeight != params.originalHeight) {
            val aspectRatio =
                if (params.originalHeight > 0) params.originalWidth.toFloat() / params.originalHeight else 16f / 9f
            var width = (params.outputHeight * aspectRatio).toInt()
            var height = params.outputHeight

            // Align the computed width down to a multiple of 16 (and the
            // height to a multiple of 8). Merely being even is not enough
            // for some vendor HEVC encoders: at sizes like 644x480 they
            // accept the configuration but then never produce a single
            // output sample, so the export dies on the muxer watchdog.
            width = (width / 16) * 16
            height = (height / 8) * 8

            if (width > 0 && height > 0) {
                effectsList.add(Presentation.createForWidthAndHeight(width, height, Presentation.LAYOUT_SCALE_TO_FIT))
            }
        }

        if (params.outputFps > 0 && params.outputFps.toFloat() < params.originalFps) {
            effectsList.add(FrameDropEffect.createSimpleFrameDropEffect(params.originalFps, params.outputFps.toFloat()))
        }

        val mediaItem = MediaItem.fromUri(params.inputUri)
        val audioProcessors: List<AudioProcessor> = if (!params.removeAudio) {
            val volumeProcessor = VolumeAudioProcessor().apply { setVolume(params.audioVolume) }
            listOf(volumeProcessor, SonicAudioProcessor())
        } else {
            emptyList()
        }
        val editedMediaItem = EditedMediaItem.Builder(mediaItem)
            .setEffects(Effects(audioProcessors, effectsList))
            .setRemoveAudio(params.removeAudio)
            .build()

        var hdrMode = Composition.HDR_MODE_KEEP_HDR
        if (Build.MANUFACTURER.equals("Google", ignoreCase = true) && Build.MODEL.contains("Pixel 10")) {
            if (videoMimeType == MimeTypes.VIDEO_H265 || videoMimeType == MimeTypes.VIDEO_H264) {
                if (isHdr(context, params.inputUri)) {
                    hdrMode = Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL
                    params.onHdrToneMap?.invoke()
                }
            }
        }

        val composition = Composition.Builder(
            listOf(EditedMediaItemSequence.withAudioAndVideoFrom(listOf(editedMediaItem)))
        )
            .setHdrMode(hdrMode)
            .build()

        transformer.start(composition, params.outputPath)
        return transformer
    }

    /**
     * Compact description of the input file's tracks (codec, size, rotation,
     * frame rate, color, audio format) for error reports. Best-effort: any
     * failure just notes itself in the output.
     */
    fun probeInput(context: Context, uri: Uri): String {
        val sb = StringBuilder()
        try {
            val extractor = android.media.MediaExtractor()
            try {
                extractor.setDataSource(context, uri, null)
                for (i in 0 until extractor.trackCount) {
                    val f = extractor.getTrackFormat(i)
                    val mime = try { f.getString(android.media.MediaFormat.KEY_MIME) } catch (e: Exception) { null } ?: "?"
                    fun intOrNull(key: String): Int? =
                        if (f.containsKey(key)) try { f.getInteger(key) } catch (e: Exception) { null } else null
                    when {
                        mime.startsWith("video/") -> {
                            sb.append("Input video track: ").append(mime)
                            val w = intOrNull(android.media.MediaFormat.KEY_WIDTH)
                            val h = intOrNull(android.media.MediaFormat.KEY_HEIGHT)
                            if (w != null && h != null) sb.append(" ").append(w).append("x").append(h)
                            intOrNull(android.media.MediaFormat.KEY_ROTATION)?.let { sb.append(" rot=").append(it) }
                            intOrNull(android.media.MediaFormat.KEY_FRAME_RATE)?.let { sb.append(" fps=").append(it) }
                            val cs = intOrNull(android.media.MediaFormat.KEY_COLOR_STANDARD)
                            val ct = intOrNull(android.media.MediaFormat.KEY_COLOR_TRANSFER)
                            val cr = intOrNull(android.media.MediaFormat.KEY_COLOR_RANGE)
                            if (cs != null || ct != null || cr != null) {
                                sb.append(" color(std=").append(cs).append(",transfer=").append(ct).append(",range=").append(cr).append(")")
                            }
                            intOrNull(android.media.MediaFormat.KEY_BIT_RATE)?.let { sb.append(" bitrate=").append(it) }
                            sb.append("\n")
                        }
                        mime.startsWith("audio/") -> {
                            sb.append("Input audio track: ").append(mime)
                            intOrNull(android.media.MediaFormat.KEY_SAMPLE_RATE)?.let { sb.append(" ").append(it).append("Hz") }
                            intOrNull(android.media.MediaFormat.KEY_CHANNEL_COUNT)?.let { sb.append(" ").append(it).append("ch") }
                            intOrNull(android.media.MediaFormat.KEY_BIT_RATE)?.let { sb.append(" bitrate=").append(it) }
                            sb.append("\n")
                        }
                    }
                }
            } finally {
                extractor.release()
            }
        } catch (e: Exception) {
            sb.append("Input probe failed: ").append(e.message).append("\n")
        }
        if (sb.isEmpty()) sb.append("Input probe: no tracks found\n")
        return sb.toString().trim()
    }

    /** True when the export died because the muxer watchdog saw no output samples at all (encoder stall). */
    fun isMuxerStall(e: Throwable): Boolean {
        var t: Throwable? = e
        while (t != null) {
            if (t.message?.contains("no output sample written") == true) return true
            t = t.cause
        }
        return false
    }

    suspend fun executeSuspend(
        context: Context,
        params: Params,
        onProgress: (androidx.media3.transformer.ProgressHolder, Transformer) -> Unit,
        onStallRetry: (() -> Unit)? = null
    ): Long {
        return try {
            attemptOnce(context, params, onProgress)
        } catch (e: ExportException) {
            if (!isMuxerStall(e)) throw e
            onStallRetry?.invoke()
            // The encoder accepted the configuration but never produced a
            // sample. Retry once with the opposite bitrate mode, which is a
            // materially different encoder configuration on vendor codecs.
            try { File(params.outputPath).delete() } catch (_: Exception) {}
            val cbr = android.media.MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR
            val vbr = android.media.MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR
            val retryOverride = when (params.bitrateModeOverride) {
                vbr -> cbr
                cbr -> vbr
                else -> if (isMediaTekDeviceOrEncoder(params.videoMimeType)) cbr else vbr
            }
            attemptOnce(context, params.copy(bitrateModeOverride = retryOverride), onProgress)
        }
    }

    private suspend fun attemptOnce(
        context: Context,
        params: Params,
        onProgress: (androidx.media3.transformer.ProgressHolder, Transformer) -> Unit
    ): Long = withContext(Dispatchers.Main) {
        var progressJob: Job? = null
        try {
            kotlinx.coroutines.suspendCancellableCoroutine { cont ->
                val transformer = try {
                    execute(
                        context,
                        params,
                        onCompleted = { size -> if (cont.isActive) cont.resume(size) },
                        onError = { err -> if (cont.isActive) cont.resumeWithException(err) }
                    )
                } catch (e: Exception) {
                    if (cont.isActive) cont.resumeWithException(e)
                    return@suspendCancellableCoroutine
                }

                progressJob = launch {
                    while (isActive) {
                        val holder = androidx.media3.transformer.ProgressHolder()
                        val state = transformer.getProgress(holder)
                        if (state != Transformer.PROGRESS_STATE_NOT_STARTED) {
                            onProgress(holder, transformer)
                        }
                        delay(200)
                    }
                }

                cont.invokeOnCancellation {
                    if (Looper.myLooper() == Looper.getMainLooper()) {
                        try {
                            transformer.cancel()
                        } catch (_: Exception) {}
                    } else {
                        Handler(Looper.getMainLooper()).post {
                            try {
                                transformer.cancel()
                            } catch (_: Exception) {}
                        }
                    }
                }
            }
        } finally {
            progressJob?.cancel()
        }
    }

    fun errorMessage(context: Context, exception: ExportException): String {
        val isCodecError = exception.errorCode == ExportException.ERROR_CODE_DECODER_INIT_FAILED ||
            exception.errorCode == ExportException.ERROR_CODE_ENCODER_INIT_FAILED
        val isDecoderInitError = exception.errorCode == ExportException.ERROR_CODE_DECODER_INIT_FAILED
        val isEncoderInitError = exception.errorCode == ExportException.ERROR_CODE_ENCODER_INIT_FAILED
        val isMuxerError = exception.errorCode == ExportException.ERROR_CODE_MUXING_FAILED
        val isHuawei = Build.MANUFACTURER.equals("HUAWEI", ignoreCase = true)

        return when {
            isMuxerError && isHuawei -> context.getString(R.string.error_huawei_muxer)
            isDecoderInitError -> context.getString(R.string.error_decoder_config_unsupported)
            isEncoderInitError -> context.getString(R.string.error_encoder_config_unsupported)
            isCodecError -> context.getString(R.string.error_codec_unsupported)
            else -> exception.localizedMessage ?: context.getString(R.string.error_unknown)
        }
    }

    private fun isMediaTekDeviceOrEncoder(mimeType: String): Boolean {
        try {
            val hardware = Build.HARDWARE.lowercase()
            val board = Build.BOARD.lowercase()
            val manufacturer = Build.MANUFACTURER.lowercase()
            val soc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MODEL.lowercase() else ""

            if (hardware.contains("mediatek") || board.contains("mediatek") || manufacturer.contains("mediatek") ||
                soc.contains("mediatek") || soc.contains("dimensity") ||
                hardware.matches(Regex(""".*mt\d{4}.*""")) || board.matches(Regex(""".*mt\d{4}.*"""))
            ) {
                return true
            }

            val list = android.media.MediaCodecList(android.media.MediaCodecList.ALL_CODECS)
            for (info in list.codecInfos) {
                if (!info.isEncoder) continue
                if (info.supportedTypes.any { it.equals(mimeType, ignoreCase = true) }) {
                    val name = info.name.lowercase()
                    if (name.contains("mtk") || name.contains("mediatek")) {
                        return true
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return false
    }

    private fun isHdr(context: Context, uri: Uri): Boolean {
        val retriever = android.media.MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            if (Build.VERSION.SDK_INT >= 30) {
                val transfer =
                    retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_COLOR_TRANSFER)
                return transfer == "6" || transfer == "7"
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            try { retriever.release() } catch (e: Exception) {}
        }
        return false
    }
}
