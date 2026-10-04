package com.litemusic.app.data

import kotlinx.serialization.json.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Displays confirmed remote fields; never guesses SVIP from account.vipType. */
internal fun membershipDescription(response: JsonObject, uid: Long, nowMs: Long): String? {
    if ((response["code"] as? JsonPrimitive)?.intOrNull != 200) return null
    val data = response["data"] as? JsonObject ?: return null
    val remoteUid = (data["userId"] as? JsonPrimitive)?.longOrNull
    if (remoteUid != null && remoteUid != uid) return null
    val remoteNow = (data["now"] as? JsonPrimitive)?.longOrNull?.takeIf { it > 0 } ?: nowMs
    val level = (data["redVipLevel"] as? JsonPrimitive)?.intOrNull?.takeIf { it > 0 }
    if (data["associator"] !is JsonObject && data["musicPackage"] !is JsonObject) return null
    val expiry = listOf("associator", "musicPackage").mapNotNull { key ->
        val item = data[key] as? JsonObject
        (item?.get("expireTime") as? JsonPrimitive)?.longOrNull?.takeIf { it > remoteNow }
    }.maxOrNull()
    if (expiry == null) return "暂无有效会员权益"
    val date = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(Date(expiry))
    return listOfNotNull(level?.let { "会员等级 $it" }, "权益至 $date").joinToString(" · ")
}
