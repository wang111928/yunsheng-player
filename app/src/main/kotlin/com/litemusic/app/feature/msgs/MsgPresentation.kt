package com.litemusic.app.feature.msgs

internal fun messageIsFromOther(senderId: Long?, chatUserId: Long): Boolean = senderId == chatUserId
