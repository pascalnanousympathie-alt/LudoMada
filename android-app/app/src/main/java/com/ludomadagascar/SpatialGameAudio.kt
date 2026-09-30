package com.ludomadagascar

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.audiofx.PresetReverb
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Offline procedural game SFX with stereo spatialisation and optional room reverb.
 * No network or large sound assets are required. Audio is always best-effort.
 */
class SpatialGameAudio {
    private val sampleRate = 44100
    private var track: AudioTrack? = null
    private var reverb: PresetReverb? = null

    init {
        runCatching {
            track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .build()
                )
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(sampleRate / 2 * 4)
                .build()
            track?.play()
            reverb = runCatching {
                PresetReverb(0, track!!.audioSessionId).apply {
                    preset = PresetReverb.PRESET_SMALLROOM
                    enabled = true
                    setStrength(700.toShort())
                }
            }.getOrNull()
        }.onFailure {
            track = null
            reverb = null
        }
    }

    fun play(pan: Float, intensity: Float = 0.8f, frequency: Double = 620.0, distance: Float = 0f) {
        val audio = track ?: return
        val p = pan.coerceIn(-1f, 1f)
        val distanceNorm = distance.coerceIn(0f, 1f)
        val gain = (intensity.coerceIn(0f, 1f) * (1f - 0.48f * distanceNorm * distanceNorm)).coerceIn(0f, 1f)
        Thread {
            val frames = (sampleRate * 0.12f).toInt().coerceAtLeast(1)
            val pcm = ShortArray(frames * 2)
            for (i in 0 until frames) {
                val t = i.toDouble() / sampleRate.toDouble()
                val env = ((1.0 - i.toDouble() / frames.toDouble()).pow(2.2)).toFloat()
                val tone = (sin(2.0 * PI * frequency * t) * env * gain * 0.28 * Short.MAX_VALUE).toInt()
                val left = (tone * (1f - max(0f, p))).coerceIn(-32768f, 32767f).toInt().toShort()
                val right = (tone * (1f + min(0f, p))).coerceIn(-32768f, 32767f).toInt().toShort()
                pcm[i * 2] = left
                pcm[i * 2 + 1] = right
            }
            runCatching { audio.setStereoVolume(1f, 1f); audio.write(pcm, 0, pcm.size) }
        }.start()
    }

    fun release() {
        runCatching { reverb?.release() }
        reverb = null
        val audio = track
        track = null
        runCatching { audio?.stop() }
        runCatching { audio?.release() }
    }
}
