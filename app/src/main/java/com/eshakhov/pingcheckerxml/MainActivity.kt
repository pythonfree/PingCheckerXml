package com.eshakhov.pingcheckerxml

import android.graphics.Color
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.os.Handler
import android.os.Message
import android.util.Log
import android.view.View
import android.widget.EditText
import android.widget.TableRow
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.eshakhov.pingcheckerxml.databinding.ActivityMainBinding
import java.io.IOException
//import kotlinx.android.synthetic.main.activity_main.*
import java.lang.ref.WeakReference
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.Queue
import java.util.regex.Matcher
import java.util.regex.Pattern
import kotlin.math.roundToInt
import kotlin.math.roundToLong

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding

    private val pingRegex =
        "(?:\\[(?<ts>[0-9.]+)] )?(?<size>[0-9]+) bytes from (?<ip>[0-9.]+): icmp_seq=(?<seq>[0-9]+) ttl=(?<ttl>[0-9]+)(?: time=(?<rtt>[0-9.]+) (?<rttmetric>\\w+))?"
    private val outerClass = WeakReference<MainActivity>(this)
    private var mHandlerThread = MyHandler(outerClass)

    private var mThread: Thread? = null
    private var isThreadRunning = false
    private var errorMessage = ""

    val queue: Queue<String> = ArrayDeque<String>()

    companion object {
        private const val START = 100
        private const val STOP = 101
        private const val PING = 102
    }

    object Params {
        var server = "8.8.8.8"
        var size = 16
        var interval = 1.0
        var count = 5
        var rttAlarmValue = 50
    }

    private var params = Params

    private fun showSettingsPopup() {
        val builder = AlertDialog.Builder(this)
        val inflater = layoutInflater
        val dialogLayout = inflater.inflate(R.layout.alert_dialog_settings, null)

        val serverText = dialogLayout.findViewById<EditText>(R.id.addressText)
        val sizeText = dialogLayout.findViewById<EditText>(R.id.sizeText)
        val intervalText = dialogLayout.findViewById<EditText>(R.id.intervalText)
        val countText = dialogLayout.findViewById<EditText>(R.id.countText)
        val rttAlarmValue = dialogLayout.findViewById<EditText>(R.id.rttAlarmValue)

        serverText.setText(params.server)
        sizeText.setText(params.size.toString())
        intervalText.setText(params.interval.toString())
        countText.setText(params.count.toString())
        rttAlarmValue.setText(params.rttAlarmValue.toString())

        builder.setView(dialogLayout)
        builder.setPositiveButton("OK") { _, _ ->
            params.server = try {
                serverText.text.toString()
            } catch (e: Exception) {
                "8.8.8.8"
            }
            params.size = try {
                sizeText.text.toString().toInt()
            } catch (e: Exception) {
                16
            }
            params.interval = try {
                intervalText.text.toString().toDouble()
            } catch (e: Exception) {
                1.0
            }
            params.count = try {
                countText.text.toString().toInt()
            } catch (e: Exception) {
                5
            }
            params.rttAlarmValue = try {
                rttAlarmValue.text.toString().toInt()
            } catch (e: Exception) {
                50
            }

            syncServerText()
        }
        builder.show()
    }

    private class MyHandler(private val outerClass: WeakReference<MainActivity>) : Handler() {
        override fun handleMessage(msg: Message) {
            super.handleMessage(msg)
            when (msg.what) {
                PING -> outerClass.get()?.updateText()
                STOP -> outerClass.get()?.togglePing(false)
                START -> outerClass.get()?.togglePing(true)
            }
        }
    }

    private fun parsePingString(s: String): Matcher {
        val re = Pattern.compile(
            pingRegex,
            Pattern.CASE_INSENSITIVE.or(Pattern.DOTALL)
        )
        return re.matcher(s)

    }

    private fun updateText() {
        if (queue.size == 0) {
            return
        }

        val res = parsePingString(queue.remove())

        if (!res.find()) {
            return
        }
        val row = TableRow(this)
        row.layoutParams = TableRow.LayoutParams(TableRow.LayoutParams.WRAP_CONTENT)

        val rowView = layoutInflater.inflate(R.layout.result_row, binding.tableLayout, false)
        val tvTimestamp = rowView.findViewById(R.id.tvTimestamp) as? TextView
        val tvSize = rowView.findViewById(R.id.tvSize) as? TextView
        val tvTarget = rowView.findViewById(R.id.tvTarget) as? TextView
        val tvSeqN = rowView.findViewById(R.id.tvSeqN) as? TextView
        val tvTtl = rowView.findViewById(R.id.tvTtl) as? TextView
        val tvRtt = rowView.findViewById(R.id.tvRtt) as? TextView

        var dateTimeStr = res.group(1)
        try {
            val sdf = SimpleDateFormat(getString(R.string.datetime_format), Locale.US)
            val netDate = Date((dateTimeStr.toDouble() * 1000).roundToLong())
            dateTimeStr = sdf.format(netDate)
        } finally {
            tvTimestamp?.text = dateTimeStr
        }

        tvSize?.text = res.group(2)
        tvTarget?.text = res.group(3)
        tvSeqN?.text = res.group(4)
        tvTtl?.text = res.group(5)

        val rttConcat = if (res.group(6) != null)
            resources.getString(R.string.format_rtt, res.group(6), res.group(7))
        else resources.getString(R.string.value_na)

        tvRtt?.text = rttConcat
        val endIndex = rttConcat.indexOf(".") + 2
        val rtt = rttConcat.substring(0, endIndex).toDouble().roundToInt()
        if (rtt > params.rttAlarmValue) {
            tvRtt?.setTextColor(Color.RED)
        }
        binding.tableLayout.addView(rowView, 1)
    }

    internal inner class PingProcess : Runnable {
        override fun run() {

            val cmd = mutableListOf("ping", "-D")
            if (params.size > 0) {
                cmd.addAll(arrayOf("-s", params.size.toString()))
            }
            if (params.interval > 0) {
                cmd.addAll(arrayOf("-i", params.interval.toString()))
            }
            if (params.count > 0) {
                cmd.addAll(arrayOf("-c", params.count.toString()))
            }
            cmd.add(params.server)

            val builder = ProcessBuilder()
            builder.command(cmd)

            val process = builder.start()
            val stdInput = process.inputStream.bufferedReader()

            val messageStart = Message()
            messageStart.what = START
            mHandlerThread.sendMessage(messageStart)

            while (isThreadRunning) {
                val currentStr = try {
                    stdInput.readLine()
                } catch (e: IllegalStateException) {
                    break
                } ?: break

                pingRegex.toRegex().find(currentStr) ?: continue

                queue.add(currentStr)

                val messagePing = Message()
                messagePing.what = PING
                mHandlerThread.sendMessage(messagePing)
            }
            if (isThreadRunning) {
                if (process.errorStream.bufferedReader().ready()) {
                    errorMessage = process.errorStream.bufferedReader().read().toString()
                }
            }

            val messageStop = Message()
            messageStop.what = STOP
            mHandlerThread.sendMessage(messageStop)

            process.destroy()
        }
    }

    private fun togglePing(on: Boolean) {
//        if (errorMessage != "") {
//            Toast.makeText(this, "Error PING! Maybe wrong params?", Toast.LENGTH_LONG).show()
//        }

        if (on) {
            binding.pingButton.text = resources.getString(R.string.btn_stop)
        } else {
            mThread = null
            binding.pingButton.text = resources.getString(R.string.btn_start)
        }
        errorMessage = ""
        binding.pingButton.isClickable = true
    }


    private fun triggerTogglePing() {
        binding.pingButton.isClickable = false

        val doEnable = mThread == null
        isThreadRunning = doEnable

        if (doEnable) {
            mThread = Thread(PingProcess())
            mThread?.start()
        }
    }

    private fun initTableView() {
        binding.tableLayout.removeAllViews()
        val headerRow = layoutInflater.inflate(R.layout.result_row, binding.tableLayout, false)
        binding.tableLayout.addView(headerRow)
    }

    private fun syncServerText() {
        binding.settingsButton.text = params.server
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        syncServerText()
        initTableView()

        binding.clearButton.setOnClickListener {
            initTableView()
        }

        binding.pingButton.setOnClickListener {
            triggerTogglePing()
        }

        binding.settingsButton.setOnClickListener {
            showSettingsPopup()
        }

    }
}