package com.litemusic.design.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import android.graphics.Typeface
import java.io.File

/** Reuse the device's CJK sans font without bundling a large font asset.
 * OEM theme fonts can override generic sans-serif; an explicit system font keeps the
 * application typography close to the approved layout. Devices without it retain sans-serif.
 */
private val NmlFontFamily: FontFamily by lazy {
    runCatching {
        listOf("/system/fonts/NotoSansCJK-Regular.ttc", "/system/fonts/NotoSansCJK-VF.ttc")
            .firstOrNull { File(it).isFile }
            ?.let { FontFamily(Typeface.createFromFile(it)) }
    }.getOrNull() ?: FontFamily.SansSerif
}

/**
 * 字号阶梯（设计规范 v2.0 · P1）：10 / 11.5 / 12 / 12.5 / 13.5 / 14.5 / 16 / 18 / 20 sp。
 * 映射到 Material3 语义槽位，feature 层统一用 MaterialTheme.typography.*
 */
val NmTypography = Typography(
    headlineLarge = TextStyle(fontFamily = NmlFontFamily, fontWeight = FontWeight.SemiBold, fontSize = 24.sp, lineHeight = 31.sp),
    headlineMedium = TextStyle(fontFamily = NmlFontFamily, fontWeight = FontWeight.SemiBold, fontSize = 19.sp, lineHeight = 26.sp),
    headlineSmall = TextStyle(fontFamily = NmlFontFamily, fontWeight = FontWeight.Medium, fontSize = 17.sp, lineHeight = 23.sp),
    titleLarge = TextStyle(fontFamily = NmlFontFamily, fontWeight = FontWeight.Medium, fontSize = 17.sp, lineHeight = 23.sp),
    titleMedium = TextStyle(fontFamily = NmlFontFamily, fontWeight = FontWeight.Medium, fontSize = 15.sp, lineHeight = 22.sp),
    titleSmall = TextStyle(fontFamily = NmlFontFamily, fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 19.sp),
    bodyLarge = TextStyle(fontFamily = NmlFontFamily, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontFamily = NmlFontFamily, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontFamily = NmlFontFamily, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 17.sp),
    labelLarge = TextStyle(fontFamily = NmlFontFamily, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp),
    labelMedium = TextStyle(fontFamily = NmlFontFamily, fontWeight = FontWeight.Normal, fontSize = 11.sp, lineHeight = 15.sp),
    labelSmall = TextStyle(fontFamily = NmlFontFamily, fontWeight = FontWeight.Normal, fontSize = 10.sp, lineHeight = 14.sp),
)
