package rocks.pkb.plektra.inputmethod

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * The stored form of the hardware key remap rules: one JSON document, rules grouped by keyboard
 * profile id.
 *
 *     {"v":1,"profiles":{"titan2":{"rules":[
 *         {"match":"scan","scan":54,"key":60,"to":66},
 *         {"match":"key","key":66,"to":60}]}}}
 *
 * "key" and "to" are Android key codes, "scan" a Linux scan code. A rule that does not validate
 * ([KeyRemapRules.isValid]) is dropped on reading, so a damaged or hand-edited store can at worst
 * lose rules. A document from a newer version is not understood and not touched: it reads as no
 * rules, and [Store.writable] tells the settings layer not to replace it.
 */
object KeyRemapJson {
    const val VERSION = 1

    /** More profiles than there are keyboards is a corrupt store; the rest is ignored. */
    private const val MAX_PROFILES = 32

    class Store(val rulesByProfile: Map<String, List<KeyRemapRule>>, val writable: Boolean) {
        fun table(): KeyRemapTable {
            if (rulesByProfile.isEmpty()) return KeyRemapTable.EMPTY
            val compiled = HashMap<String, KeyRemapRules>()
            for ((profileId, rules) in rulesByProfile) {
                val profileRules = KeyRemapRules.compile(rules)
                if (!profileRules.isEmpty) compiled[profileId] = profileRules
            }
            return if (compiled.isEmpty()) KeyRemapTable.EMPTY else KeyRemapTable(compiled)
        }
    }

    private val EMPTY = Store(emptyMap(), writable = true)

    /** Profile ids are stored lower case ([DeviceSpecific] reports the Q25 as "Q25"). */
    fun profileKey(profileId: String): String = profileId.trim().lowercase()

    fun decode(json: String?): Store {
        if (json.isNullOrBlank()) return EMPTY
        try {
            val root = JSONObject(json)
            if (root.optInt("v", 0) > VERSION) return Store(emptyMap(), writable = false)
            val profiles = root.optJSONObject("profiles") ?: return EMPTY
            val rulesByProfile = LinkedHashMap<String, List<KeyRemapRule>>()
            val profileIds = profiles.keys()
            while (profileIds.hasNext() && rulesByProfile.size < MAX_PROFILES) {
                val profileId = profileIds.next()
                val stored = profiles.optJSONObject(profileId)?.optJSONArray("rules") ?: continue
                val rules = ArrayList<KeyRemapRule>()
                for (i in 0 until minOf(stored.length(), KeyRemapRules.MAX_RULES)) {
                    stored.optJSONObject(i)?.let(::decodeRule)?.let(rules::add)
                }
                val valid = KeyRemapRules.compile(rules).rules
                if (valid.isNotEmpty()) rulesByProfile[profileKey(profileId)] = valid
            }
            return Store(rulesByProfile, writable = true)
        } catch (_: JSONException) {
            return EMPTY
        }
    }

    private fun decodeRule(rule: JSONObject): KeyRemapRule? {
        val match = when (rule.optString("match")) {
            "scan" -> KeyMatch.SCAN
            "key" -> KeyMatch.KEY
            else -> return null
        }
        return KeyRemapRule(
            KeySource(match, rule.optInt("key", 0), rule.optInt("scan", 0)),
            rule.optInt("to", 0)
        )
    }

    /** The document for these rules; profiles without rules are left out. */
    fun encode(rulesByProfile: Map<String, List<KeyRemapRule>>): String {
        val profiles = JSONObject()
        for ((profileId, rules) in rulesByProfile) {
            if (rules.isEmpty()) continue
            val stored = JSONArray()
            for (rule in rules) {
                val entry = JSONObject()
                entry.put("match", if (rule.from.match == KeyMatch.SCAN) "scan" else "key")
                if (rule.from.scanCode != 0) entry.put("scan", rule.from.scanCode)
                if (rule.from.keyCode != 0) entry.put("key", rule.from.keyCode)
                entry.put("to", rule.toKeyCode)
                stored.put(entry)
            }
            profiles.put(profileKey(profileId), JSONObject().put("rules", stored))
        }
        return JSONObject().put("v", VERSION).put("profiles", profiles).toString()
    }
}
