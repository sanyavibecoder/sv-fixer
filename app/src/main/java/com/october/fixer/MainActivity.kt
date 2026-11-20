package com.october.fixer

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.graphics.Color
import android.graphics.drawable.AnimatedVectorDrawable
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : Activity() {

    private lateinit var button: View
    private lateinit var mascot: ImageView
    private lateinit var status: TextView
    private lateinit var hint: TextView
    private lateinit var proxyHint: TextView
    private lateinit var mm2Sub: TextView
    private lateinit var settingsScreen: SettingsScreen
    private var pickInProgress = false
    private var reportToSave: String? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = render()
    }

    private enum class Look { OFF, CONNECTING, ON }

    private var look: Look? = null
    private var connecting = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        reportToSave = savedInstanceState?.getString(STATE_REPORT_TO_SAVE)
        setContentView(R.layout.activity_main)
        button = findViewById(R.id.toggle)
        mascot = findViewById(R.id.mascot)
        status = findViewById(R.id.status)
        hint = findViewById(R.id.hint)
        proxyHint = findViewById(R.id.proxyHint)
        findViewById<View>(R.id.project).setOnClickListener {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(getString(R.string.project_url))))
            } catch (_: ActivityNotFoundException) {
                Toast.makeText(this, R.string.settings_link_unavailable, Toast.LENGTH_SHORT).show()
            }
        }
        settingsScreen = SettingsScreen(
            this, findViewById<FrameLayout>(android.R.id.content), ::saveDomain, ::addProxyToTelegram, ::confirmReportShare,
            checkUpdates = { checkUpdate(manual = true) }, onHomeShown = ::render,
        )
        findViewById<View>(R.id.settings).setOnClickListener { showSettings() }
        proxyHint.setOnClickListener { showSettings() }
        settingsScreen.restoreState(savedInstanceState)
        mm2Sub = findViewById(R.id.mm2Sub)
        findViewById<View>(R.id.mm2Row).setOnClickListener { showServerPicker() }
        button.setOnClickListener { onToggle() }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2)
        }
    }

    override fun onStart() {
        super.onStart()
        val f = IntentFilter(FixVpnService.ACTION_STATE)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(receiver, f, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(receiver, f)
        }
        if (!FixVpnService.running) Diagnostics.noteServiceKilled(this)
    }

    override fun onStop() {
        unregisterReceiver(receiver)
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(STATE_REPORT_TO_SAVE, reportToSave)
        settingsScreen.saveState(outState)
        super.onSaveInstanceState(outState)
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        if (!settingsScreen.back()) super.onBackPressed()
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        privateDnsBlocked = NetworkFacts.privateDnsBlocks(this)
        render()
        Mm2.last?.let { mm2Sub.text = "Последний: $it" }
        handleServerRetry(intent)
        pendingUpdate?.let { pendingUpdate = null; askUpdate(it) }
        checkUpdate()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleServerRetry(intent)
    }

    /** Automatic checks are throttled. A manual check can use an automatic request still in flight. */
    private fun checkUpdate(manual: Boolean = false) {
        if (!Updater.enabled) return
        val now = System.currentTimeMillis()
        if (!manual && now - lastUpdateCheck < UPDATE_CHECK_MS) return
        if (manual) {
            manualUpdateRequested = true
            settingsScreen.showUpdateFeedback(getString(R.string.settings_update_running), checking = true)
        }
        if (updateCheckInProgress) return
        updateCheckInProgress = true
        lastUpdateCheck = now
        val current = packageManager.getPackageInfo(packageName, 0).longVersionCode.toInt()
        Thread({
            val result = Updater.check(current)
            runOnUiThread { finishUpdateCheck(result) }
        }, "update-check").start()
    }

    private fun finishUpdateCheck(result: Updater.CheckResult) {
        updateCheckInProgress = false
        val manual = manualUpdateRequested
        manualUpdateRequested = false
        val active = resumed && !isFinishing && !isDestroyed
        if (manual && !isDestroyed) {
            val message = when (result) {
                is Updater.CheckResult.Available -> getString(R.string.settings_update_available, result.release.name)
                Updater.CheckResult.Current -> getString(R.string.settings_update_current)
                Updater.CheckResult.DnsFailure -> getString(R.string.settings_update_dns_error)
                Updater.CheckResult.Timeout -> getString(R.string.settings_update_timeout)
                Updater.CheckResult.NetworkFailure -> getString(R.string.settings_update_network_error)
                Updater.CheckResult.InvalidResponse -> getString(R.string.settings_update_invalid)
                is Updater.CheckResult.HttpFailure -> getString(R.string.settings_update_http_error, result.code)
            }
            settingsScreen.showUpdateFeedback(message)
        }
        if (result is Updater.CheckResult.Available) {
            val release = result.release
            val prefs = getSharedPreferences(FixVpnService.PREFS, MODE_PRIVATE)
            val putOff = prefs.getInt(KEY_UPDATE_LATER, 0) == release.code &&
                System.currentTimeMillis() - prefs.getLong(KEY_UPDATE_LATER_AT, 0) < UPDATE_LATER_MS
            if (manual || !putOff) {
                if (active) askUpdate(release) else pendingUpdate = release
            }
        }
    }

    private fun askUpdate(r: Updater.Release) {
        val view = layoutInflater.inflate(R.layout.dialog_update, null)
        val d = cardDialog(view)
        val go = view.findViewById<TextView>(R.id.updateGo)
        val notes = view.findViewById<TextView>(R.id.updateNotes)
        view.findViewById<TextView>(R.id.updateTitle).text = "Вышел ${getString(R.string.app_name)} ${r.name}"
        notes.text = r.notes.ifEmpty { "Настройки и прокси Telegram сохранятся." }
        var busy = false
        go.setOnClickListener {
            if (busy) return@setOnClickListener
            if (!packageManager.canRequestPackageInstalls()) {
                // one-time switch in system settings; the dialog stays open for the second tap
                try {
                    startActivity(Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
                } catch (_: Exception) {
                }
                Toast.makeText(this, "Разреши установку из SV Fixer и нажми «Обновить» ещё раз", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            busy = true
            buzz(VibrationEffect.EFFECT_TICK, 15)
            go.text = "Загружаю…"
            Thread({
                try {
                    val apk = Updater.download(this, r) { p -> runOnUiThread { go.text = "Загружаю… $p%" } }
                    Updater.install(this, apk)
                    runOnUiThread { d.dismiss() }
                } catch (e: Exception) {
                    runOnUiThread {
                        busy = false
                        go.text = "Повторить"
                        notes.text = "Не загрузилось: ${e.message}. Проверь интернет и попробуй снова."
                    }
                }
            }, "update-download").start()
        }
        view.findViewById<View>(R.id.updateLater).setOnClickListener {
            getSharedPreferences(FixVpnService.PREFS, MODE_PRIVATE).edit()
                .putInt(KEY_UPDATE_LATER, r.code).putLong(KEY_UPDATE_LATER_AT, System.currentTimeMillis()).apply()
            d.dismiss()
        }
        d.show()
    }

    override fun onPause() {
        resumed = false
        super.onPause()
    }

    private fun onToggle() {
        if (connecting) return
        buzz(if (FixVpnService.running) VibrationEffect.EFFECT_TICK else VibrationEffect.EFFECT_CLICK, 15)
        if (FixVpnService.running) {
            connecting = false
            startService(Intent(this, FixVpnService::class.java).setAction(FixVpnService.ACTION_STOP))
        } else {
            note = null
            FixVpnService.lastError = null // the last attempt's error would cancel "connecting" in render()
            connecting = true
            render()
            try {
                val prep = VpnService.prepare(this)
                if (prep != null) {
                    startActivityForResult(prep, REQ_VPN)
                } else {
                    Diagnostics.recordVpnStage(this, "vpn_request", "ok")
                    startVpn()
                }
            } catch (_: RuntimeException) {
                vpnStartRejected()
            }
        }
    }

    private fun startVpn() {
        val i = Intent(this, FixVpnService::class.java)
        try {
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
        } catch (_: RuntimeException) {
            vpnStartRejected()
        }
    }

    private fun vpnStartRejected() {
        Diagnostics.recordVpnStage(this, "vpn_request", "start_rejected")
        connecting = false
        note = "Android не смог запустить VPN"
        render()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_SAVE_REPORT) {
            val report = reportToSave
            reportToSave = null
            val uri = data?.data
            if (resultCode == RESULT_OK && report != null && uri != null) writeReport(uri, report)
            return
        }
        if (requestCode != REQ_VPN) return
        if (resultCode == RESULT_OK) {
            Diagnostics.recordVpnStage(this, "vpn_request", "ok")
            startVpn()
        } else {
            Diagnostics.recordVpnStage(this, "vpn_request", "permission_missing")
            connecting = false
            note = "Разрешение на VPN не дано"
        }
        render()
    }

    private fun tgDomain() = FixVpnService.tgDomain(this)

    private fun saveDomain(d: String) {
        val before = tgDomain()
        getSharedPreferences(FixVpnService.PREFS, MODE_PRIVATE).edit().apply {
            if (d.isEmpty()) remove(FixVpnService.KEY_TG_DOMAIN)
            else putString(FixVpnService.KEY_TG_DOMAIN, d)
        }.apply()
        if (tgDomain() != before && FixVpnService.running) {
            startService(Intent(this, FixVpnService::class.java).setAction(FixVpnService.ACTION_RESTART))
        }
    }

    private fun cardDialog(view: View): Dialog {
        val d = Dialog(this)
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        d.setContentView(view)
        d.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setDimAmount(0.7f)
            decorView.setPadding(20f.dp(), 0, 20f.dp(), 0)
        }
        return d
    }

    private fun confirmReportShare(report: String) {
        AlertDialog.Builder(this, R.style.SettingsConfirmation)
            .setTitle(R.string.report_share_title)
            .setMessage(R.string.report_share_message)
            .setNeutralButton(android.R.string.cancel, null)
            .setNegativeButton(R.string.report_save) { _, _ -> saveReport(report) }
            .setPositiveButton(R.string.report_open_telegram) { _, _ -> shareReportInTelegram(report) }
            .show()
    }

    private fun shareReportInTelegram(report: String) {
        val uri = try {
            ReportFileProvider.create(this, report)
        } catch (_: IOException) {
            Toast.makeText(this, R.string.report_save_failed, Toast.LENGTH_SHORT).show()
            confirmReportShare(report)
            return
        }
        val share = Intent(Intent.ACTION_SEND).apply {
            type = ReportFileProvider.MIME_TYPE
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri(ReportFileProvider.FILE_NAME, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        for (telegram in TELEGRAM_PACKAGES) {
            try {
                startActivity(share.setPackage(telegram))
                return
            } catch (_: ActivityNotFoundException) {
                // Try the next Telegram client.
            }
        }
        AlertDialog.Builder(this, R.style.SettingsConfirmation)
            .setTitle(R.string.report_telegram_unavailable)
            .setMessage(R.string.report_manual_share)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.report_save) { _, _ -> saveReport(report) }
            .show()
    }

    private fun saveReport(report: String) {
        val create = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = ReportFileProvider.MIME_TYPE
            putExtra(Intent.EXTRA_TITLE, ReportFileProvider.FILE_NAME)
        }
        reportToSave = report
        try {
            startActivityForResult(create, REQ_SAVE_REPORT)
        } catch (_: ActivityNotFoundException) {
            reportToSave = null
            Toast.makeText(this, R.string.report_save_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun writeReport(uri: Uri, report: String) {
        Thread {
            val message = try {
                val output = contentResolver.openOutputStream(uri, "wt") ?: throw IOException()
                output.bufferedWriter(Charsets.UTF_8).use { it.write(report) }
                R.string.report_saved
            } catch (_: IOException) {
                R.string.report_save_failed
            } catch (_: SecurityException) {
                R.string.report_save_failed
            }
            runOnUiThread {
                if (!isDestroyed) Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
            }
        }.start()
    }

    @Suppress("DEPRECATION") // adjustResize still handles the keyboard for this floating Dialog window.
    private fun showServerPicker() {
        val view = layoutInflater.inflate(R.layout.dialog_server_picker, null)
        val dialog = cardDialog(view)
        dialog.window?.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
        )
        ServerPicker(view, dialog) { id, done -> pickServer(id, done) }
        dialog.show()
    }

    private fun handleServerRetry(intent: Intent) {
        val token = intent.getStringExtra(FixVpnService.EXTRA_SERVER_RETRY) ?: return
        intent.removeExtra(FixVpnService.EXTRA_SERVER_RETRY)
        if (token == Mm2.pickToken && FixVpnService.running) pickServer(Mm2.armedPlaceId)
    }

    /**
     * Starts a server pick on a worker thread. [onResult] runs on the UI thread with the outcome unless
     * cancelled. Returns the cancel flag, or null when nothing was started (busy or cooling down).
     */
    private fun pickServer(placeId: Long, onResult: ((PickResult) -> Unit)? = null): AtomicBoolean? {
        if (pickInProgress) {
            Toast.makeText(this, "Подожди, сервер ещё подбирается", Toast.LENGTH_SHORT).show()
            return null
        }
        val wait = Mm2.lastPick + Mm2.COOLDOWN_MS - SystemClock.elapsedRealtime()
        if (Mm2.lastPick != 0L && wait > 0) {
            Toast.makeText(this, "Повтори через ${(wait + 999) / 1000} с", Toast.LENGTH_SHORT).show()
            return null
        }
        pickInProgress = true
        mm2Sub.text = "Ищу свободный сервер…"
        val cancel = AtomicBoolean(false)
        Thread({
            var result: PickResult = PickResult.Offline
            try {
                result = Mm2.next(placeId, cancel::get)
            } catch (_: Throwable) {
                result = PickResult.Offline // shown as «Нет связи с Roblox» by finishPick
            } finally {
                runOnUiThread {
                    pickInProgress = false
                    if (cancel.get()) {
                        mm2Sub.text = Mm2.last?.let { "Последний: $it" } ?: getString(R.string.choose_server_subtitle)
                    } else if (!isFinishing && !isDestroyed) {
                        finishPick(placeId, result, onResult)
                    }
                }
            }
        }, "roblox-pick").start()
        return cancel
    }

    private fun finishPick(placeId: Long, result: PickResult, onResult: ((PickResult) -> Unit)?) {
        when (result) {
            is PickResult.Server -> {
                val measure = FixVpnService.running
                if (measure) Mm2.arm(placeId)
                if (joinServer(placeId, result.jobId)) {
                    Mm2.lastPick = SystemClock.elapsedRealtime()
                    mm2Sub.text = if (measure) "Вход · пинг придёт уведомлением" else "Вход · замер доступен с VPN"
                } else {
                    Mm2.disarm()
                    mm2Sub.text = "Не удалось открыть Roblox"
                }
            }
            // the error is shown by the picker dialog; the main screen goes back to its normal subtitle
            else -> mm2Sub.text = Mm2.last?.let { "Последний: $it" } ?: getString(R.string.choose_server_subtitle)
        }
        onResult?.invoke(result)
    }

    private fun joinServer(placeId: Long, jobId: String): Boolean {
        val app = Intent(Intent.ACTION_VIEW, Mm2.joinUri(placeId, jobId)).setPackage(FixVpnService.ROBLOX)
        val web = Intent(Intent.ACTION_VIEW, Mm2.webJoinUri(placeId, jobId))
        for (intent in listOf(app, web)) {
            try {
                startActivity(intent)
                return true
            } catch (_: android.content.ActivityNotFoundException) {
            }
        }
        return false
    }

    /** Telegram reaches Fixer as an MTProto proxy; this link opens its "Connect proxy?" sheet. */
    private fun addProxyToTelegram() {
        val link = TgBridge.link(TgBridge.parseSecret(FixVpnService.tgSecret(this)))
        val target = Intent(Intent.ACTION_VIEW, Uri.parse(link))
        try {
            if (packageManager.queryIntentActivities(target, PackageManager.MATCH_DEFAULT_ONLY).isNotEmpty()) {
                startActivity(Intent.createChooser(target, getString(R.string.telegram_proxy_chooser)))
                return
            }
        } catch (_: ActivityNotFoundException) {
        }
        Toast.makeText(this, R.string.telegram_proxy_no_handler, Toast.LENGTH_LONG).show()
    }

    private fun showSettings() {
        proxyHint.visibility = View.GONE
        settingsScreen.show()
    }

    private fun render() {
        val on = FixVpnService.running
        val vpnError = FixVpnService.lastError
        if (on || vpnError != null) connecting = false
        val next = when {
            on -> Look.ON
            connecting -> Look.CONNECTING
            else -> Look.OFF
        }
        if (next != look) {
            val previous = look
            look = next
            renderMascot(previous, next)
            button.setBackgroundResource(if (on) R.drawable.btn_core_on else R.drawable.btn_core)
            status.setTextColor(getColor(if (on) R.color.accent else R.color.text_secondary))
            status.text = when (next) {
                Look.ON -> "Подключено"
                Look.CONNECTING -> "Подключаю…"
                Look.OFF -> "Отключено"
            }
            button.contentDescription = when (next) {
                Look.ON -> "Выключить SV Fixer"
                Look.CONNECTING -> "Подключаю SV Fixer"
                Look.OFF -> "Включить SV Fixer"
            }
        }

        if (!on) {
            proxyHint.visibility = View.GONE
        } else if (resumed && button.isShown) {
            val prefs = getSharedPreferences(FixVpnService.PREFS, MODE_PRIVATE)
            if (!prefs.getBoolean(KEY_PROXY_HINT_SHOWN, false)) {
                proxyHint.visibility = View.VISIBLE
                prefs.edit().putBoolean(KEY_PROXY_HINT_SHOWN, true).apply()
            }
        }

        val problem = when (next) {
            Look.ON -> listOfNotNull(FixVpnService.notice, FixVpnService.tgError?.let { "Telegram: $it" })
                .joinToString("\n").takeIf { it.isNotEmpty() }
            Look.CONNECTING -> null
            Look.OFF -> vpnError ?: note ?: interruptedVpnMessage()
        }
        val warnPrivateDns = problem == null && next != Look.CONNECTING && privateDnsBlocked
        hint.visibility = if (problem != null || warnPrivateDns) View.VISIBLE else View.GONE
        hint.text = when {
            problem != null -> getString(R.string.problem_diagnostics, problem)
            warnPrivateDns -> "Выключи «Частный DNS» в настройках сети, чтобы Fixer мог исправить загрузку иконок Roblox"
            else -> null
        }
        when {
            problem != null -> hint.setOnClickListener { settingsScreen.diagnostics() }
            warnPrivateDns -> hint.setOnClickListener {
                try {
                    startActivity(Intent(android.provider.Settings.ACTION_WIRELESS_SETTINGS))
                } catch (_: ActivityNotFoundException) {
                }
            }
            else -> {
                hint.setOnClickListener(null)
                hint.isClickable = false
            }
        }
    }

    private fun renderMascot(previous: Look?, next: Look) {
        val awake = next != Look.OFF
        if (previous != null && awake == (previous != Look.OFF)) return
        val drawable = when {
            awake && previous == null -> R.drawable.mascot_awake
            awake -> R.drawable.mascot_wake
            previous == null -> R.drawable.mascot_asleep
            else -> R.drawable.mascot_sleep
        }
        (mascot.drawable as? AnimatedVectorDrawable)?.stop()
        mascot.setImageResource(drawable)
        (mascot.drawable as AnimatedVectorDrawable).start()
    }

    private fun interruptedVpnMessage(): String? {
        val code = Diagnostics.pendingServiceKill(this) ?: return null
        val message = when (code) {
            "exit_low_memory" -> R.string.vpn_interrupted_memory
            "exit_user_requested" -> R.string.vpn_interrupted_user
            "exit_crash" -> R.string.vpn_interrupted_crash
            "exit_anr" -> R.string.vpn_interrupted_unresponsive
            "exit_excessive_resource" -> R.string.vpn_interrupted_resources
            "exit_signaled" -> R.string.vpn_interrupted_signal
            else -> R.string.vpn_interrupted_unknown
        }
        return getString(message)
    }

    private fun buzz(effect: Int, fallbackMs: Long) {
        try {
            val v = getSystemService(Vibrator::class.java) ?: return
            when {
                Build.VERSION.SDK_INT >= 29 -> v.vibrate(VibrationEffect.createPredefined(effect))
                Build.VERSION.SDK_INT >= 26 -> v.vibrate(VibrationEffect.createOneShot(fallbackMs, VibrationEffect.DEFAULT_AMPLITUDE))
                else -> @Suppress("DEPRECATION") v.vibrate(fallbackMs)
            }
        } catch (_: Exception) {
        }
    }

    private fun Float.dp() = (this * resources.displayMetrics.density).toInt()

    private var note: String? = null
    private var resumed = false
    private var privateDnsBlocked = false
    private var updateCheckInProgress = false
    private var manualUpdateRequested = false

    companion object {
        private const val REQ_VPN = 1
        private const val REQ_SAVE_REPORT = 3
        private const val STATE_REPORT_TO_SAVE = "report_to_save"
        private const val KEY_PROXY_HINT_SHOWN = "proxy_hint_shown"
        private const val KEY_UPDATE_LATER = "update_later"
        private const val KEY_UPDATE_LATER_AT = "update_later_at"
        private const val UPDATE_CHECK_MS = 6 * 3600_000L
        private const val UPDATE_LATER_MS = 24 * 3600_000L

        /** Per process: reopening the screen does not ask the server again. */
        private var lastUpdateCheck = 0L

        /** A release found while the screen was paused, shown when it is back. */
        private var pendingUpdate: Updater.Release? = null
        private val TELEGRAM_PACKAGES = listOf("org.telegram.messenger", "org.telegram.messenger.web", "org.thunderdog.challegram")
    }
}
