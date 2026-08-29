/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.privacyhistory

import android.content.Context
import androidx.preference.PreferenceManager
import java.net.IDN
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/** User-configurable rules deciding what must never be retained as browsing history. */
class PrivateHistoryRules(
    context: Context,
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
) {
    private val prefs = PreferenceManager.getDefaultSharedPreferences(context.applicationContext)

    val enabled: Boolean get() = prefs.getBoolean(KEY_ENABLED, true)
    val matchUrl: Boolean get() = prefs.getBoolean(KEY_MATCH_URL, true)
    val matchTitle: Boolean get() = prefs.getBoolean(KEY_MATCH_TITLE, true)
    val decodeUrl: Boolean get() = prefs.getBoolean(KEY_DECODE_URL, true)
    val wholeWordsOnly: Boolean get() = prefs.getBoolean(KEY_WHOLE_WORDS, false)

    fun decide(
        uri: String,
        title: String? = null,
        searchTerm: String? = null,
        includeTransientProtection: Boolean = true,
    ): PrivateHistoryDecision {
        if (!enabled || uri.isBlank()) return PrivateHistoryDecision.ALLOW
        if (includeTransientProtection &&
            (temporaryProtectionActive() || PrivateHistoryTabProtection.isProtectedUri(uri))
        ) {
            return PrivateHistoryDecision(PrivateHistoryRule.Action.BLOCK)
        }

        val rules = visualRules().filter(::isRuleActive)
        rules.firstOrNull { it.action == PrivateHistoryRule.Action.ALLOW && matchesRule(it, uri, title, searchTerm) }
            ?.let { return PrivateHistoryDecision(PrivateHistoryRule.Action.ALLOW, matchedRule = it) }

        rules.firstOrNull { it.action != PrivateHistoryRule.Action.ALLOW && matchesRule(it, uri, title, searchTerm) }
            ?.let { rule ->
                return PrivateHistoryDecision(
                    action = rule.action,
                    collapsedUri = if (rule.action == PrivateHistoryRule.Action.COLLAPSE_TO_ROOT) siteRoot(uri) else null,
                    matchedRule = rule,
                )
            }

        val host = extractHost(uri)
        if (host != null && blockedDomains().any { domainMatches(host, it) }) {
            return PrivateHistoryDecision(PrivateHistoryRule.Action.BLOCK)
        }
        if (matchUrl && matchesLegacyText(normalizeUrl(uri))) {
            return PrivateHistoryDecision(PrivateHistoryRule.Action.BLOCK)
        }
        if (title != null && matchTitle && title.isNotBlank() && matchesLegacyText(title)) {
            return PrivateHistoryDecision(PrivateHistoryRule.Action.BLOCK)
        }
        if (searchTerm != null && searchTerm.isNotBlank() && matchesLegacyText(searchTerm)) {
            return PrivateHistoryDecision(PrivateHistoryRule.Action.BLOCK)
        }
        return PrivateHistoryDecision.ALLOW
    }

    fun shouldBlockUri(uri: String): Boolean = decide(uri).suppressesOriginal

    fun shouldBlockTitle(title: String): Boolean =
        enabled && matchTitle && title.isNotBlank() && matchesLegacyText(title)

    /** Search terms are checked independently of the page-title switch. */
    fun shouldBlockSearchTerm(searchTerm: String): Boolean =
        enabled && searchTerm.isNotBlank() && matchesLegacyText(searchTerm)

    fun shouldBlockVisit(uri: String, title: String? = null, searchTerm: String? = null): Boolean =
        decide(uri, title, searchTerm).suppressesOriginal

    /** Whether a previously stored URL is old enough to be removed by the matched rule. */
    fun shouldRemoveStoredVisit(
        decision: PrivateHistoryDecision,
        lastVisitAt: Long,
        includeRestartRules: Boolean,
    ): Boolean = when (decision.action) {
        PrivateHistoryRule.Action.ALLOW -> false
        PrivateHistoryRule.Action.BLOCK,
        PrivateHistoryRule.Action.COLLAPSE_TO_ROOT,
        -> true
        PrivateHistoryRule.Action.FORGET_ON_RESTART -> includeRestartRules
        PrivateHistoryRule.Action.FORGET_AFTER -> {
            val retention = decision.matchedRule?.retentionMillis?.coerceAtLeast(MIN_RETENTION_MILLIS) ?: 0L
            retention > 0L && lastVisitAt <= nowEpochMillis() - retention
        }
    }

    /**
     * Returns whether an open tab belongs to an explicitly enabled close-tab rule.
     *
     * Tab restoration is independent from Places history, so this deliberately evaluates the
     * visual rule itself instead of relying on a history callback. Only URLs that match the rule
     * are removed from restored state, so a clean homepage remains open for DOMAIN_EXCEPT_ROOT.
     * An exact ALLOW rule keeps its normal precedence and can opt into tab removal explicitly.
     */
    fun shouldCloseTab(uri: String, title: String? = null, searchTerm: String? = null): Boolean {
        if (!enabled || uri.isBlank()) return false
        val rules = visualRules().filter(::isRuleActive)
        rules.firstOrNull {
            it.action == PrivateHistoryRule.Action.ALLOW && matchesRule(it, uri, title, searchTerm)
        }?.let { return it.closeTab }

        return rules.any { rule -> rule.closeTab && matchesRule(rule, uri, title, searchTerm) }
    }

    fun blockedDomains(): Set<String> = parseLines(prefs.getString(KEY_DOMAINS, "").orEmpty())
        .mapNotNull(::normalizeDomain)
        .toSet()

    fun blockedKeywords(): Set<String> = parseLines(prefs.getString(KEY_KEYWORDS, "").orEmpty())
        .map { normalizeCase(it) }
        .filter { it.isNotBlank() }
        .toSet()

    fun blockedRegexes(): List<Regex> = parseLines(prefs.getString(KEY_REGEX, "").orEmpty())
        .mapNotNull(::compileRegex)

    fun visualRules(): List<PrivateHistoryRule> =
        PrivateHistoryRule.decode(prefs.getString(KEY_VISUAL_RULES, "[]").orEmpty())

    fun activeProfiles(): Set<String> = parseLines(
        prefs.getString(KEY_ACTIVE_PROFILES, PrivateHistoryRule.DEFAULT_PROFILE).orEmpty(),
    ).toSet().ifEmpty { setOf(PrivateHistoryRule.DEFAULT_PROFILE) }

    fun saveVisualRules(rules: List<PrivateHistoryRule>) {
        prefs.edit().putString(KEY_VISUAL_RULES, PrivateHistoryRule.encode(rules)).apply()
    }

    fun addOrReplaceRule(rule: PrivateHistoryRule) {
        val existing = visualRules()
        saveVisualRules(existing.filterNot { it.id == rule.id } + rule)
        if (existing.none { it.id == rule.id } && rule.profile !in activeProfiles()) {
            setActiveProfiles(activeProfiles() + rule.profile)
        }
    }

    fun deleteRule(id: String) {
        saveVisualRules(visualRules().filterNot { it.id == id })
    }

    fun setActiveProfiles(profiles: Collection<String>) {
        val value = profiles.map(String::trim).filter(String::isNotBlank).distinct().joinToString("\n")
        prefs.edit().putString(KEY_ACTIVE_PROFILES, value.ifBlank { PrivateHistoryRule.DEFAULT_PROFILE }).apply()
    }

    fun setTemporaryMode(durationMillis: Long) {
        val until = if (durationMillis <= 0L) 0L else saturatedAdd(nowEpochMillis(), durationMillis)
        prefs.edit().putLong(KEY_TEMPORARY_UNTIL, until).apply()
    }

    fun clearTemporaryMode() {
        sessionProtection.set(false)
        prefs.edit().remove(KEY_TEMPORARY_UNTIL).apply()
    }

    fun temporaryProtectionActive(): Boolean =
        sessionProtection.get() || prefs.getLong(KEY_TEMPORARY_UNTIL, 0L) > nowEpochMillis()

    fun temporaryProtectionUntil(): Long = prefs.getLong(KEY_TEMPORARY_UNTIL, 0L)

    fun setSessionMode(enabled: Boolean) {
        sessionProtection.set(enabled)
    }

    fun sessionModeActive(): Boolean = sessionProtection.get()

    fun siteRoot(uri: String): String? = runCatching {
        val parsed = URI(uri)
        val scheme = parsed.scheme?.lowercase(Locale.ROOT) ?: return@runCatching null
        val host = canonicalizeHost(parsed.host.orEmpty()) ?: return@runCatching null
        URI(scheme, null, host, normalizedPort(scheme, parsed.port), "/", null, null).toASCIIString()
    }.getOrNull()

    private fun isRuleActive(rule: PrivateHistoryRule): Boolean =
        rule.enabled && !rule.isExpired(nowEpochMillis()) && rule.profile in activeProfiles()

    private fun matchesRule(
        rule: PrivateHistoryRule,
        uri: String,
        title: String?,
        searchTerm: String?,
    ): Boolean {
        val parsed = runCatching { URI(uri) }.getOrNull()
        val host = parsed?.host?.let(::canonicalizeHost)
        val path = parsed?.rawPath.orEmpty().ifBlank { "/" }
        val normalizedUri = normalizeUrl(uri)

        return when (rule.matcher) {
            PrivateHistoryRule.Matcher.DOMAIN ->
                host != null && normalizeDomain(rule.value)?.let { domainMatches(host, it) } == true
            PrivateHistoryRule.Matcher.DOMAIN_EXCEPT_ROOT ->
                host != null && normalizeDomain(rule.value)?.let { domainMatches(host, it) } == true &&
                    (path != "/" || !parsed.rawQuery.isNullOrBlank() || !parsed.rawFragment.isNullOrBlank())
            PrivateHistoryRule.Matcher.PATH_PREFIX -> pathPrefixMatches(host, path, rule.value)
            PrivateHistoryRule.Matcher.URL_CONTAINS ->
                matchUrl && keywordMatches(normalizeCase(normalizedUri), normalizeCase(rule.value))
            PrivateHistoryRule.Matcher.TITLE_CONTAINS ->
                matchTitle && !title.isNullOrBlank() && keywordMatches(normalizeCase(title), normalizeCase(rule.value))
            PrivateHistoryRule.Matcher.QUERY_PARAMETER ->
                queryMatches(parsed?.rawQuery, rule.queryParameter, rule.value) ||
                    (!searchTerm.isNullOrBlank() && rule.queryParameter.isBlank() &&
                        keywordMatches(normalizeCase(searchTerm), normalizeCase(rule.value)))
            PrivateHistoryRule.Matcher.REGEX -> {
                val haystack = listOfNotNull(
                    normalizedUri.takeIf { matchUrl },
                    title?.takeIf { matchTitle },
                    searchTerm,
                ).joinToString("\n")
                compileRegex(rule.value)?.containsMatchIn(haystack) == true
            }
            PrivateHistoryRule.Matcher.EXACT_URL -> exactUrlKey(uri) == exactUrlKey(rule.value)
        }
    }

    private fun pathPrefixMatches(host: String?, path: String, rawRule: String): Boolean {
        val value = rawRule.trim()
        if (value.startsWith('/')) return path.startsWith(value)
        val withScheme = if (value.contains("://")) value else "https://$value"
        val parsedRule = runCatching { URI(withScheme) }.getOrNull() ?: return false
        val ruleHost = canonicalizeHost(parsedRule.host.orEmpty()) ?: return false
        val rulePath = parsedRule.rawPath.orEmpty().ifBlank { "/" }
        return host != null && domainMatches(host, ruleHost) && path.startsWith(rulePath)
    }

    private fun queryMatches(rawQuery: String?, parameter: String, expected: String): Boolean {
        if (rawQuery.isNullOrBlank()) return false
        return rawQuery.split('&').any { pair ->
            val key = decodeComponent(pair.substringBefore('='))
            val value = decodeComponent(pair.substringAfter('=', ""))
            val keyMatches = parameter.isBlank() || normalizeCase(key) == normalizeCase(parameter.trim())
            keyMatches && keywordMatches(normalizeCase(value), normalizeCase(expected))
        }
    }

    private fun matchesLegacyText(rawText: String): Boolean {
        val text = normalizeCase(rawText)
        if (blockedKeywords().any { keyword -> keywordMatches(text, keyword) }) return true
        return blockedRegexes().any { it.containsMatchIn(rawText) }
    }

    private fun keywordMatches(text: String, keyword: String): Boolean {
        if (!wholeWordsOnly) return text.contains(keyword)
        val escaped = Regex.escape(keyword)
        val options = if (isCaseSensitive()) emptySet() else setOf(RegexOption.IGNORE_CASE)
        return Regex("(?<![\\p{L}\\p{N}_])$escaped(?![\\p{L}\\p{N}_])", options).containsMatchIn(text)
    }

    private fun compileRegex(pattern: String): Regex? = runCatching {
        if (isCaseSensitive()) Regex(pattern) else Regex(pattern, RegexOption.IGNORE_CASE)
    }.getOrNull()

    private fun normalizeUrl(uri: String): String {
        if (!decodeUrl) return uri
        var decoded = uri
        repeat(MAX_URL_DECODE_PASSES) {
            val next = try {
                URLDecoder.decode(decoded, StandardCharsets.UTF_8.name())
            } catch (_: IllegalArgumentException) {
                return decoded
            }
            if (next == decoded) return decoded
            decoded = next
        }
        return decoded
    }

    private fun decodeComponent(value: String): String = runCatching {
        URLDecoder.decode(value.replace("+", "%20"), StandardCharsets.UTF_8.name())
    }.getOrDefault(value)

    private fun exactUrlKey(value: String): String? = runCatching {
        val raw = if (value.contains("://")) value else "https://$value"
        val parsed = URI(raw)
        val scheme = parsed.scheme?.lowercase(Locale.ROOT) ?: return@runCatching null
        val host = canonicalizeHost(parsed.host.orEmpty()) ?: return@runCatching null
        val path = parsed.rawPath.orEmpty().ifBlank { "/" }
        URI(
            scheme,
            null,
            host,
            normalizedPort(scheme, parsed.port),
            path,
            parsed.rawQuery,
            parsed.rawFragment,
        ).toASCIIString()
    }.getOrNull()

    private fun extractHost(uri: String): String? = runCatching {
        canonicalizeHost(URI(uri).host.orEmpty())
    }.getOrNull()

    private fun normalizeDomain(input: String): String? {
        var raw = input.trim().trimEnd('/')
        if (raw.isBlank()) return null
        DOMAIN_WILDCARD_PREFIX.find(raw)?.let { match ->
            raw = match.groupValues[1] + raw.substring(match.range.last + 1)
        }
        raw = raw.trimStart('.')
        val withScheme = if (raw.contains("://")) raw else "https://$raw"
        val parsedHost = runCatching { URI(withScheme).host }.getOrNull()
        val authority = raw.substringAfter("://", raw).substringBefore('/').substringBefore('?')
            .substringBefore('#').substringAfterLast('@')
        val fallbackHost = if (authority.startsWith("[")) {
            authority.substringAfter('[').substringBefore(']')
        } else {
            authority.substringBefore(':')
        }
        return canonicalizeHost(parsedHost ?: fallbackHost)
    }

    private fun canonicalizeHost(value: String): String? = runCatching {
        IDN.toASCII(value.trim().trim('.'), IDN.USE_STD3_ASCII_RULES)
            .lowercase(Locale.ROOT)
            .takeIf { it.isNotBlank() }
    }.getOrNull()

    private fun domainMatches(host: String, domain: String): Boolean =
        host == domain || host.endsWith(".$domain")

    private fun normalizedPort(scheme: String, port: Int): Int = when {
        scheme == "https" && port == 443 -> -1
        scheme == "http" && port == 80 -> -1
        else -> port
    }

    private fun isCaseSensitive(): Boolean = prefs.getBoolean(KEY_CASE_SENSITIVE, false)
    private fun normalizeCase(value: String): String =
        if (isCaseSensitive()) value else value.lowercase(Locale.ROOT)

    private fun parseLines(value: String): List<String> = value.lineSequence()
        .map(String::trim)
        .filter { it.isNotBlank() && !it.startsWith('#') }
        .toList()

    companion object {
        private const val MAX_URL_DECODE_PASSES = 3
        private const val MIN_RETENTION_MILLIS = 60_000L
        private val DOMAIN_WILDCARD_PREFIX = Regex(
            """^([a-z][a-z0-9+.-]*://)?\*\.""",
            RegexOption.IGNORE_CASE,
        )
        private val sessionProtection = AtomicBoolean(false)

        const val KEY_ENABLED = "private_history_enabled"
        const val KEY_DOMAINS = "private_history_domains"
        const val KEY_KEYWORDS = "private_history_keywords"
        const val KEY_REGEX = "private_history_regex"
        const val KEY_VISUAL_RULES = "private_history_visual_rules_v2"
        const val KEY_ACTIVE_PROFILES = "private_history_active_profiles"
        const val KEY_TEMPORARY_UNTIL = "private_history_temporary_until"
        const val KEY_MATCH_URL = "private_history_match_url"
        const val KEY_MATCH_TITLE = "private_history_match_title"
        const val KEY_DECODE_URL = "private_history_decode_url"
        const val KEY_CASE_SENSITIVE = "private_history_case_sensitive"
        const val KEY_WHOLE_WORDS = "private_history_whole_words"
        const val KEY_BIOMETRIC_LOCK = "private_history_biometric_lock"
        const val KEY_AUTO_UPDATE = "fenix_privacy_auto_update"

        private fun saturatedAdd(left: Long, right: Long): Long =
            if (right > Long.MAX_VALUE - left) Long.MAX_VALUE else left + right
    }
}
