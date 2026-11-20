package com.october.fixer

/** Result of one DNS attempt: `rcode` is noerror, nxdomain, servfail, refused, timeout or error. */
data class DnsOutcome(val rcode: String, val answers: Int, val ms: Int)

internal fun probeKey(host: String): String = "probe.${host.replace('.', '_').replace('-', '_')}"

/** `tcp:<ok>/<checked>` counts completed attempts. `total` gives the number of addresses selected. */
fun probeLine(
    host: String,
    sys: DnsOutcome,
    doh: DnsOutcome,
    connectOk: Int,
    connectAll: Int,
    connectSource: String? = null,
    connectChecked: Int? = null,
): String {
    val source = if (connectSource == "sys" || connectSource == "doh") " via:$connectSource" else ""
    val total = if (connectChecked != null && connectAll > 0) " total:$connectAll" else ""
    return ReportFormat.line(
        probeKey(host),
        "sys:${sys.rcode}/${sys.answers}/${sys.ms}ms doh:${doh.rcode}/${doh.answers}/${doh.ms}ms tcp:$connectOk/${connectChecked ?: connectAll}$source$total",
    )
}

internal sealed interface ProbeReading {
    data object Skipped : ProbeReading
    data class Measured(
        val system: DnsOutcome,
        val doh: DnsOutcome,
        val connectOk: Int,
        val connectAll: Int,
        val connectSource: String?,
        val connectChecked: Int? = null,
    ) : ProbeReading {
        val deadlineExceeded: Boolean
            get() = system == unfinished && doh == unfinished && connectAll == 0
    }
}

private val unfinished = DnsOutcome("timeout", 0, 0)
private val probeValue = Regex(
    "sys:([a-z]+)/([0-9]{1,5})/([0-9]{1,9})ms doh:([a-z]+)/([0-9]{1,5})/([0-9]{1,9})ms " +
        "tcp:([0-9]{1,5})/([0-9]{1,5})(?: via:(sys|doh))?(?: total:([0-9]{1,5}))?",
)

/** Reads both legacy stored results and results that mark the DNS source of the TCP addresses. */
internal fun parseProbeReading(value: String): ProbeReading? {
    if (value == "skip") return ProbeReading.Skipped
    val parts = probeValue.matchEntire(value)?.groupValues ?: return null
    val ok = parts[7].toInt()
    val checked = parts[8].toInt()
    val total = parts[10].toIntOrNull()
    if (ok > checked || (total != null && checked > total)) return null
    return ProbeReading.Measured(
        DnsOutcome(parts[1], parts[2].toInt(), parts[3].toInt()),
        DnsOutcome(parts[4], parts[5].toInt(), parts[6].toInt()),
        ok,
        total ?: checked,
        parts[9].ifEmpty { null },
        checked.takeIf { total != null },
    )
}

/** Vocabulary of [DnsOutcome.rcode] for a DNS header RCODE. */
fun rcodeName(rcode: Int): String = when (rcode) {
    0 -> "noerror"
    2 -> "servfail"
    3 -> "nxdomain"
    5 -> "refused"
    else -> "error"
}
