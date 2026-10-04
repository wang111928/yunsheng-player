package com.litemusic.app.data

import com.litemusic.data.db.AppDatabase

/** 供非 Koin 直接依赖的类（如 MediaStore 查询协程）访问数据库 */
object AppDatabaseHolder {
    val instance: AppDatabase? by lazy {
        runCatching { org.koin.core.context.GlobalContext.get().get<AppDatabase>() }.getOrNull()
    }
}
