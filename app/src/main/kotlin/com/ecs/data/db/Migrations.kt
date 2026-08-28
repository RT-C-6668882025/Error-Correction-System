package com.ecs.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v1 → v2：原题落库，新增 stem 列。
 *
 * 必须走真迁移，不能用 fallbackToDestructiveMigration——那会把已经录进去的题清空。
 * 旧记录的 stem 为 null，在原题页显示为「题干缺失，可手动补」。
 */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE records ADD COLUMN stem TEXT")
    }
}
