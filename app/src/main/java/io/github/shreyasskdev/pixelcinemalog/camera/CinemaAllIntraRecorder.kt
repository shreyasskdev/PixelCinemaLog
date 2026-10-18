// File: CinemaAllIntraRecorder.kt
// Path: app/src/main/java/io/github/shreyasskdev/pixelcinemalog/camera/CinemaAllIntraRecorder.kt
package io.github.shreyasskdev.pixelcinemalog.camera

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.util.Log
import android.view.Surface
import java.io.FileDescriptor
import java.nio.ByteBuffer
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class CinemaAllIntraRecorder(
    private val fd: FileDescriptor,
    private val width: Int,
    private val height: Int,
    private val fps: Int,
    private val use10BitMain10: Boolean,
    private val persistentSurface: Surface? = null
) {
    private class MuxPacket(val isVideo: Boolean, val data: ByteBuffer, val info: MediaCodec.BufferInfo)

    private var videoCodec: MediaCodec? = null
    private var audioCodec: MediaCodec? = null
    private var audioRecord: AudioRecord? = null
    private var muxer: MediaMuxer? = null

    var inputSurface: Surface? = null
        private set

    private val isRunning = AtomicBoolean(false)
    private val packetQueue = LinkedBlockingQueue<MuxPacket>(90)

    private var videoTrackIndex = -1
    private var audioTrackIndex = -1
    private var muxerStarted = false

    private var videoThread: Thread? = null
    private var audioThread: Thread? = null
    private var muxerWriterThread: Thread? = null

    @SuppressLint("MissingPermission")
    fun prepareAndStart() {
        val mime = MediaFormat.MIMETYPE_VIDEO_HEVC
        val targetBitrate = if (width >= 3840) 140_000_000 else 80_000_000

        val videoFormat = MediaFormat.createVideoFormat(mime, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, targetBitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 0)
            setInteger(MediaFormat.KEY_OPERATING_RATE, fps)
            setInteger(MediaFormat.KEY_PRIORITY, 0)
            if (use10BitMain10) {
                setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10)
                setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT2020)
                setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_HLG)
            }
        }
        videoCodec = MediaCodec.createEncoderByType(mime).apply {
            configure(videoFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            if (persistentSurface != null) { setInputSurface(persistentSurface); inputSurface = persistentSurface }
            else { inputSurface = createInputSurface() }
            start()
        }

        val audioFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, 48_000, 2).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, 320_000)
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16_384)
        }
        audioCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
            configure(audioFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE); start()
        }

        val minBufSize = AudioRecord.getMinBufferSize(48_000, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT).coerceAtLeast(16_384)
        audioRecord = AudioRecord(MediaRecorder.AudioSource.MIC, 48_000, AudioFormat.CHANNEL_IN_STEREO,
            AudioFormat.ENCODING_PCM_16BIT, minBufSize * 2).apply {
            if (state == AudioRecord.STATE_INITIALIZED) startRecording()
        }

        muxer = MediaMuxer(fd, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        isRunning.set(true)
        muxerWriterThread = thread(name = "AsyncMuxerDiskWriter") { runMuxerDiskLoop() }
        videoThread = thread(name = "AllIntraVideoDrain") { drainVideoEncoder() }
        audioThread = thread(name = "AllIntraAudioPump") { pumpAndDrainAudio() }
    }

    fun stopAndRelease() {
        if (!isRunning.getAndSet(false)) return
        try { videoCodec?.signalEndOfInputStream() } catch (_: Exception) {}
        try { videoThread?.join(1200); audioThread?.join(1200); muxerWriterThread?.join(2500) } catch (_: Exception) {}
        try { audioRecord?.stop(); audioRecord?.release() } catch (_: Exception) {}
        audioRecord = null
        try { videoCodec?.stop(); videoCodec?.release() } catch (_: Exception) {}
        videoCodec = null
        try { audioCodec?.stop(); audioCodec?.release() } catch (_: Exception) {}
        audioCodec = null
        try { if (muxerStarted) muxer?.stop(); muxer?.release() } catch (e: Exception) {
            Log.e("CinemaAllIntra", "Error stopping muxer", e)
        }
        muxer = null
        muxerStarted = false
        if (persistentSurface == null) { try { inputSurface?.release() } catch (_: Exception) {} }
        inputSurface = null
    }

    private fun drainVideoEncoder() {
        val codec = videoCodec ?: return
        val bufferInfo = MediaCodec.BufferInfo()
        while (isRunning.get()) {
            val outIndex = try { codec.dequeueOutputBuffer(bufferInfo, 10_000L) } catch (_: Exception) { break }
            when {
                outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> continue
                outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> synchronized(this) {
                    if (!muxerStarted) {
                        videoTrackIndex = muxer?.addTrack(codec.outputFormat) ?: -1
                        if (videoTrackIndex >= 0 && audioTrackIndex >= 0) { muxer?.start(); muxerStarted = true }
                    }
                }
                outIndex >= 0 -> {
                    val encodedData = codec.getOutputBuffer(outIndex)
                    if (encodedData != null && bufferInfo.size > 0 &&
                        (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                        encodedData.position(bufferInfo.offset)
                        encodedData.limit(bufferInfo.offset + bufferInfo.size)
                        val copy = ByteBuffer.allocateDirect(bufferInfo.size)
                        copy.put(encodedData); copy.flip()
                        val infoCopy = MediaCodec.BufferInfo().apply {
                            set(0, bufferInfo.size, bufferInfo.presentationTimeUs, bufferInfo.flags)
                        }
                        packetQueue.offer(MuxPacket(true, copy, infoCopy))
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break
                }
            }
        }
    }

    private fun pumpAndDrainAudio() {
        val codec = audioCodec ?: return
        val record = audioRecord ?: return
        val bufferInfo = MediaCodec.BufferInfo()
        val pcmBuf = ByteArray(4096)
        while (isRunning.get()) {
            if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                val readBytes = record.read(pcmBuf, 0, pcmBuf.size)
                if (readBytes > 0) {
                    val inIdx = try { codec.dequeueInputBuffer(5_000L) } catch (_: Exception) { -1 }
                    if (inIdx >= 0) {
                        val inBuf = codec.getInputBuffer(inIdx)
                        inBuf?.clear(); inBuf?.put(pcmBuf, 0, readBytes)
                        codec.queueInputBuffer(inIdx, 0, readBytes, System.nanoTime() / 1000L, 0)
                    }
                } else Thread.sleep(5)
            } else Thread.sleep(10)

            var outIdx = try { codec.dequeueOutputBuffer(bufferInfo, 0L) } catch (_: Exception) { -1 }
            while (outIdx >= 0 || outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    synchronized(this) {
                        if (!muxerStarted) {
                            audioTrackIndex = muxer?.addTrack(codec.outputFormat) ?: -1
                            if (videoTrackIndex >= 0 && audioTrackIndex >= 0) { muxer?.start(); muxerStarted = true }
                        }
                    }
                } else if (outIdx >= 0) {
                    val encodedData = codec.getOutputBuffer(outIdx)
                    if (encodedData != null && bufferInfo.size > 0 &&
                        (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                        encodedData.position(bufferInfo.offset)
                        encodedData.limit(bufferInfo.offset + bufferInfo.size)
                        val copy = ByteBuffer.allocateDirect(bufferInfo.size)
                        copy.put(encodedData); copy.flip()
                        val infoCopy = MediaCodec.BufferInfo().apply {
                            set(0, bufferInfo.size, bufferInfo.presentationTimeUs, bufferInfo.flags)
                        }
                        packetQueue.offer(MuxPacket(false, copy, infoCopy))
                    }
                    codec.releaseOutputBuffer(outIdx, false)
                }
                outIdx = try { codec.dequeueOutputBuffer(bufferInfo, 0L) } catch (_: Exception) { -1 }
            }
        }
    }

    private fun runMuxerDiskLoop() {
        var waitedVideoPackets = 0
        while (isRunning.get() || packetQueue.isNotEmpty()) {
            val pkt = packetQueue.poll(15, TimeUnit.MILLISECONDS) ?: continue
            synchronized(this) {
                if (!muxerStarted && videoTrackIndex >= 0) {
                    waitedVideoPackets++
                    if (audioTrackIndex >= 0 || waitedVideoPackets > 8) { muxer?.start(); muxerStarted = true }
                }
            }
            if (muxerStarted) {
                try {
                    val track = if (pkt.isVideo) videoTrackIndex else audioTrackIndex
                    if (track >= 0) muxer?.writeSampleData(track, pkt.data, pkt.info)
                } catch (_: Exception) {}
            }
        }
    }
}