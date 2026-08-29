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

/**
 * v2 → v3：不再分题型，标注字段改名为分析字段。
 *
 * 这次改的是列而不是只加列，SQLite 只能重建表：建新表 → 搬数据 → 换名 → 重建索引。
 *
 * - 去掉 section / total_in_section：录入不再区分语法填空与完成句子
 * - 去掉 secondary / difficulty / co_error / tree_version / verified / next_check：
 *   它们服务的统计层、抽检、休眠、考点树都已经删掉了
 * - kaodian → branch：值也要跟着截断。老的 kaodian 是四层路径
 *   （词法/名词/后缀转换/-tion），而板块只到两层，截到前两段才对得上骨架
 * - form_rule → form_shape、eye → basis：只是改名，值原样搬
 * - status 的旧取值（活跃/休眠/归档/不完整）在新枚举里不存在，统一落到「待分析」，
 *   下次分析会重新算出正确的状态
 */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS records_new (
                uid TEXT NOT NULL PRIMARY KEY,
                id TEXT NOT NULL,
                paper TEXT NOT NULL,
                no INTEGER NOT NULL,
                slot INTEGER NOT NULL,
                batch TEXT NOT NULL,
                src_ref TEXT,
                stem TEXT,
                given TEXT,
                answer TEXT,
                confidence TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                branch TEXT,
                form_shape TEXT,
                basis TEXT,
                form_context TEXT,
                note TEXT,
                status TEXT NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            INSERT INTO records_new (
                uid, id, paper, no, slot, batch, src_ref, stem, given, answer,
                confidence, created_at, branch, form_shape, basis, form_context, note, status
            )
            SELECT
                uid, id, paper, no, slot, batch, src_ref, stem, given, answer,
                CASE WHEN confidence = '蒙对' THEN '蒙对' ELSE '错' END,
                created_at,
                CASE
                    WHEN kaodian IS NULL OR kaodian = '' THEN NULL
                    -- 只有一段：不是板块路径，判为未归类
                    WHEN instr(kaodian, '/') = 0 THEN NULL
                    -- 正好两段：本身就是板块，原样留下
                    WHEN instr(substr(kaodian, instr(kaodian, '/') + 1), '/') = 0 THEN kaodian
                    -- 三段以上：截到第二个 '/' 之前
                    ELSE substr(
                        kaodian, 1,
                        instr(kaodian, '/') + instr(substr(kaodian, instr(kaodian, '/') + 1), '/') - 1
                    )
                END,
                form_rule, eye, form_context, note, '待分析'
            FROM records
            """.trimIndent()
        )
        db.execSQL("DROP TABLE records")
        db.execSQL("ALTER TABLE records_new RENAME TO records")
        // 索引名必须和 Room 生成的一致，否则打开时 schema 校验不过
        db.execSQL("CREATE INDEX IF NOT EXISTS index_records_branch ON records (branch)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_records_status ON records (status)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_records_paper ON records (paper)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_records_batch ON records (batch)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_records_created_at ON records (created_at)")
    }
}
