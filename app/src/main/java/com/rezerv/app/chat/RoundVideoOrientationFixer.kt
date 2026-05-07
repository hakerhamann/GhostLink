package com.rezerv.app.chat

import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.EGLExt
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.util.Log
import android.view.Surface
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

internal object RoundVideoOrientationFixer {
    fun pixelRotateBackSegment180(input: File, output: File) {
        val tracks = findTracks(input)
        require(tracks.videoIndex >= 0) { "No video track" }
        val videoFormat = tracks.videoFormat ?: error("No video format")
        val width = videoFormat.getInteger(MediaFormat.KEY_WIDTH)
        val height = videoFormat.getInteger(MediaFormat.KEY_HEIGHT)
        val inputRotation = readRotation(input)
        val (outWidth, outHeight) = if (inputRotation == 90 || inputRotation == 270) {
            height to width
        } else {
            width to height
        }
        val durationUs = if (videoFormat.containsKey(MediaFormat.KEY_DURATION)) {
            videoFormat.getLong(MediaFormat.KEY_DURATION)
        } else {
            0L
        }
        val bitrate = if (videoFormat.containsKey(MediaFormat.KEY_BIT_RATE)) {
            videoFormat.getInteger(MediaFormat.KEY_BIT_RATE).coerceAtLeast(DEFAULT_BITRATE)
        } else {
            DEFAULT_BITRATE
        }
        val mime = videoFormat.getString(MediaFormat.KEY_MIME).orEmpty()
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        var encoder: MediaCodec? = null
        var inputSurface: Surface? = null
        var outputSurface: CodecInputSurface? = null
        var decoderSurface: DecoderOutputSurface? = null
        var muxer: MediaMuxer? = null
        try {
            extractor.setDataSource(input.absolutePath)
            extractor.selectTrack(tracks.videoIndex)
            val encodeFormat = MediaFormat.createVideoFormat(MIME_AVC, outWidth, outHeight).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, 30)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }
            encoder = MediaCodec.createEncoderByType(MIME_AVC).apply {
                configure(encodeFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            }
            inputSurface = encoder.createInputSurface()
            outputSurface = CodecInputSurface(inputSurface).apply { makeCurrent() }
            encoder.start()

            decoderSurface = DecoderOutputSurface(outWidth, outHeight, inputRotation)
            decoder = MediaCodec.createDecoderByType(mime).apply {
                configure(videoFormat, decoderSurface.surface, null, 0)
                start()
            }
            muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer.setOrientationHint(0)
            var muxerStarted = false
            var videoMuxTrack = -1
            val audioMuxTrack = tracks.audioFormat?.let { muxer.addTrack(it) } ?: -1
            val decoderInfo = MediaCodec.BufferInfo()
            val encoderInfo = MediaCodec.BufferInfo()
            var sawInputEnd = false
            var sawDecoderEnd = false
            var sawEncoderEnd = false

            while (!sawEncoderEnd) {
                if (!sawInputEnd) {
                    val inputIndex = decoder.dequeueInputBuffer(TIMEOUT_US)
                    if (inputIndex >= 0) {
                        val inputBuffer = decoder.getInputBuffer(inputIndex) ?: error("decoder input null")
                        val sampleSize = extractor.readSampleData(inputBuffer, 0)
                        if (sampleSize < 0) {
                            decoder.queueInputBuffer(inputIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            sawInputEnd = true
                        } else {
                            decoder.queueInputBuffer(
                                inputIndex,
                                0,
                                sampleSize,
                                extractor.sampleTime.coerceAtLeast(0L),
                                extractor.sampleFlags
                            )
                            extractor.advance()
                        }
                    }
                }

                var decoderOutputAvailable = true
                while (decoderOutputAvailable && !sawDecoderEnd) {
                    when (val outputIndex = decoder.dequeueOutputBuffer(decoderInfo, TIMEOUT_US)) {
                        MediaCodec.INFO_TRY_AGAIN_LATER -> decoderOutputAvailable = false
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Unit
                        else -> if (outputIndex >= 0) {
                            val render = decoderInfo.size > 0
                            decoder.releaseOutputBuffer(outputIndex, render)
                            if (render) {
                                val stMatrix = decoderSurface.awaitFrame()
                                decoderSurface.draw(stMatrix)
                                outputSurface.setPresentationTime(decoderInfo.presentationTimeUs * 1000L)
                                outputSurface.swapBuffers()
                            }
                            if ((decoderInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                                encoder.signalEndOfInputStream()
                                sawDecoderEnd = true
                            }
                        }
                    }
                }

                var encoderOutputAvailable = true
                while (encoderOutputAvailable && !sawEncoderEnd) {
                    when (val outputIndex = encoder.dequeueOutputBuffer(encoderInfo, TIMEOUT_US)) {
                        MediaCodec.INFO_TRY_AGAIN_LATER -> encoderOutputAvailable = false
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            check(!muxerStarted) { "encoder format changed twice" }
                            val outFormat = encoder.outputFormat
                            if (durationUs > 0L) outFormat.setLong(MediaFormat.KEY_DURATION, durationUs)
                            videoMuxTrack = muxer.addTrack(outFormat)
                            muxer.start()
                            muxerStarted = true
                        }
                        else -> if (outputIndex >= 0) {
                            val encoded = encoder.getOutputBuffer(outputIndex) ?: error("encoder output null")
                            if ((encoderInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                                encoderInfo.size = 0
                            }
                            if (encoderInfo.size > 0 && muxerStarted) {
                                encoded.position(encoderInfo.offset)
                                encoded.limit(encoderInfo.offset + encoderInfo.size)
                                muxer.writeSampleData(videoMuxTrack, encoded, encoderInfo)
                            }
                            sawEncoderEnd = (encoderInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                            encoder.releaseOutputBuffer(outputIndex, false)
                        }
                    }
                }
            }
            check(muxerStarted) { "muxer not started" }
            if (tracks.audioIndex >= 0 && audioMuxTrack >= 0) copyAudio(input, tracks.audioIndex, muxer, audioMuxTrack)
            Log.i(
                "VideoUpload",
                "orientation correction inputWidth=$width inputHeight=$height inputRotation=$inputRotation outputWidth=$outWidth outputHeight=$outHeight outputRotation=0 fixStrategy=texture_normalize frameWidth=$outWidth frameHeight=$outHeight"
            )
        } finally {
            runCatching { extractor.release() }
            runCatching { decoder?.stop() }
            runCatching { decoder?.release() }
            runCatching { encoder?.stop() }
            runCatching { encoder?.release() }
            runCatching { decoderSurface?.release() }
            runCatching { outputSurface?.release() }
            runCatching { inputSurface?.release() }
            runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
        }
    }

    private fun findTracks(file: File): Tracks {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            var videoIndex = -1
            var audioIndex = -1
            var videoFormat: MediaFormat? = null
            var audioFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
                when {
                    mime.startsWith("video/") && videoIndex < 0 -> {
                        videoIndex = i
                        videoFormat = format
                    }
                    mime.startsWith("audio/") && audioIndex < 0 -> {
                        audioIndex = i
                        audioFormat = format
                    }
                }
            }
            return Tracks(videoIndex, audioIndex, videoFormat, audioFormat)
        } finally {
            extractor.release()
        }
    }

    private fun copyAudio(input: File, sourceTrack: Int, muxer: MediaMuxer, muxTrack: Int) {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(input.absolutePath)
            extractor.selectTrack(sourceTrack)
            val buffer = ByteBuffer.allocate(1024 * 1024)
            val info = MediaCodec.BufferInfo()
            while (true) {
                buffer.clear()
                val sampleSize = extractor.readSampleData(buffer, 0)
                if (sampleSize < 0) break
                info.set(0, sampleSize, extractor.sampleTime.coerceAtLeast(0L), extractor.sampleFlags)
                muxer.writeSampleData(muxTrack, buffer, info)
                extractor.advance()
            }
        } finally {
            extractor.release()
        }
    }

    private class CodecInputSurface(private val surface: Surface) {
        private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
        private var context: EGLContext = EGL14.EGL_NO_CONTEXT
        private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE

        init {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            EGL14.eglInitialize(display, null, 0, null, 0)
            val configs = arrayOfNulls<EGLConfig>(1)
            val numConfigs = IntArray(1)
            EGL14.eglChooseConfig(display, EGL_ATTRIBS, 0, configs, 0, configs.size, numConfigs, 0)
            context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT, EGL_CONTEXT_ATTRIBS, 0)
            eglSurface = EGL14.eglCreateWindowSurface(display, configs[0], surface, EGL_NONE_ATTRIBS, 0)
        }

        fun makeCurrent() {
            EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context)
        }

        fun swapBuffers() {
            EGL14.eglSwapBuffers(display, eglSurface)
        }

        fun setPresentationTime(nsecs: Long) {
        EGLExt.eglPresentationTimeANDROID(display, eglSurface, nsecs)
        }

        fun release() {
            EGL14.eglDestroySurface(display, eglSurface)
            EGL14.eglDestroyContext(display, context)
            EGL14.eglReleaseThread()
            EGL14.eglTerminate(display)
        }
    }

    private class DecoderOutputSurface(width: Int, height: Int, inputRotation: Int) : SurfaceTexture.OnFrameAvailableListener {
        private val frameSyncObject = Object()
        private var frameAvailable = false
        private val textureId = createTexture()
        private val surfaceTexture = SurfaceTexture(textureId)
        val surface = Surface(surfaceTexture)
        private val drawer = TextureDrawer(width, height, textureId, inputRotation)

        init {
            surfaceTexture.setOnFrameAvailableListener(this)
        }

        override fun onFrameAvailable(surfaceTexture: SurfaceTexture) {
            synchronized(frameSyncObject) {
                frameAvailable = true
                frameSyncObject.notifyAll()
            }
        }

        fun awaitFrame(): FloatArray {
            synchronized(frameSyncObject) {
                while (!frameAvailable) frameSyncObject.wait(2500)
                frameAvailable = false
            }
            surfaceTexture.updateTexImage()
            val stMatrix = FloatArray(16)
            surfaceTexture.getTransformMatrix(stMatrix)
            return stMatrix
        }

        fun draw(stMatrix: FloatArray) {
            drawer.draw(stMatrix)
        }

        fun release() {
            drawer.release()
            surface.release()
            surfaceTexture.release()
        }
    }

    private class TextureDrawer(
        private val width: Int,
        private val height: Int,
        private val textureId: Int,
        inputRotation: Int
    ) {
        private val vertexBuffer = floatBuffer(
            -1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f
        )
        private val texBuffer = textureBufferFor(inputRotation)
        private val program = createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        private val positionLoc = GLES20.glGetAttribLocation(program, "aPosition")
        private val texCoordLoc = GLES20.glGetAttribLocation(program, "aTexCoord")
        private val stMatrixLoc = GLES20.glGetUniformLocation(program, "uSTMatrix")

        fun draw(stMatrix: FloatArray) {
            GLES20.glViewport(0, 0, width, height)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            GLES20.glUseProgram(program)
            GLES20.glUniformMatrix4fv(stMatrixLoc, 1, false, stMatrix, 0)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
            GLES20.glEnableVertexAttribArray(positionLoc)
            GLES20.glVertexAttribPointer(positionLoc, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer)
            GLES20.glEnableVertexAttribArray(texCoordLoc)
            GLES20.glVertexAttribPointer(texCoordLoc, 2, GLES20.GL_FLOAT, false, 0, texBuffer)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GLES20.glDisableVertexAttribArray(positionLoc)
            GLES20.glDisableVertexAttribArray(texCoordLoc)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)
        }

        fun release() {
            GLES20.glDeleteProgram(program)
        }
    }

    private data class Tracks(
        val videoIndex: Int,
        val audioIndex: Int,
        val videoFormat: MediaFormat?,
        val audioFormat: MediaFormat?
    )

    private fun readRotation(file: File): Int {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            for (trackIndex in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(trackIndex)
                val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("video/") && format.containsKey(MediaFormat.KEY_ROTATION)) {
                    return format.getInteger(MediaFormat.KEY_ROTATION)
                }
            }
        } catch (_: Throwable) {
        } finally {
            extractor.release()
        }
        return 0
    }

    private fun createTexture(): Int {
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textures[0])
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        return textures[0]
    }

    private fun floatBuffer(vararg values: Float): FloatBuffer {
        return ByteBuffer.allocateDirect(values.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
                put(values)
                position(0)
            }
    }

    private fun textureBufferFor(inputRotation: Int): FloatBuffer {
        return when (inputRotation) {
            90 -> floatBuffer(0f, 1f, 0f, 0f, 1f, 1f, 1f, 0f)
            180 -> floatBuffer(1f, 0f, 0f, 0f, 1f, 1f, 0f, 1f)
            270 -> floatBuffer(1f, 0f, 1f, 1f, 0f, 0f, 0f, 1f)
            else -> floatBuffer(1f, 1f, 0f, 1f, 1f, 0f, 0f, 0f) // 0°
        }
    }

    private fun createProgram(vertex: String, fragment: String): Int {
        val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertex)
        val fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragment)
        return GLES20.glCreateProgram().also { program ->
            GLES20.glAttachShader(program, vertexShader)
            GLES20.glAttachShader(program, fragmentShader)
            GLES20.glLinkProgram(program)
            GLES20.glDeleteShader(vertexShader)
            GLES20.glDeleteShader(fragmentShader)
        }
    }

    private fun loadShader(type: Int, source: String): Int {
        return GLES20.glCreateShader(type).also { shader ->
            GLES20.glShaderSource(shader, source)
            GLES20.glCompileShader(shader)
        }
    }

    private const val MIME_AVC = "video/avc"
    private const val DEFAULT_BITRATE = 800_000
    private const val TIMEOUT_US = 10_000L
    private val EGL_ATTRIBS = intArrayOf(
        EGL14.EGL_RED_SIZE, 8,
        EGL14.EGL_GREEN_SIZE, 8,
        EGL14.EGL_BLUE_SIZE, 8,
        EGL14.EGL_ALPHA_SIZE, 8,
        EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
        EGLExt.EGL_RECORDABLE_ANDROID, 1,
        EGL14.EGL_NONE
    )
    private val EGL_CONTEXT_ATTRIBS = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
    private val EGL_NONE_ATTRIBS = intArrayOf(EGL14.EGL_NONE)
    private const val VERTEX_SHADER = """
        attribute vec4 aPosition;
        attribute vec2 aTexCoord;
        uniform mat4 uSTMatrix;
        varying vec2 vTexCoord;
        void main() {
            gl_Position = aPosition;
            vTexCoord = (uSTMatrix * vec4(aTexCoord, 0.0, 1.0)).xy;
        }
    """
    private const val FRAGMENT_SHADER = """
        #extension GL_OES_EGL_image_external : require
        precision mediump float;
        varying vec2 vTexCoord;
        uniform samplerExternalOES sTexture;
        void main() {
            gl_FragColor = texture2D(sTexture, vTexCoord);
        }
    """
}
