package com.ecs.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.ecs.core.model.Confidence
import com.ecs.core.model.ErrorRecord
import com.ecs.core.model.RecordStatus
import com.ecs.core.model.Src

/**
 * 落库。主键是 uid = "{paper}#{id}"：
 * id 只含题号+空序，跨卷必然重复，单靠它做主键会把不同卷的同号题覆盖掉。
 *
 * 列分两半，和 [ErrorRecord] 一致：上半是原始数据，下半是分析产出。
 * 分析可以重跑，原始数据不动。
 */
@Entity(
    tableName = "records",
    indices = [
        Index("branch"), Index("status"), Index("paper"),
        Index("batch"), Index("created_at"),
    ],
)
data class RecordEntity(
    @PrimaryKey val uid: String,
    val id: String,
    val paper: String,
    val no: Int,
    val slot: Int,
    val batch: String,
    @ColumnInfo(name = "src_ref") val srcRef: String?,
    val stem: String?,
    val given: String?,
    val answer: String?,
    val confidence: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    val branch: String?,
    @ColumnInfo(name = "form_shape") val formShape: String?,
    val basis: String?,
    @ColumnInfo(name = "form_context") val formContext: String?,
    val note: String?,
    val status: String,
)

fun ErrorRecord.toEntity(): RecordEntity = RecordEntity(
    uid = uid,
    id = id,
    paper = src.paper,
    no = src.no,
    slot = src.slot,
    batch = src.batch,
    srcRef = srcRef,
    stem = stem,
    given = given,
    answer = answer,
    confidence = confidence.label,
    createdAt = createdAt,
    branch = branch,
    formShape = formShape,
    basis = basis,
    formContext = formContext,
    note = note,
    status = status.label,
)

fun RecordEntity.toModel(): ErrorRecord = ErrorRecord(
    id = id,
    src = Src(paper = paper, no = no, slot = slot, batch = batch),
    srcRef = srcRef,
    stem = stem,
    given = given,
    answer = answer,
    confidence = Confidence.fromLabel(confidence) ?: Confidence.WRONG,
    createdAt = createdAt,
    branch = branch,
    formShape = formShape,
    basis = basis,
    formContext = formContext,
    note = note,
    status = RecordStatus.fromLabel(status) ?: RecordStatus.PENDING,
)
