/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.privacyhistory

import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/** A visual rule created by Privacy Studio. Legacy line-based rules remain supported. */
data class PrivateHistoryRule(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val profile: String = DEFAULT_PROFILE,
    val matcher: Matcher,
    val value: String,
    val queryParameter: String = "",
    val action: Action = Action.BLOCK,
    val enabled: Boolean = true,
    val expiresAtEpochMillis: Long = 0L,
    val clearCookies: Boolean = false,
    val clearCache: Boolean = false,
    val clearDownloads: Boolean = false,
    val closeTab: Boolean = false,
) {
    val isDestructive: Boolean
        get() = clearCookies || clearCache || clearDownloads || closeTab

    fun isExpired(nowEpochMillis: Long): Boolean =
        expiresAtEpochMillis > 0L && nowEpochMillis >= expiresAtEpochMillis

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("profile", profile)
        put("matcher", matcher.name)
        put("value", value)
        put("queryParameter", queryParameter)
        put("action", action.name)
        put("enabled", enabled)
        put("expiresAtEpochMillis", expiresAtEpochMillis)
        put("clearCookies", clearCookies)
        put("clearCache", clearCache)
        put("clearDownloads", clearDownloads)
        put("closeTab", closeTab)
    }

    enum class Matcher {
        DOMAIN,
        DOMAIN_EXCEPT_ROOT,
        PATH_PREFIX,
        URL_CONTAINS,
        TITLE_CONTAINS,
        QUERY_PARAMETER,
        REGEX,
        EXACT_URL,
    }

    enum class Action {
        ALLOW,
        BLOCK,
        COLLAPSE_TO_ROOT,
    }

    companion object {
        const val DEFAULT_PROFILE = "Default"

        fun encode(rules: List<PrivateHistoryRule>): String = JSONArray().apply {
            rules.forEach { put(it.toJson()) }
        }.toString()

        fun decode(raw: String): List<PrivateHistoryRule> = runCatching {
            val array = JSONArray(raw.ifBlank { "[]" })
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val matcher = enumValueOrNull<Matcher>(item.optString("matcher")) ?: continue
                    val action = enumValueOrNull<Action>(item.optString("action")) ?: Action.BLOCK
                    val value = item.optString("value").trim()
                    if (value.isBlank()) continue
                    add(
                        PrivateHistoryRule(
                            id = item.optString("id").ifBlank { UUID.randomUUID().toString() },
                            name = item.optString("name").ifBlank { value },
                            profile = item.optString("profile").ifBlank { DEFAULT_PROFILE },
                            matcher = matcher,
                            value = value,
                            queryParameter = item.optString("queryParameter"),
                            action = action,
                            enabled = item.optBoolean("enabled", true),
                            expiresAtEpochMillis = item.optLong("expiresAtEpochMillis", 0L),
                            clearCookies = item.optBoolean("clearCookies", false),
                            clearCache = item.optBoolean("clearCache", false),
                            clearDownloads = item.optBoolean("clearDownloads", false),
                            closeTab = item.optBoolean("closeTab", false),
                        ),
                    )
                }
            }
        }.getOrDefault(emptyList())

        private inline fun <reified T : Enum<T>> enumValueOrNull(value: String): T? =
            runCatching { enumValueOf<T>(value) }.getOrNull()
    }
}

/** A decision is ephemeral. It is never written to disk or included in the aggregate counter. */
data class PrivateHistoryDecision(
    val action: PrivateHistoryRule.Action,
    val collapsedUri: String? = null,
    val matchedRule: PrivateHistoryRule? = null,
) {
    val suppressesOriginal: Boolean
        get() = action != PrivateHistoryRule.Action.ALLOW

    companion object {
        val ALLOW = PrivateHistoryDecision(PrivateHistoryRule.Action.ALLOW)
    }
}
