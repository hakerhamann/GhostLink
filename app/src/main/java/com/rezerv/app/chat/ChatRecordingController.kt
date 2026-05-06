package com.rezerv.app.chat

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.google.common.util.concurrent.ListenableFuture
import com.rezerv.app.databinding.ActivityChatBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.abs

internal class ChatRecordingController(
    private val activity: AppCompatActivity,
    private val binding: ActivityChatBinding,
    private val requestAudioPermission: () -> Unit,
    private val requestVideoPermissions: () -> Unit,
    private val hideEmojiPanel: () -> Unit,
    private val updateComposerActionState: () -> Unit,
    private val sendVoice: (File, Int) -> Unit,
    private val sendVideo: (File, Int) -> Unit
) {
    private var currentRecordMode: RecordMode = RecordMode.VOICE
    private var pendingPermissionRecordingType: RecordingType? = null
    private var voiceButtonPressed: Boolean = false
    private var longPressTriggered: Boolean = false
    private var voiceTouchDownX: Float = 0f
    private var voiceTouchDownY: Float = 0f
    private val voiceButtonLongPressHandler = Handler(Looper.getMainLooper())
    private var voiceRecorder: MediaRecorder? = null
    private var voiceRecordFile: File? = null
    private var recordingStartedAtMs: Long = 0L
    private var isVoiceRecording: Boolean = false
    private var cameraProviderFuture: ListenableFuture<ProcessCameraProvider>? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var videoPreviewUseCase: Preview? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var videoRecording: Recording? = null
    private var videoRecordFile: File? = null
    private var videoCameraFacing: Int = CameraSelector.LENS_FACING_FRONT
    private var isVideoRecording: Boolean = false
    private var pendingVideoSendAfterStop: Boolean = false
    private var pendingCameraSwitchAfterFinalize: Boolean = false
    private var videoSegments = mutableListOf<File>()
    private val recordingUiHandler = Handler(Looper.getMainLooper())

    init {
        binding.videoRecordingPreview.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        binding.videoRecordingPreview.scaleType = PreviewView.ScaleType.FILL_CENTER
        binding.videoRecordingProgress.setProgressFraction(0f)
    }

    private val voiceLongPressRunnable = Runnable {
        if (!voiceButtonPressed || longPressTriggered || isAnyRecordingInProgress()) return@Runnable
        longPressTriggered = true
        when (currentRecordMode) {
            RecordMode.VOICE -> ensureAudioPermissionAndRecord()
            RecordMode.VIDEO -> ensureVideoPermissionsAndRecord()
        }
    }

    private val recordingTicker = object : Runnable {
        override fun run() {
            if (!isAnyRecordingInProgress()) return
            val elapsedSec = ((System.currentTimeMillis() - recordingStartedAtMs) / 1000L).toInt().coerceAtLeast(0)
            if (isVideoRecording && elapsedSec >= MAX_VIDEO_RECORD_DURATION_SEC) {
                stopVideoRecording(send = true)
                return
            }
            updateRecordingUi(recording = true, elapsedSec = elapsedSec)
            recordingUiHandler.postDelayed(this, 1000L)
        }
    }

    fun handleAudioPermissionResult(granted: Boolean) {
        val pendingType = pendingPermissionRecordingType
        pendingPermissionRecordingType = null
        if (!granted) {
            Toast.makeText(activity, "Нужно разрешение на микрофон", Toast.LENGTH_SHORT).show()
            return
        }
        if (pendingType == RecordingType.VOICE && voiceButtonPressed) {
            startVoiceRecordingInternal()
        }
    }

    fun handleVideoPermissionsResult(cameraGranted: Boolean, audioGranted: Boolean) {
        val pendingType = pendingPermissionRecordingType
        pendingPermissionRecordingType = null
        if (!cameraGranted || !audioGranted) {
            Toast.makeText(activity, "Нужны разрешения на камеру и микрофон", Toast.LENGTH_SHORT).show()
            return
        }
        if (pendingType == RecordingType.VIDEO && voiceButtonPressed) {
            startVideoRecordingInternal()
        }
    }

    fun handleVoiceButtonTouch(view: View, event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                voiceButtonPressed = true
                longPressTriggered = false
                voiceTouchDownX = event.x
                voiceTouchDownY = event.y
                voiceButtonLongPressHandler.removeCallbacks(voiceLongPressRunnable)
                voiceButtonLongPressHandler.postDelayed(
                    voiceLongPressRunnable,
                    ViewConfiguration.getLongPressTimeout().toLong()
                )
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (!longPressTriggered) {
                    val slop = ViewConfiguration.get(activity).scaledTouchSlop
                    val movedTooMuch =
                        abs(event.x - voiceTouchDownX) > slop || abs(event.y - voiceTouchDownY) > slop
                    if (movedTooMuch) {
                        voiceButtonPressed = false
                        voiceButtonLongPressHandler.removeCallbacks(voiceLongPressRunnable)
                    }
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                val wasLongPress = longPressTriggered
                voiceButtonPressed = false
                longPressTriggered = false
                voiceButtonLongPressHandler.removeCallbacks(voiceLongPressRunnable)
                if (wasLongPress) {
                    stopAnyActiveRecording(send = true)
                } else if (!isAnyRecordingInProgress()) {
                    view.performClick()
                }
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                val wasLongPress = longPressTriggered
                voiceButtonPressed = false
                longPressTriggered = false
                voiceButtonLongPressHandler.removeCallbacks(voiceLongPressRunnable)
                if (wasLongPress) {
                    stopAnyActiveRecording(send = false)
                }
                return true
            }
        }
        return false
    }

    fun isAnyRecordingInProgress(): Boolean =
        isVoiceRecording || isVideoRecording || voiceRecorder != null || videoRecording != null

    fun toggleRecordMode() {
        if (isAnyRecordingInProgress()) return
        currentRecordMode = when (currentRecordMode) {
            RecordMode.VOICE -> RecordMode.VIDEO
            RecordMode.VIDEO -> RecordMode.VOICE
        }
        if (currentRecordMode == RecordMode.VOICE) {
            releaseVideoCapture()
        }
        updateRecordingUi(recording = false, elapsedSec = 0)
        val modeToast = if (currentRecordMode == RecordMode.VOICE) {
            "Режим: голосовое"
        } else {
            "Режим: видеосообщение"
        }
        Toast.makeText(activity, modeToast, Toast.LENGTH_SHORT).show()
    }

    fun switchVideoCamera() {
        if (currentRecordMode != RecordMode.VIDEO) return
        if (isVideoRecording || videoRecording != null) {
            pendingCameraSwitchAfterFinalize = true
            pendingVideoSendAfterStop = false
            videoRecording?.stop()
            return
        }
        if (!switchVideoCameraNow()) {
            ensureVideoCapture(
                onReady = { _ ->
                    if (!switchVideoCameraNow()) {
                        Toast.makeText(activity, "Не удалось переключить камеру", Toast.LENGTH_SHORT).show()
                    }
                },
                onError = {
                    Toast.makeText(activity, "Не удалось переключить камеру", Toast.LENGTH_SHORT).show()
                }
            )
        }
    }

    fun stopAnyActiveRecording(send: Boolean) {
        when {
            isVoiceRecording || voiceRecorder != null -> stopVoiceRecording(send)
            isVideoRecording || videoRecording != null -> stopVideoRecording(send)
        }
    }

    fun updateRecordingUi(recording: Boolean, elapsedSec: Int) {
        if (recording) {
            val label = if (isVideoRecording) {
                "Идет запись видео (${elapsedSec}s/${MAX_VIDEO_RECORD_DURATION_SEC}s)"
            } else {
                "Идет запись голосового"
            }
            binding.tvRecordingStatus.isVisible = true
            binding.tvRecordingStatus.text = label
            binding.btnVoice.text = "■"
            binding.videoRecordingContainer.isVisible = isVideoRecording
            binding.videoRecordingProgress.isVisible = isVideoRecording
            binding.videoRecordingProgress.setProgressFraction(
                elapsedSec.toFloat() / MAX_VIDEO_RECORD_DURATION_SEC.toFloat()
            )
            binding.btnSwitchVideoCamera.isVisible = isVideoRecording
            binding.btnSend.isEnabled = false
            binding.btnEmoji.isEnabled = false
            binding.btnAttach.isEnabled = false
            binding.etMessage.isEnabled = false
        } else {
            binding.tvRecordingStatus.isVisible = false
            binding.videoRecordingContainer.isVisible = false
            binding.videoRecordingProgress.isVisible = false
            binding.videoRecordingProgress.setProgressFraction(0f)
            binding.btnSwitchVideoCamera.isVisible = false
            binding.btnVoice.text = if (currentRecordMode == RecordMode.VOICE) {
                "\uD83C\uDFA4"
            } else {
                "\uD83C\uDFA5"
            }
            binding.btnSend.isEnabled = true
            binding.btnEmoji.isEnabled = true
            binding.btnAttach.isEnabled = true
            binding.etMessage.isEnabled = true
        }
        updateComposerActionState()
    }

    fun release() {
        recordingUiHandler.removeCallbacksAndMessages(null)
        voiceButtonLongPressHandler.removeCallbacksAndMessages(null)
        stopAnyActiveRecording(send = false)
        releaseVideoCapture()
    }

    private fun switchVideoCameraNow(): Boolean {
        val provider = cameraProvider ?: return false
        val preview = videoPreviewUseCase ?: return false
        val capture = videoCapture ?: return false
        val nextFacing = if (videoCameraFacing == CameraSelector.LENS_FACING_FRONT) {
            CameraSelector.LENS_FACING_BACK
        } else {
            CameraSelector.LENS_FACING_FRONT
        }
        val selector = CameraSelector.Builder().requireLensFacing(nextFacing).build()
        return runCatching {
            provider.unbindAll()
            provider.bindToLifecycle(activity, selector, preview, capture)
            videoCameraFacing = nextFacing
            true
        }.getOrElse {
            false
        }
    }

    private fun ensureAudioPermissionAndRecord() {
        val granted = ContextCompat.checkSelfPermission(
            activity,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            startVoiceRecordingInternal()
        } else {
            pendingPermissionRecordingType = RecordingType.VOICE
            requestAudioPermission()
        }
    }

    private fun ensureVideoPermissionsAndRecord() {
        val cameraGranted = ContextCompat.checkSelfPermission(
            activity,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
        val audioGranted = ContextCompat.checkSelfPermission(
            activity,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (cameraGranted && audioGranted) {
            startVideoRecordingInternal()
        } else {
            pendingPermissionRecordingType = RecordingType.VIDEO
            requestVideoPermissions()
        }
    }

    private fun startVoiceRecordingInternal() {
        if (isAnyRecordingInProgress() || !voiceButtonPressed) return
        hideEmojiPanel()

        val tempFile = File(activity.cacheDir, "voice_${System.currentTimeMillis()}.m4a")
        val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(activity)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }

        val result = runCatching {
            recorder.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(64_000)
                setAudioSamplingRate(22_050)
                setOutputFile(tempFile.absolutePath)
                prepare()
                start()
            }
        }

        result.onSuccess {
            voiceRecorder = recorder
            voiceRecordFile = tempFile
            recordingStartedAtMs = System.currentTimeMillis()
            isVoiceRecording = true
            recordingUiHandler.removeCallbacks(recordingTicker)
            recordingUiHandler.post(recordingTicker)
            updateRecordingUi(recording = true, elapsedSec = 0)
        }.onFailure {
            runCatching { recorder.release() }
            runCatching { tempFile.delete() }
            Toast.makeText(activity, "Не удалось начать запись голоса", Toast.LENGTH_SHORT).show()
            updateRecordingUi(recording = false, elapsedSec = 0)
        }
    }

    private fun stopVoiceRecording(send: Boolean) {
        val recorder = voiceRecorder
        val file = voiceRecordFile
        if (recorder == null || file == null) {
            isVoiceRecording = false
            voiceRecorder = null
            voiceRecordFile = null
            recordingStartedAtMs = 0L
            updateRecordingUi(recording = false, elapsedSec = 0)
            return
        }

        val elapsedSec = ((System.currentTimeMillis() - recordingStartedAtMs) / 1000L).toInt().coerceAtLeast(0)
        isVoiceRecording = false
        recordingUiHandler.removeCallbacks(recordingTicker)
        voiceRecorder = null
        voiceRecordFile = null
        recordingStartedAtMs = 0L

        runCatching { recorder.stop() }
        runCatching { recorder.release() }
        updateRecordingUi(recording = false, elapsedSec = 0)

        if (!send) {
            runCatching { file.delete() }
            return
        }

        if (!file.exists() || file.length() <= 0L) {
            runCatching { file.delete() }
            Toast.makeText(activity, "Не удалось сохранить голосовое сообщение", Toast.LENGTH_SHORT).show()
            return
        }

        sendVoice(file, elapsedSec.coerceAtMost(MAX_VOICE_RECORD_DURATION_SEC))
    }

    @SuppressLint("MissingPermission")
    private fun startVideoRecordingInternal() {
        if (isAnyRecordingInProgress() || !voiceButtonPressed) return
        hideEmojiPanel()
        binding.videoRecordingContainer.isVisible = true
        videoSegments.clear()
        startVideoSegment(resetTimer = true)
    }

    @SuppressLint("MissingPermission")
    private fun startVideoSegment(resetTimer: Boolean) {
        ensureVideoCapture(
            onReady = { capture ->
                val tempFile = File(activity.cacheDir, "video_${System.currentTimeMillis()}.mp4")
                val outputOptions = FileOutputOptions.Builder(tempFile).build()
                pendingVideoSendAfterStop = true
                videoRecordFile = tempFile

                videoRecording = capture.output
                    .prepareRecording(activity, outputOptions)
                    .withAudioEnabled()
                    .start(ContextCompat.getMainExecutor(activity)) { event ->
                        when (event) {
                            is VideoRecordEvent.Start -> {
                                if (resetTimer || recordingStartedAtMs <= 0L) {
                                    recordingStartedAtMs = System.currentTimeMillis()
                                }
                                isVideoRecording = true
                                recordingUiHandler.removeCallbacks(recordingTicker)
                                recordingUiHandler.post(recordingTicker)
                                val elapsedSec = ((System.currentTimeMillis() - recordingStartedAtMs) / 1000L)
                                    .toInt()
                                    .coerceAtLeast(0)
                                updateRecordingUi(recording = true, elapsedSec = elapsedSec)
                            }

                            is VideoRecordEvent.Finalize -> {
                                handleVideoFinalize(event)
                            }
                        }
                    }
            },
            onError = {
                binding.videoRecordingContainer.isVisible = false
                Toast.makeText(activity, "Не удалось запустить видеозапись", Toast.LENGTH_SHORT).show()
                updateRecordingUi(recording = false, elapsedSec = 0)
            }
        )
    }

    private fun stopVideoRecording(send: Boolean) {
        pendingVideoSendAfterStop = send
        val recording = videoRecording
        if (recording == null) {
            isVideoRecording = false
            if (!send) {
                runCatching { videoRecordFile?.delete() }
                videoSegments.forEach { segment -> runCatching { segment.delete() } }
                videoSegments.clear()
                videoRecordFile = null
            }
            recordingStartedAtMs = 0L
            updateRecordingUi(recording = false, elapsedSec = 0)
            if (pendingCameraSwitchAfterFinalize) {
                pendingCameraSwitchAfterFinalize = false
                switchVideoCameraNow()
            }
            return
        }
        recording.stop()
    }

    private fun handleVideoFinalize(event: VideoRecordEvent.Finalize) {
        val file = videoRecordFile
        val shouldSend = pendingVideoSendAfterStop
        val durationSec = ((System.currentTimeMillis() - recordingStartedAtMs) / 1000L).toInt().coerceAtLeast(0)
        val shouldSwitchCamera = pendingCameraSwitchAfterFinalize

        isVideoRecording = false
        recordingUiHandler.removeCallbacks(recordingTicker)
        videoRecording = null
        videoRecordFile = null

        if (shouldSwitchCamera) {
            pendingCameraSwitchAfterFinalize = false
            if (event.hasError() || file == null || !file.exists() || file.length() <= 0L) {
                runCatching { file?.delete() }
                deleteVideoSegments()
                recordingStartedAtMs = 0L
                updateRecordingUi(recording = false, elapsedSec = 0)
                Toast.makeText(activity, "Ошибка видеозаписи", Toast.LENGTH_SHORT).show()
                return
            }
            videoSegments += file
            if (!switchVideoCameraNow()) {
                deleteVideoSegments()
                recordingStartedAtMs = 0L
                updateRecordingUi(recording = false, elapsedSec = 0)
                Toast.makeText(activity, "Не удалось переключить камеру", Toast.LENGTH_SHORT).show()
                return
            }
            if (voiceButtonPressed) {
                startVideoSegment(resetTimer = false)
            } else {
                recordingStartedAtMs = 0L
                updateRecordingUi(recording = false, elapsedSec = 0)
            }
            return
        }

        recordingStartedAtMs = 0L
        updateRecordingUi(recording = false, elapsedSec = 0)

        if (event.hasError()) {
            runCatching { file?.delete() }
            deleteVideoSegments()
            Toast.makeText(activity, "Ошибка видеозаписи", Toast.LENGTH_SHORT).show()
            return
        }

        if (!shouldSend) {
            runCatching { file?.delete() }
            deleteVideoSegments()
            return
        }

        if (file == null || !file.exists() || file.length() <= 0L) {
            runCatching { file?.delete() }
            deleteVideoSegments()
            Toast.makeText(activity, "Не удалось сохранить видеосообщение", Toast.LENGTH_SHORT).show()
            return
        }

        videoSegments += file
        finalizeVideoSegmentsForSend(durationSec.coerceAtMost(MAX_VIDEO_RECORD_DURATION_SEC))
    }

    private fun finalizeVideoSegmentsForSend(durationSec: Int) {
        val segments = videoSegments.toList()
        videoSegments.clear()
        if (segments.isEmpty()) {
            Toast.makeText(activity, "Не удалось сохранить видеосообщение", Toast.LENGTH_SHORT).show()
            return
        }
        if (segments.size == 1) {
            sendVideo(segments.first(), durationSec)
            return
        }
        activity.lifecycleScope.launch {
            val mergedFile = withContext(Dispatchers.IO) {
                val output = File(activity.cacheDir, "video_merged_${System.currentTimeMillis()}.mp4")
                runCatching {
                    concatMp4Segments(segments, output)
                    output.takeIf { it.exists() && it.length() > 0L }
                }.onFailure {
                    output.delete()
                }.getOrNull()
            }
            segments.forEach { segment -> runCatching { segment.delete() } }
            if (mergedFile == null) {
                Toast.makeText(activity, "Не удалось собрать видеосообщение", Toast.LENGTH_SHORT).show()
                return@launch
            }
            sendVideo(mergedFile, durationSec)
        }
    }

    private fun deleteVideoSegments() {
        videoSegments.forEach { segment -> runCatching { segment.delete() } }
        videoSegments.clear()
    }

    private fun concatMp4Segments(segments: List<File>, output: File) {
        require(segments.isNotEmpty())
        val firstExtractor = MediaExtractor()
        val trackIndexMap = mutableMapOf<Int, Int>()
        try {
            firstExtractor.setDataSource(segments.first().absolutePath)
            val muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            try {
                for (trackIndex in 0 until firstExtractor.trackCount) {
                    val format = firstExtractor.getTrackFormat(trackIndex)
                    trackIndexMap[trackIndex] = muxer.addTrack(format)
                }
                muxer.start()
                val buffer = ByteBuffer.allocate(1024 * 1024)
                val info = MediaCodec.BufferInfo()
                val trackOffsetsUs = LongArray(firstExtractor.trackCount)
                segments.forEach { segment ->
                    appendSegmentToMuxer(segment, muxer, trackIndexMap, trackOffsetsUs, buffer, info)
                }
                muxer.stop()
            } finally {
                runCatching { muxer.release() }
            }
        } finally {
            firstExtractor.release()
        }
    }

    private fun appendSegmentToMuxer(
        segment: File,
        muxer: MediaMuxer,
        trackIndexMap: Map<Int, Int>,
        trackOffsetsUs: LongArray,
        buffer: ByteBuffer,
        info: MediaCodec.BufferInfo
    ) {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(segment.absolutePath)
            for (trackIndex in 0 until extractor.trackCount) {
                val muxerTrack = trackIndexMap[trackIndex] ?: continue
                extractor.selectTrack(trackIndex)
                var lastSampleTimeUs = 0L
                while (true) {
                    val sampleTrack = extractor.sampleTrackIndex
                    if (sampleTrack != trackIndex) break
                    buffer.clear()
                    val sampleSize = extractor.readSampleData(buffer, 0)
                    if (sampleSize < 0) break
                    info.set(
                        0,
                        sampleSize,
                        extractor.sampleTime.coerceAtLeast(0L) + trackOffsetsUs[trackIndex],
                        extractor.sampleFlags
                    )
                    muxer.writeSampleData(muxerTrack, buffer, info)
                    lastSampleTimeUs = info.presentationTimeUs
                    extractor.advance()
                }
                trackOffsetsUs[trackIndex] = lastSampleTimeUs + 1_000L
                extractor.unselectTrack(trackIndex)
            }
        } finally {
            extractor.release()
        }
    }

    private fun ensureVideoCapture(
        onReady: (VideoCapture<Recorder>) -> Unit,
        onError: (Throwable) -> Unit
    ) {
        val existingCapture = videoCapture
        if (existingCapture != null) {
            onReady(existingCapture)
            return
        }

        val future = cameraProviderFuture ?: ProcessCameraProvider.getInstance(activity).also {
            cameraProviderFuture = it
        }

        future.addListener(
            {
                val provider = runCatching { future.get() }.getOrElse { throwable ->
                    onError(throwable)
                    return@addListener
                }
                runCatching {
                    val preview = videoPreviewUseCase ?: Preview.Builder().build().apply {
                        setSurfaceProvider(binding.videoRecordingPreview.surfaceProvider)
                    }
                    val recorder = Recorder.Builder()
                        .setQualitySelector(QualitySelector.from(Quality.SD))
                        .build()
                    val capture = VideoCapture.withOutput(recorder)
                    bindVideoUseCases(provider, preview, capture)
                    cameraProvider = provider
                    videoPreviewUseCase = preview
                    videoCapture = capture
                    capture
                }.onSuccess(onReady).onFailure(onError)
            },
            ContextCompat.getMainExecutor(activity)
        )
    }

    private fun bindVideoUseCases(
        provider: ProcessCameraProvider,
        preview: Preview,
        capture: VideoCapture<Recorder>
    ) {
        val preferredSelector = CameraSelector.Builder()
            .requireLensFacing(videoCameraFacing)
            .build()
        try {
            provider.unbindAll()
            provider.bindToLifecycle(activity, preferredSelector, preview, capture)
            return
        } catch (_: Throwable) {
        }

        val fallbackFacing = if (videoCameraFacing == CameraSelector.LENS_FACING_FRONT) {
            CameraSelector.LENS_FACING_BACK
        } else {
            CameraSelector.LENS_FACING_FRONT
        }
        val fallbackSelector = CameraSelector.Builder()
            .requireLensFacing(fallbackFacing)
            .build()
        provider.unbindAll()
        provider.bindToLifecycle(activity, fallbackSelector, preview, capture)
        videoCameraFacing = fallbackFacing
    }

    private fun releaseVideoCapture() {
        videoRecording?.stop()
        videoRecording = null
        pendingCameraSwitchAfterFinalize = false
        runCatching { videoRecordFile?.delete() }
        videoRecordFile = null
        deleteVideoSegments()
        runCatching { cameraProvider?.unbindAll() }
        videoPreviewUseCase = null
        videoCapture = null
        binding.videoRecordingContainer.isVisible = false
        binding.videoRecordingProgress.isVisible = false
        binding.videoRecordingProgress.setProgressFraction(0f)
    }

    private enum class RecordMode {
        VOICE,
        VIDEO
    }

    private enum class RecordingType {
        VOICE,
        VIDEO
    }

    private companion object {
        private const val MAX_VOICE_RECORD_DURATION_SEC = 600
        private const val MAX_VIDEO_RECORD_DURATION_SEC = 60
    }
}
