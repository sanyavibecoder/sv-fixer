package com.october.fixer

/** Diagnostics report grammar: only `key=value` lines from a fixed alphabet, bounded by [MAX_CHARS]. */
object ReportFormat {
    const val MAX_CHARS = 3800
    val LINE = Regex("^[a-z0-9_.]{1,48}=[A-Za-z0-9 _.,:/+-]{0,96}$")
    private val ALLOWED_VALUE = Regex("[A-Za-z0-9 _.,:/+-]")

    /** Every character outside the LINE value alphabet (including '=' and newline) becomes '?'. */
    fun clean(value: String, max: Int = 96): String =
        buildString {
            for (ch in value.take(max)) append(if (ALLOWED_VALUE.matches(ch.toString())) ch else '?')
        }

    fun line(key: String, value: Any?): String = "$key=${clean(value?.toString() ?: "none")}"

    /** Drops lines that do not match [LINE]; cuts whole lines from the end so the result fits [MAX_CHARS]. */
    fun render(lines: List<String>): String {
        val out = StringBuilder()
        for (l in lines) {
            if (!LINE.matches(l)) continue
            val extra = if (out.isEmpty()) l.length else l.length + 1
            if (out.length + extra > MAX_CHARS) break
            if (out.isNotEmpty()) out.append('\n')
            out.append(l)
        }
        return out.toString()
    }

    /**
     * Report order: what the cut by [MAX_CHARS] loses last is the least useful, so base lines, facts and probes
     * come first, then events, `tun.*` and `dns.*`.
     */
    fun assemble(
        base: List<String>, facts: List<String>, probes: List<String>,
        events: List<String>, tun: List<String>, dns: List<String>,
    ): String = render(base + facts + probes.ifEmpty { listOf("probe.state=not_run") } + events + tun + dns)

    /** Non-blank lines of [report] that do not match [LINE]. */
    fun foreign(report: String): List<String> =
        report.lines().filter { it.isNotBlank() && !LINE.matches(it) }
}
