package app.filmmaking.studio.sync

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** The Studio account this phone's local work belongs to (set once it is linked; never cleared on sign-out). */
data class LocalOwner(val userId: String, val email: String)

/** What may happen to this phone's local work for the account that just signed in. */
sealed interface LinkDecision {
    /** Local work already belongs to this account: sync. */
    data object Proceed : LinkDecision
    /** Nothing on this phone yet: link silently. */
    data object LinkEmpty : LinkDecision
    /** Unlinked local work exists: the person must agree before it is copied to this account. */
    data class AskToLink(val localItems: Int) : LinkDecision
    /** Local work belongs to a different account: sync stays off so nothing is mixed. */
    data class OtherAccount(val ownerEmail: String) : LinkDecision
}

object OwnershipGate {
    /**
     * [owner] is the recorded link. [syncedUserIds] are accounts that already have a sync base on this phone
     * (installs from before linking existed); a single one is treated as the owner.
     */
    fun decide(owner: LocalOwner?, syncedUserIds: Set<String>, userId: String, localItems: Int): LinkDecision {
        require(userId.isNotBlank()) { "No account id" }
        if (owner != null) return if (owner.userId == userId) LinkDecision.Proceed
            else LinkDecision.OtherAccount(owner.email.ifBlank { "another account" })
        if (syncedUserIds.isNotEmpty()) return if (syncedUserIds == setOf(userId)) LinkDecision.Proceed
            else LinkDecision.OtherAccount("another account")
        return if (localItems == 0) LinkDecision.LinkEmpty else LinkDecision.AskToLink(localItems)
    }
}

/** Raw PostgREST access as the signed-in user (implemented by [SupabaseRest]; faked in tests). */
interface RawRest {
    suspend fun request(method: String, path: String, body: String? = null, prefer: String? = null): Pair<Int, String>
}

/**
 * Checks the live project before any local work is linked or synced: every sync table exists, reads return no
 * other account's rows, and an insert claiming another user's id is refused by Row Level Security.
 * Returns null when everything holds, otherwise a plain-language reason (sync then stays off).
 */
object CloudAccessCheck {
    const val PROBE_TABLE = "ideas"

    suspend fun verify(rest: RawRest, userId: String, tables: List<String> = CloudMapping.TABLES): String? {
        for (t in tables) {
            val (code, _) = rest.request("GET", "$t?select=user_id&limit=1")
            if (code !in 200..299) return "The cloud table “$t” is not ready (HTTP $code). Sync stays off; your work stays on this phone."
            val (c2, foreign) = rest.request("GET", "$t?select=id&user_id=neq.$userId&limit=1")
            if (c2 !in 200..299) return "Could not check privacy rules on “$t” (HTTP $c2). Sync stays off."
            if (runCatching { JSONArray(foreign).length() }.getOrDefault(1) != 0)
                return "Privacy check failed: the cloud showed another account's rows in “$t”. Sync stays off."
        }
        val probeId = UUID.randomUUID().toString()
        val stranger = UUID.randomUUID().toString()
        val body = JSONObject().put("id", probeId).put("user_id", stranger).put("payload", JSONObject().put("rlsProbe", true))
        val (code, text) = rest.request("POST", PROBE_TABLE, body.toString(), "return=minimal")
        return when {
            code in 200..299 -> {
                rest.request("DELETE", "$PROBE_TABLE?id=eq.$probeId")
                "Privacy check failed: the cloud accepted a row for another account. Sync stays off."
            }
            code == 401 || code == 403 || "42501" in text -> null
            else -> "Could not confirm the cloud privacy rules (HTTP $code). Sync stays off; try again later."
        }
    }
}
