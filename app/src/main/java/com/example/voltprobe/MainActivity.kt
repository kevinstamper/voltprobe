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
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sqrt

class MainActivity : Activity() {
    private lateinit var meter: TextView
    private lateinit var out: TextView
    private val sb = StringBuilder()
    @Volatile private var running = false
    private val rate = 48000
    @Volatile private var useFloat = false
    @Volatile private var srcIdx = 0
    private val srcs = listOf(
        MediaRecorder.AudioSource.MIC to "MIC",
        MediaRecorder.AudioSource.UNPROCESSED to "UNPROCESSED",
        MediaRecorder.AudioSource.VOICE_RECOGNITION to "VOICE_REC"
    )
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
        addBtn("4. Mask scan (steady signal on ONE input)") { needMic { maskScan() } }
        val modeBtn = Button(this)
        fun refreshMode() {
            modeBtn.text = "Mode: ${if (useFloat) "float" else "16-bit"} / ${srcs[srcIdx].second} (tap to change)"
        }
        modeBtn.setOnClickListener {
            val nxt = (srcIdx * 2 + (if (useFloat) 1 else 0) + 1) % (srcs.size * 2)
            srcIdx = nxt / 2
            useFloat = nxt % 2 == 1
            refreshMode()
        }
        refreshMode()
        root.addView(modeBtn)
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
            it.type == AudioDeviceInfo.TYPE_USB_DEVICE ||
                it.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                it.productName.toString().contains("volt", ignoreCase = true)
        }

    private fun open(
        dev: AudioDeviceInfo?,
        n: Int,
        float: Boolean = useFloat,
        src: Int = srcIdx,
        mask: Int = (1 shl n) - 1
    ): AudioRecord? = try {
        val fmt = AudioFormat.Builder()
            .setEncoding(if (float) AudioFormat.ENCODING_PCM_FLOAT else AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(rate)
            .setChannelIndexMask(mask)
            .build()
        val r = AudioRecord.Builder()
            .setAudioSource(srcs[src].first)
            .setAudioFormat(fmt)
            .setBufferSizeInBytes(rate * n * (if (float) 4 else 2) / 2)
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
                val r = open(dev, n, false, 0)
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
        val float = useFloat
        val modeName = "${if (float) "float" else "16-bit"}/${srcs[srcIdx].second}"
        thread {
            val dev = usbIn()
            log("meters: mode $modeName; USB input: ${dev?.productName ?: "NONE FOUND"}")
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
            r.startRecording()
            log("meters: $n ch, routed=${r.routedDevice?.productName} type=${r.routedDevice?.type}")
            val frames = rate / 20
            val sbuf = ShortArray(if (float) 0 else frames * n)
            val fbuf = FloatArray(if (float) frames * n else 0)
            while (running) {
                val got = if (float) r.read(fbuf, 0, fbuf.size, AudioRecord.READ_BLOCKING)
                else r.read(sbuf, 0, sbuf.size)
                if (got <= 0) break
                val f = got / n
                val txt = StringBuilder()
                for (c in 0 until n) {
                    var s = 0.0
                    var pk = 0.0
                    for (i in 0 until f) {
                        val v = if (float) fbuf[i * n + c].toDouble() else sbuf[i * n + c] / 32768.0
                        s += v * v
                        val a = abs(v)
                        if (a > pk) pk = a
                    }
                    val rms = 20 * log10(sqrt(s / f) + 1e-9)
                    val pdb = 20 * log10(pk + 1e-9)
                    val bars = (((rms + 80) / 80).coerceIn(0.0, 1.0) * 24).toInt()
                    txt.append(String.format("In%d ", c + 1))
                        .append("█".repeat(bars))
                        .append("░".repeat(24 - bars))
                        .append(String.format(" rms %5.1f pk %5.1f\n", rms, pdb))
                }
                val rd = r.routedDevice
                val t = "ROUTED: ${rd?.productName} (type ${rd?.type}) $modeName ${r.channelCount}ch\n" + txt.toString()
                runOnUiThread { meter.text = t }
            }
            r.stop()
            r.release()
            running = false
            runOnUiThread { meter.text = "" }
        }
    }

    private fun measure(r: AudioRecord, n: Int, float: Boolean, frames: Int): DoubleArray {
        val sums = DoubleArray(n)
        var done = 0
        val sbuf = ShortArray(if (float) 0 else 4800 * n)
        val fbuf = FloatArray(if (float) 4800 * n else 0)
        while (done < frames) {
            val got = if (float) r.read(fbuf, 0, fbuf.size, AudioRecord.READ_BLOCKING)
            else r.read(sbuf, 0, sbuf.size)
            if (got <= 0) break
            val f = got / n
            for (i in 0 until f) {
                for (c in 0 until n) {
                    val v = if (float) fbuf[i * n + c].toDouble() else sbuf[i * n + c] / 32768.0
                    sums[c] += v * v
                }
            }
            done += f
        }
        return DoubleArray(n) { 20 * log10(sqrt(sums[it] / maxOf(done, 1)) + 1e-9) }
    }

    private fun maskScan() {
        if (running) {
            log("Stop the live meters first")
            return
        }
        thread {
            val float = useFloat
            log("== MASK SCAN ${if (float) "float" else "16-bit"}/${srcs[srcIdx].second} ==")
            log("(mask: rightmost digit = Volt input 1; values are dB per app channel)")
            val dev = usbIn()
            for (mask in listOf(1, 2, 4, 8, 3, 12, 15)) {
                val n = Integer.bitCount(mask)
                val r = open(dev, n, float, srcIdx, mask)
                if (r == null) {
                    log("mask ${Integer.toBinaryString(mask)}: cannot open")
                    continue
                }
                try {
                    r.startRecording()
                    measure(r, n, float, 4800)
                    val db = measure(r, n, float, 48000)
                    log(
                        String.format("mask %4s: ", Integer.toBinaryString(mask)) +
                            db.joinToString("  ") { String.format("%.0f", it) }
                    )
                } catch (e: Exception) {
                    log("mask ${Integer.toBinaryString(mask)}: error ${e.message}")
                } finally {
                    r.release()
                }
            }
            log("")
        }
    }

    private fun copy() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("report", sb.toString()))
        Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
    }
}
