package com.example.voltprobe

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kotlin.concurrent.thread
import kotlin.math.log10
import kotlin.math.sqrt

class MainActivity : Activity() {
    private lateinit var meter: TextView
    private lateinit var out: TextView
    private val sb = StringBuilder()
    @Volatile private var running = false
    private val rate = 48000
    private var pending: (() -> Unit)? = null
    private val am by lazy { getSystemService(Context.AUDIO_SERVICE) as AudioManager }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 48, 24, 24)
        }
        fun addBtn(t: String, f: () -> Unit) {
            root.addView(Button(this).apply { text = t; setOnClickListener { f() } })
        }
        addBtn("1. List audio devices") { needMic { listDevices() } }
        addBtn("2. Probe channel counts") { needMic { probe() } }
        addBtn("3. Live meters (start/stop)") { needMic { toggleMeters() } }
        addBtn("Copy report") { copy() }
        meter = TextView(this).apply { typeface = Typeface.MONOSPACE; textSize = 13f }
        out = TextView(this).apply { typeface = Typeface.MONOSPACE; textSize = 11f }
        root.addView(meter)
        root.addView(
            ScrollView(this).apply { addView(out) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        )
        setContentView(root)
    }

    private fun needMic(f: () -> Unit) {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            f()
        } else {
            pending = f
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            pending?.invoke()
        } else {
            log("Mic permission denied")
        }
        pending = null
    }

    @Synchronized
    private fun log(s: String) {
        sb.append(s).append('\n')
        val t = sb.toString()
        runOnUiThread { out.text = t }
    }

    private fun listDevices() {
        log("== DEVICES (0 values = any) ==")
        val groups = listOf("IN" to AudioManager.GET_DEVICES_INPUTS, "OUT" to AudioManager.GET_DEVICES_OUTPUTS)
        for ((label, flag) in groups) {
            for (d in am.getDevices(flag)) {
                log(
                    "$label id=${d.id} type=${d.type} ${d.productName} " +
                        "ch=${d.channelCounts.toList()} idxMasks=${d.channelIndexMasks.toList()} " +
                        "masks=${d.channelMasks.toList()} rates=${d.sampleRates.toList()} enc=${d.encodings.toList()}"
                )
            }
        }
        log("")
    }

    private fun usbIn(): AudioDeviceInfo? =
        am.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull {
            it.type == AudioDeviceInfo.TYPE_USB_DEVICE || it.type == AudioDeviceInfo.TYPE_USB_HEADSET
        }

    private fun open(dev: AudioDeviceInfo?, n: Int): AudioRecord? = try {
        val fmt = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(rate)
            .setChannelIndexMask((1 shl n) - 1)
            .build()
        val r = AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.MIC)
            .setAudioFormat(fmt)
            .setBufferSizeInBytes(rate * n)
            .build()
        if (r.state != AudioRecord.STATE_INITIALIZED) {
            r.release()
            null
        } else {
            if (dev != null) r.setPreferredDevice(dev)
            r
        }
    } catch (e: Exception) {
        log("open($n) error: ${e.message}")
        null
    }

    private fun probe() {
        thread {
            log("== PROBE ==")
            val dev = usbIn()
            log("USB input: ${dev?.productName ?: "NONE FOUND"}")
            for (n in listOf(1, 2, 3, 4, 6, 8)) {
                val r = open(dev, n)
                if (r == null) {
                    log("$n ch: cannot open")
                    continue
                }
                try {
                    r.startRecording()
                    val buf = ShortArray(rate / 10 * n)
                    val got = r.read(buf, 0, buf.size)
                    log("$n ch: OK channelCount=${r.channelCount} read=$got routed=${r.routedDevice?.productName}")
                } catch (e: Exception) {
                    log("$n ch: error ${e.message}")
                } finally {
                    r.release()
                }
            }
            log("")
        }
    }

    private fun toggleMeters() {
        if (running) {
            running = false
            return
        }
        running = true
        thread {
            val dev = usbIn()
            var n = 0
            var r: AudioRecord? = null
            for (c in listOf(4, 2, 1)) {
                r = open(dev, c)
                if (r != null) {
                    n = c
                    break
                }
            }
            if (r == null) {
                log("meters: could not open any channel count")
                running = false
                return@thread
            }
            log("meters: $n ch, routed=${r.routedDevice?.productName}")
            r.startRecording()
            val buf = ShortArray(rate / 20 * n)
            while (running) {
                val got = r.read(buf, 0, buf.size)
                if (got <= 0) break
                val f = got / n
                val txt = StringBuilder()
                for (c in 0 until n) {
                    var s = 0.0
                    for (i in 0 until f) {
                        val v = buf[i * n + c].toDouble()
                        s += v * v
                    }
                    val db = 20 * log10(sqrt(s / f) / 32768.0 + 1e-9)
                    val bars = (((db + 80) / 80).coerceIn(0.0, 1.0) * 24).toInt()
                    txt.append("In${c + 1} ")
                        .append("█".repeat(bars))
                        .append("░".repeat(24 - bars))
                        .append(String.format(" %5.1f dB\n", db))
                }
                val t = txt.toString()
                runOnUiThread { meter.text = t }
            }
            r.stop()
            r.release()
            running = false
            runOnUiThread { meter.text = "" }
        }
    }

    private fun copy() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("report", sb.toString()))
        Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
    }
}
