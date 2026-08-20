/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.privacyhistory

import android.content.Context
import androidx.preference.PreferenceManager
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Locale

/** User-configurable rules deciding what must never be retained as browsing history. */
class PrivateHistoryRules(context: Context) {
    private val prefs = PreferenceManager.getDefaultSharedPreferences(context.applicationContext)

    val enabled: Boolean get() = prefs.getBoolean(KEY_ENABLED, true)
    val matchUrl: Boolean get() = prefs.getBoolean(KEY_MATCH_URL, true)
    val matchTitle: Boolean get() = prefs.getBoolean(KEY_MATCH_TITLE, true)
    val decodeUrl: Boolean get() = prefs.getBoolean(KEY_DECODE_URL, true)
    val wholeWordsOnly: Boolean get() = prefs.getBoolean(KEY_WHOLE_WORDS, false)

    fun shouldBlockUri(uri: String): Boolean {
        if (!enabled) return false
        val host = extractHost(uri)
        if (host != null && blockedDomains().any { domainMatches(host, it) }) return true
        return matchUrl && matchesText(normalizeUrl(uri))
    }

    fun shouldBlockTitle(title: String): Boolean =
        enabled && matchTitle && title.isNotBlank() && matchesText(title)

    /** Search terms are checked independently of the page-title switch. */
    fun shouldBlockSearchTerm(searchTerm: String): Boolean =
        enabled && searchTerm.isNotBlank() && matchesText(searchTerm)

    fun shouldBlockVisit(uri: String, title: String? = null, searchTerm: String? = null): Boolean =
        shouldBlockUri(uri) ||
            (title != null && shouldBlockTitle(title)) ||
            (searchTerm != null && shouldBlockSearchTerm(searchTerm))

    fun blockedDomains(): Set<String> = parseLines(prefs.getString(KEY_DOMAINS, "").orEmpty())
        .mapNotNull(::normalizeDomain)
        .toSet()

    fun blockedKeywords(): Set<String> = parseLines(prefs.getString(KEY_KEYWORDS, "").orEmpty())
        .map { normalizeCase(it) }
        .filter { it.isNotBlank() }
        .toSet()

    fun blockedRegexes(): List<Regex> = parseLines(prefs.getString(KEY_REGEX, "").orEmpty())
        .mapNotNull { pattern ->
            runCatching {
                if (isCaseSensitive()) Regex(pattern) else Regex(pattern, RegexOption.IGNORE_CASE)
            }.getOrNull()
        }

    private fun matchesText(rawText: String): Boolean {
        val text = normalizeCase(rawText)
        if (blockedKeywords().any { keyword -> keywordMatches(text, keyword) }) return true
        return blockedRegexes().any { it.containsMatchIn(rawText) }
    }

    private fun keywordMatches(text: String, keyword: String): Boolean {
        if (!wholeWordsOnly || keyword.any(Char::isWhitespace)) return text.contains(keyword)
        val escaped = Regex.escape(keyword)
        val options = if (isCaseSensitive()) emptySet() else setOf(RegexOption.IGNORE_CASE)
        return Regex("(?<![\\p{L}\\p{N}_])$escaped(?![\\p{L}\\p{N}_])", options).containsMatchIn(text)
    }

    private fun normalizeUrl(uri: String): String {
        if (!decodeUrl) return uri
        return runCatching { URLDecoder.decode(uri, StandardCharsets.UTF_8.name()) }.getOrDefault(uri)
    }

    private fun extractHost(uri: String): String? = runCatching {
        URI(uri).host?.trimEnd('.')?.lowercase(Locale.ROOT)
    }.getOrNull()

    private fun normalizeDomain(input: String): String? {
        val raw = input.trim().removePrefix("*.").trimEnd('/')
        if (raw.isBlank()) return null
        val withScheme = if (raw.contains("://")) raw else "https://$raw"
        val parsedHost = runCatching { URI(withScheme).host }.getOrNull()
        return (parsedHost ?: raw.substringBefore('/').substringBefore(':'))
            .trim().trimEnd('.').lowercase(Locale.ROOT).takeIf { it.isNotBlank() }
    }

    private fun domainMatches(host: String, domain: String): Boolean =
        host == domain || host.endsWith(".$domain")

    private fun isCaseSensitive(): Boolean = prefs.getBoolean(KEY_CASE_SENSITIVE, false)
    private fun normalizeCase(value: String): String = if (isCaseSensitive()) value else value.lowercase(Locale.ROOT)
    private fun parseLines(value: String): List<String> = value
        .split('\n', ',', ';')
        .map(String::trim)
        .filter { it.isNotBlank() && !it.startsWith('#') }

    companion object {
        const val KEY_ENABLED = "private_history_enabled"
        const val KEY_DOMAINS = "private_history_domains"
        const val KEY_KEYWORDS = "private_history_keywords"
        const val KEY_REGEX = "private_history_regex"
        const val KEY_MATCH_URL = "private_history_match_url"
        const val KEY_MATCH_TITLE = "private_history_match_title"
        const val KEY_DECODE_URL = "private_history_decode_url"
        const val KEY_CASE_SENSITIVE = "private_history_case_sensitive"
        const val KEY_WHOLE_WORDS = "private_history_whole_words"
        const val KEY_AUTO_UPDATE = "fenix_privacy_auto_update"
    }
}
