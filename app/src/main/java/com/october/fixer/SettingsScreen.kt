package com.october.fixer

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.Locale

internal class SettingsScreen(
    private val activity: Activity,
    private val host: FrameLayout,
    private val saveDomain: (String) -> Unit,
    private val addProxy: () -> Unit,
    private val shareReport: (String) -> Unit,
    private val checkUpdates: () -> Unit,
    private val onHomeShown: () -> Unit,
) {
    private enum class Page(val title: Int) {
        SETTINGS(R.string.brand_name),
        DOMAIN(R.string.relay_settings),
        DOMAIN_HELP(R.string.settings_domain_help_title),
        CHECK(R.string.settings_check),
        REPORT(R.string.report_settings),
        ABOUT(R.string.settings_about),
    }

    private data class Entry(val page: Page, var scrollY: Int = 0)

    private data class Row(
        val icon: Int,
        val title: Int,
        val subtitle: String,
        val external: Boolean = false,
        val click: () -> Unit,
    )

    private val home = host.getChildAt(0)
    private val inflater = activity.layoutInflater
    private val history = mutableListOf<Entry>()
    private var screen: View? = null
    private var domainDraft: String? = null
    private var updateFeedback: String? = null
    private var updateChecking = false
    private var updateButton: Button? = null
    private var updateStatus: TextView? = null

    @Suppress("DEPRECATION")
    private val version by lazy {
        activity.getString(
            R.string.settings_version,
            activity.packageManager.getPackageInfo(activity.packageName, 0).versionName.orEmpty(),
        )
    }

    fun show() = open(Page.SETTINGS)

    fun diagnostics() = open(Page.CHECK)

    fun showUpdateFeedback(message: String, checking: Boolean = false) {
        updateFeedback = message
        updateChecking = checking
        refreshUpdateFeedback()
    }

    private fun refreshUpdateFeedback() {
        updateButton?.isEnabled = !updateChecking
        updateStatus?.apply {
            text = updateFeedback
            visibility = if (updateFeedback == null) View.GONE else View.VISIBLE
        }
    }

    fun back(): Boolean {
        if (history.isEmpty()) return false
        hideKeyboard()
        if (history.removeAt(history.lastIndex).page == Page.DOMAIN) domainDraft = null
        render()
        return true
    }

    fun saveState(state: Bundle) {
        rememberPage()
        state.putStringArrayList(STATE_PAGES, ArrayList(history.map { it.page.name }))
        state.putIntArray(STATE_SCROLL, history.map { it.scrollY }.toIntArray())
        state.putString(STATE_DOMAIN, domainDraft)
    }

    fun restoreState(state: Bundle?) {
        val saved = state?.getStringArrayList(STATE_PAGES).orEmpty()
        val scroll = state?.getIntArray(STATE_SCROLL) ?: intArrayOf()
        saved.forEachIndexed { index, name ->
            history.add(Entry(Page.valueOf(name), scroll.getOrElse(index) { 0 }))
        }
        domainDraft = state?.getString(STATE_DOMAIN)
        if (history.isNotEmpty()) render()
    }

    private fun open(page: Page) {
        rememberPage()
        hideKeyboard()
        history.add(Entry(page))
        render()
    }

    private fun rememberPage() {
        history.lastOrNull()?.let { entry ->
            entry.scrollY = screen?.findViewById<ScrollView>(R.id.settingsScroll)?.scrollY ?: 0
        }
        screen?.findViewById<EditText>(R.id.domainInput)?.let { domainDraft = it.text.toString() }
    }

    private fun hideKeyboard() {
        screen?.findViewById<EditText>(R.id.domainInput)?.let {
            activity.getSystemService(InputMethodManager::class.java)
                .hideSoftInputFromWindow(it.windowToken, 0)
        }
    }

    private fun render() {
        screen?.let(host::removeView)
        screen = null
        updateButton = null
        updateStatus = null
        val entry = history.lastOrNull()
        home.visibility = if (entry == null) View.VISIBLE else View.GONE
        if (entry == null) {
            onHomeShown()
            return
        }

        val view = inflater.inflate(R.layout.screen_settings, host, false)
        val title = view.findViewById<TextView>(R.id.settingsTitle)
        title.setText(entry.page.title)
        if (entry.page == Page.SETTINGS) {
            title.typeface = activity.resources.getFont(R.font.unbounded_bold)
            title.letterSpacing = 0.3f
        }
        view.findViewById<View>(R.id.settingsBack).setOnClickListener { back() }
        view.accessibilityPaneTitle = activity.getString(
            if (entry.page == Page.SETTINGS) R.string.fixer_settings else entry.page.title,
        )
        val body = view.findViewById<LinearLayout>(R.id.settingsBody)
        when (entry.page) {
            Page.SETTINGS -> buildSettings(body)
            Page.DOMAIN -> buildDomain(body)
            Page.DOMAIN_HELP -> buildDomainHelp(body)
            Page.CHECK -> buildCheck(body)
            Page.REPORT -> buildReport(body)
            Page.ABOUT -> buildAbout(body)
        }
        host.addView(view)
        screen = view
        view.requestFocus()
        if (entry.scrollY > 0) {
            val scroll = view.findViewById<ScrollView>(R.id.settingsScroll)
            scroll.post { scroll.scrollTo(0, entry.scrollY) }
        }
    }

    private fun buildSettings(body: LinearLayout) {
        body.append(label(R.string.fixer_settings, 28f, heading = true))
        val hasRelay = FixVpnService.tgDomain(activity).isNotEmpty()
        body.section(R.string.settings_network,
            Row(R.drawable.ic_settings_globe, R.string.relay_settings, string(R.string.settings_domain_subtitle)) {
                open(Page.DOMAIN)
            },
            Row(R.drawable.ic_telegram, R.string.telegram_add_proxy,
                string(if (hasRelay) R.string.settings_telegram_subtitle else R.string.settings_telegram_needs_domain),
                external = hasRelay,
            ) { if (hasRelay) addProxy() else open(Page.DOMAIN) },
        )
        body.section(R.string.settings_support,
            Row(R.drawable.ic_settings_probe, R.string.settings_check, string(R.string.settings_check_subtitle)) {
                open(Page.CHECK)
            },
            Row(R.drawable.ic_settings_report, R.string.report_settings, string(R.string.settings_report_subtitle)) {
                open(Page.REPORT)
            },
        )
        body.section(R.string.settings_project,
            Row(R.drawable.ic_info, R.string.settings_about, version) { open(Page.ABOUT) },
        )
    }

    private fun buildDomain(body: LinearLayout) {
        val view = inflater.inflate(R.layout.settings_domain, body, true)
        val input = view.findViewById<EditText>(R.id.domainInput)
        val note = view.findViewById<TextView>(R.id.domainNote)
        input.setText(domainDraft ?: FixVpnService.tgDomain(activity))
        input.setSelection(input.text.length)
        val save = {
            val domain = normalizeDomain(input.text.toString())
            if (domain == null) {
                note.setText(R.string.settings_domain_error)
                note.setTextColor(activity.getColor(R.color.settings_blue))
            } else {
                saveDomain(domain)
                back()
            }
        }
        view.findViewById<View>(R.id.domainSave).setOnClickListener { save() }
        input.setOnEditorActionListener { _, action, event ->
            val done = action == EditorInfo.IME_ACTION_DONE ||
                (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_UP)
            if (done) save()
            done
        }
        view.findViewById<View>(R.id.domainHelp).setOnClickListener { open(Page.DOMAIN_HELP) }
        view.findViewById<View>(R.id.domainReset).setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            saveDomain("")
            back()
        }
    }

    private fun buildDomainHelp(body: LinearLayout) {
        val steps = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.card)
            setPadding(dp(18), dp(18), dp(18), dp(18))
        }
        steps.append(label(R.string.settings_domain_help_download, 16f, heading = true))
        steps.append(label(R.string.settings_domain_help_download_detail), top = 5)
        steps.append(label(R.string.settings_domain_help_address, 16f, heading = true), top = 18)
        steps.append(label(R.string.settings_domain_help_address_detail), top = 5)
        body.append(steps)
    }

    private fun buildCheck(body: LinearLayout) {
        body.append(label(R.string.settings_check_intro))
        val status = label(R.string.settings_check_results, 16f, heading = true).apply {
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        val results = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val run = button(R.string.settings_check_start)
        fun refresh() {
            val running = Diagnostics.probesRunning()
            run.isEnabled = !running
            run.alpha = if (running) 0.65f else 1f
            run.setText(if (running) R.string.settings_check_running else R.string.settings_check_start)
            val readings = if (running) emptyList() else Diagnostics.latestProbeResults(activity)
            status.visibility = if (readings.isEmpty()) View.GONE else View.VISIBLE
            results.removeAllViews()
            readings.forEach { (host, reading) -> results.append(probeResult(host, reading), top = 8) }
            if (running) run.afterProbes(::refresh)
        }
        run.setOnClickListener {
            Diagnostics.runProbes(activity) {}
            refresh()
        }
        body.append(run, top = 22)
        body.append(button(R.string.settings_report_open, primary = false) {
            if (history.getOrNull(history.lastIndex - 1)?.page == Page.REPORT) back()
            else open(Page.REPORT)
        }, top = 8)
        body.append(status, top = 20)
        body.append(results)
        refresh()
    }

    private fun probeResult(host: String, reading: ProbeReading): View = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundResource(R.drawable.card)
        setPadding(dp(14), dp(12), dp(14), dp(12))
        val ipv6 = host.endsWith("_aaaa")
        val name = if (ipv6) activity.getString(R.string.settings_probe_ipv6, host.removeSuffix("_aaaa")) else host
        append(label(name, 15f, heading = true))
        if (reading is ProbeReading.Skipped) {
            append(label(R.string.settings_probe_skipped, 14f), top = 4)
        } else if (reading is ProbeReading.Measured) {
            val summary = when {
                reading.deadlineExceeded -> string(R.string.settings_probe_incomplete)
                ipv6 -> null
                reading.connectAll == 0 -> string(R.string.settings_probe_no_address)
                reading.connectChecked != null && reading.connectChecked < reading.connectAll -> activity.getString(
                    R.string.settings_probe_tcp_incomplete, reading.connectChecked, reading.connectAll, reading.connectOk,
                )
                reading.connectChecked == null && reading.connectOk < reading.connectAll -> activity.getString(
                    R.string.settings_probe_tcp_legacy, reading.connectOk,
                )
                reading.connectOk == 0 -> activity.getString(R.string.settings_probe_tcp_failed, reading.connectAll)
                reading.connectOk == reading.connectAll -> activity.getString(R.string.settings_probe_tcp_ok, reading.connectOk, reading.connectAll)
                else -> activity.getString(R.string.settings_probe_tcp_partial, reading.connectOk, reading.connectAll)
            }
            if (summary != null) append(label(summary, 14f), top = 4)
            if (!reading.deadlineExceeded) {
                val dns = activity.getString(R.string.settings_probe_dns_details, dnsStatus(reading.system), dnsStatus(reading.doh))
                append(label(dns, 13f), top = 4)
                val source = when (reading.connectSource) {
                    "sys" -> R.string.settings_probe_via_system
                    "doh" -> R.string.settings_probe_via_doh
                    else -> null
                }
                if (source != null) append(label(source, 13f), top = 4)
            }
        }
    }

    private fun dnsStatus(outcome: DnsOutcome): String = when (outcome.rcode) {
        "noerror" -> if (outcome.answers > 0) activity.getString(R.string.settings_probe_dns_answers, outcome.answers)
            else string(R.string.settings_probe_dns_empty)
        "nxdomain" -> string(R.string.settings_probe_dns_missing)
        "refused" -> string(R.string.settings_probe_dns_refused)
        "timeout" -> string(R.string.settings_probe_dns_timeout)
        else -> string(R.string.settings_probe_dns_error)
    }

    private fun buildReport(body: LinearLayout) {
        val checking = Diagnostics.probesRunning()
        var snapshot = report()
        body.append(label(R.string.settings_report_intro))
        val send = button(R.string.settings_report_send) { shareReport(snapshot) }
        val copy = button(R.string.settings_report_copy, primary = false) {
            activity.getSystemService(ClipboardManager::class.java)
                .setPrimaryClip(ClipData.newPlainText(string(R.string.settings_report_clip_label), snapshot))
            Toast.makeText(activity, R.string.settings_report_copied, Toast.LENGTH_SHORT).show()
        }
        val progress = label(R.string.settings_check_running).apply {
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            visibility = if (checking) View.VISIBLE else View.GONE
        }
        body.append(progress, top = 12)
        body.append(send, top = 20)
        body.append(copy, top = 8)
        if (history.none { it.page == Page.CHECK }) {
            body.append(button(R.string.settings_check, primary = false) { open(Page.CHECK) }, top = 4)
        }
        val preview = TextView(activity).apply {
            setBackgroundResource(R.drawable.card)
            setPadding(dp(16), dp(16), dp(16), dp(16))
            setTextColor(activity.getColor(R.color.settings_secondary))
            setTextIsSelectable(true)
            text = snapshot
            textSize = 12f
            typeface = Typeface.MONOSPACE
        }
        body.append(preview, top = 16)
        if (checking) {
            send.isEnabled = false
            send.alpha = 0.65f
            copy.isEnabled = false
            preview.afterProbes {
                snapshot = report()
                preview.text = snapshot
                progress.visibility = View.GONE
                send.isEnabled = true
                send.alpha = 1f
                copy.isEnabled = true
            }
        }
    }

    private fun View.afterProbes(action: () -> Unit) {
        if (Diagnostics.probesRunning()) {
            postDelayed({ if (isAttachedToWindow) afterProbes(action) }, 250)
        } else {
            action()
        }
    }

    private fun buildAbout(body: LinearLayout) {
        val icon = ImageView(activity).apply {
            setImageResource(R.drawable.mascot_icon)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        body.addView(icon, LinearLayout.LayoutParams(dp(72), dp(72)).apply { gravity = Gravity.CENTER_HORIZONTAL })
        body.append(label(R.string.brand_name, 22f, heading = true).apply { gravity = Gravity.CENTER }, top = 12)
        body.append(label(R.string.settings_version).apply {
            text = version
            gravity = Gravity.CENTER
        }, top = 4)
        if (Updater.enabled) {
            val check = button(R.string.settings_update_check, click = checkUpdates)
            val feedback = label(R.string.settings_update_running).apply {
                accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            }
            body.append(check, top = 24)
            body.append(feedback, top = 8)
            updateButton = check
            updateStatus = feedback
            refreshUpdateFeedback()
        }
        body.group(top = 28,
            Row(R.drawable.ic_settings_external, R.string.settings_source, "GitHub", external = true) {
                openLink(R.string.project_url)
            },
            Row(R.drawable.ic_settings_report, R.string.settings_license, string(R.string.settings_license_mit), external = true) {
                openLink(R.string.settings_license_url)
            },
        )
    }

    private fun report() = Diagnostics.buildReport(activity, FixVpnService.running)

    private fun openLink(link: Int) {
        try {
            activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(string(link))))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(activity, R.string.settings_link_unavailable, Toast.LENGTH_SHORT).show()
        }
    }

    private fun label(text: Int, size: Float = 15f, heading: Boolean = false) = label(string(text), size, heading)

    private fun label(text: CharSequence, size: Float = 15f, heading: Boolean = false) = TextView(activity).apply {
        this.text = text
        textSize = size
        setTextColor(activity.getColor(if (heading) R.color.text_primary else R.color.settings_secondary))
        setLineSpacing(dp(2).toFloat(), 1f)
        isAccessibilityHeading = heading
        if (heading) typeface = activity.resources.getFont(R.font.inter_medium)
    }

    private fun button(text: Int, primary: Boolean = true, click: (() -> Unit)? = null) = Button(
        activity, null, 0, if (primary) R.style.SettingsButton else R.style.SettingsButton_Secondary,
    ).apply {
        setText(text)
        if (click != null) setOnClickListener { click() }
    }

    private fun LinearLayout.section(title: Int, vararg rows: Row) {
        append(label(title, 12f, heading = true).apply {
            setTextColor(activity.getColor(R.color.settings_secondary))
            letterSpacing = 0.08f
            isAllCaps = true
            setPaddingRelative(dp(4), 0, 0, 0)
        }, top = 22)
        group(8, *rows)
    }

    private fun LinearLayout.group(top: Int, vararg rows: Row) {
        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.card)
            clipToOutline = true
        }
        rows.forEachIndexed { index, row ->
            if (index > 0) {
                card.addView(View(activity).apply {
                    setBackgroundColor(activity.getColor(R.color.stroke))
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }, LinearLayout.LayoutParams(MATCH_PARENT, dp(1)).apply {
                    marginStart = dp(67)
                    marginEnd = dp(14)
                })
            }
            val item = inflater.inflate(R.layout.item_setting, card, false)
            item.findViewById<ImageView>(R.id.settingIcon).setImageResource(row.icon)
            item.findViewById<TextView>(R.id.settingName).setText(row.title)
            item.findViewById<TextView>(R.id.settingSubtitle).text = row.subtitle
            if (row.external) {
                item.findViewById<ImageView>(R.id.settingArrow).setImageResource(R.drawable.ic_settings_external)
            }
            item.setOnClickListener { row.click() }
            card.addView(item)
        }
        append(card, top)
    }

    private fun LinearLayout.append(view: View, top: Int = 0) {
        addView(view, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(top) })
    }

    private fun string(res: Int) = activity.getString(res)

    private fun dp(value: Int) = (value * activity.resources.displayMetrics.density + 0.5f).toInt()

    private fun normalizeDomain(input: String): String? {
        val entered = input.trim().lowercase(Locale.ROOT)
        if (entered.isEmpty()) return ""
        val host = entered.removePrefix("https://").removePrefix("http://").substringBefore('/')
            .substringBefore('?').substringBefore('#').removeSuffix(".")
        val isWorker = host == "workers.dev" || host.endsWith(".workers.dev")
        val domain = if (isWorker) host else host.replace(LEGACY_RELAY_PREFIX, "")
        return domain.takeIf { DOMAIN.matches(it) && (!isWorker || it.count { char -> char == '.' } == 3) }
    }

    private companion object {
        const val STATE_PAGES = "settings_pages"
        const val STATE_SCROLL = "settings_scroll"
        const val STATE_DOMAIN = "settings_domain"
        val DOMAIN = Regex("^(?=.{1,253}$)([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z][a-z0-9-]{0,61}[a-z0-9]$")
        val LEGACY_RELAY_PREFIX = Regex("^(kws\\d+|tg)\\.")
    }
}
