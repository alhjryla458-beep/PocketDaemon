package com.pocketdaemon.pocket_daemon

import android.content.Context
import android.net.Uri
import android.provider.ContactsContract
import android.util.Log

data class ResolvedCaller(
    val number: String,
    val displayName: String?,
    val trusted: TrustedContactConfig?,
    /** False when contacts lookup failed; callers must fail closed rather than auto-answer. */
    val lookupReliable: Boolean = true,
)

class ContactResolver(private val context: Context) {

    companion object {
        private const val TAG = "ContactResolver"
    }

    fun resolve(number: String): ResolvedCaller {
        val trusted = PocketDaemonApp.instance?.findTrustedContact(number)
        if (trusted != null) {
            Log.i(TAG, "Trusted contact match: ${trusted.name} (${trusted.number})")
            return ResolvedCaller(
                number = number,
                displayName = trusted.name.ifBlank { lookupContact(number).name.orEmpty() },
                trusted = trusted,
                lookupReliable = true,
            )
        }

        val lookup = lookupContact(number)
        Log.i(TAG, "Resolved $number → ${lookup.name ?: "unknown"} (not trusted, reliable=${lookup.reliable})")
        return ResolvedCaller(number = number, displayName = lookup.name, trusted = null, lookupReliable = lookup.reliable)
    }

    private data class ContactLookup(val name: String?, val reliable: Boolean)

    private fun lookupContact(number: String): ContactLookup {
        return try {
            val uri = Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                Uri.encode(number)
            )
            context.contentResolver.query(
                uri,
                arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME),
                null, null, null
            )?.use { cursor ->
                if (cursor == null) ContactLookup(null, false)
                else if (cursor.moveToFirst()) ContactLookup(
                    cursor.getString(cursor.getColumnIndexOrThrow(ContactsContract.PhoneLookup.DISPLAY_NAME)), true
                ) else ContactLookup(null, true)
            } ?: ContactLookup(null, false)
        } catch (e: Exception) {
            Log.w(TAG, "Contact lookup failed for $number: ${e.message}")
            ContactLookup(null, false)
        }
    }
}
