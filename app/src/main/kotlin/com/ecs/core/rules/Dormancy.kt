package com.ecs.core.rules

import com.ecs.core.model.ErrorRecord
import com.ecs.core.model.RecordStatus

/**
 * 4.4 考点休眠。无答题动作，基于时间：
 *   某考点 30 天无新增 → 休眠，next_check = +14 天
 *   休眠期满仍无新增   → 归档
 *   休眠期间出现新记录 → 活跃
 */
object Dormancy {

    const val DAY = 24L * 60 * 60 * 1000
    const val IDLE_DAYS = 30L
    const val DORMANT_DAYS = 14L

    data class Transition(val uid: String, val from: RecordStatus, val to: RecordStatus, val nextCheck: Long?)

    fun evaluate(records: List<ErrorRecord>, now: Long): List<Transition> {
        val out = mutableListOf<Transition>()
        records.filter { it.kaodian != null }
            .groupBy { it.kaodian!! }
            .forEach { (_, group) ->
                val lastSeen = group.maxOf { it.createdAt }
                val idle = now - lastSeen
                group.forEach { r ->
                    when (r.status) {
                        RecordStatus.ACTIVE ->
                            if (idle > IDLE_DAYS * DAY) {
                                out += Transition(r.uid, r.status, RecordStatus.DORMANT, now + DORMANT_DAYS * DAY)
                            }
                        RecordStatus.DORMANT -> {
                            if (idle <= IDLE_DAYS * DAY) {
                                out += Transition(r.uid, r.status, RecordStatus.ACTIVE, null)
                            } else if (r.nextCheck != null && now >= r.nextCheck) {
                                out += Transition(r.uid, r.status, RecordStatus.ARCHIVED, null)
                            }
                        }
                        else -> Unit
                    }
                }
            }
        return out
    }
}
