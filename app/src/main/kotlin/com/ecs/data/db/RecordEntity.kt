package com.ecs.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.ecs.core.model.Confidence
import com.ecs.core.model.Difficulty
import com.ecs.core.model.ErrorRecord
import com.ecs.core.model.RecordStatus
import com.ecs.core.model.Section
import com.ecs.core.model.Src
import com.ecs.core.model.Verified

/**
 * L0 落库。主键是 uid = "{paper}#{id}"：
 * PRD 的 id 只含题型+题号+空序，跨卷必然重复，单靠它做主键会把不同卷的同号题覆盖掉。
 */
@Entity(
    tableName = "records",
    indices = [
        Index("kaodian"), Index("status"), Index("paper"),
        Index("section"), Index("batch"), Index("created_at"),
    ],
)
data class RecordEntity(
    @PrimaryKey val uid: String,
    val id: String,
    val paper: String,
    val section: String,
    val no: Int,
    val slot: Int,
    val batch: String,
    @ColumnInfo(name = "total_in_section") val totalInSection: Int?,
    @ColumnInfo(name = "src_ref") val srcRef: String?,
    val stem: String?,
    val given: String?,
    val answer: String?,
    val confidence: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    val eye: String?,
    val kaodian: String?,
    @ColumnInfo(name = "form_rule") val formRule: String?,
    @ColumnInfo(name = "form_context") val formContext: String?,
    val secondary: String,
    val difficulty: String?,
    @ColumnInfo(name = "co_error") val coError: String,
    @ColumnInfo(name = "tree_version") val treeVersion: String?,
    val verified: String,
    val note: String?,
    val status: String,
    @ColumnInfo(name = "next_check") val nextCheck: Long?,
)

private const val SEP = "|"

fun ErrorRecord.toEntity(): RecordEntity = RecordEntity(
    uid = uid,
    id = id,
    paper = src.paper,
    section = src.section.label,
    no = src.no,
    slot = src.slot,
    batch = src.batch,
    totalInSection = src.totalInSection,
    srcRef = srcRef,
    stem = stem,
    given = given,
    answer = answer,
    confidence = confidence.label,
    createdAt = createdAt,
    eye = eye,
    kaodian = kaodian,
    formRule = formRule,
    formContext = formContext,
    secondary = secondary.joinToString(SEP),
    difficulty = difficulty?.label,
    coError = coError.joinToString(SEP),
    treeVersion = treeVersion,
    verified = verified.label,
    note = note,
    status = status.label,
    nextCheck = nextCheck,
)

fun RecordEntity.toModel(): ErrorRecord = ErrorRecord(
    id = id,
    src = Src(
        paper = paper,
        section = Section.fromLabel(section) ?: Section.GF,
        no = no,
        slot = slot,
        batch = batch,
        totalInSection = totalInSection,
    ),
    srcRef = srcRef,
    stem = stem,
    given = given,
    answer = answer,
    confidence = Confidence.fromLabel(confidence) ?: Confidence.WRONG,
    createdAt = createdAt,
    eye = eye,
    kaodian = kaodian,
    formRule = formRule,
    formContext = formContext,
    secondary = secondary.split(SEP).filter { it.isNotBlank() },
    difficulty = difficulty?.let { Difficulty.fromLabel(it) },
    coError = coError.split(SEP).filter { it.isNotBlank() },
    treeVersion = treeVersion,
    verified = Verified.fromLabel(verified) ?: Verified.UNCHECKED,
    note = note,
    status = RecordStatus.fromLabel(status) ?: RecordStatus.INCOMPLETE,
    nextCheck = nextCheck,
)
