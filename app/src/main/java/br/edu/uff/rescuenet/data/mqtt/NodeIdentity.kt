package br.edu.uff.rescuenet.data.mqtt

import android.content.Context
import java.util.UUID

object NodeIdentity {

    private const val PREFS_NAME = "rescuenet_node_identity"
    private const val KEY_NODE_ID = "node_id"

    fun getNodeId(context: Context): Long {
        val preferences = context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        val existingId = preferences.getLong(KEY_NODE_ID, -1L)

        if (existingId > 0L) {
            return existingId
        }

        val newId = UUID.randomUUID()
            .mostSignificantBits
            .and(Long.MAX_VALUE)
            .coerceAtLeast(1L)

        preferences.edit()
            .putLong(KEY_NODE_ID, newId)
            .apply()

        return newId
    }
}
