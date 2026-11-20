package com.october.fixer

/** Russian zones and Russian services outside them: system DNS and no DPI desync. No Android imports. */
internal object RuDomains {
    val tlds = setOf("ru", "su", "xn--p1ai", "moscow", "xn--80adxhks", "tatar", "xn--d1acj3b")

    /** Russian services outside Russian zones. */
    val extra = setOf(
        "sberbank.com", "vk.com", "vk.me", "vk.cc", "userapi.com", "vkuser.net", "vk-cdn.net", "mycdn.me",
        "yandex.net", "yandex.com", "yastatic.net", "yandexcloud.net", "ya.cc",
        "tbank-online.com", "tinkoff.com", "ozon.com", "ozone.travel", "avito.st", "wb.cc",
        "mts-bank.com", "gazprombank.com", "alfabank.com", "2gis.com", "kaspersky.com"
    )

    /** Names inside the zones above that still need the bypass; empty for now. */
    val forced = setOf<String>()

    fun isLocal(name: String): Boolean {
        val host = name.lowercase(java.util.Locale.ROOT).removeSuffix(".")
        if (host.isEmpty()) return false
        val labels = host.split('.')
        if (labels.any { it.isEmpty() }) return false
        if (forced.any { under(host, it) }) return false
        return labels.last() in tlds || extra.any { under(host, it) }
    }

    private fun under(host: String, zone: String): Boolean = host == zone || host.endsWith(".$zone")

    /** Entries for the byedpi host file: its lookup matches whole-label suffixes, so a bare zone covers subdomains. */
    fun noDesyncHosts(): List<String> = tlds.toList() + extra
}
