package com.training.crm.service

import com.training.crm.domain.ChannelDailyQuality
import com.training.crm.repo.ChannelDailyQualityRepo
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * 渠道投放质量服务。
 * 按「渠道 + 日期」聚合：线索数、有效报名数、退费数、投放成本。
 * - 线索登记：leads+1、cost+获客单价
 * - 报名转化：validEnrollments+1（有效报名）
 * - 退费完成：validEnrollments-1、refunds+1，防止同一线索退费后仍被重复算作有效报名
 */
@Service
class ChannelQualityService(private val repo: ChannelDailyQualityRepo) {

    private fun today(): String = LocalDate.now().toString()

    @Transactional
    fun recordLead(channel: String, cost: BigDecimal, counted: Boolean = true) {
        if (!counted) return
        val row = repo.findByChannelAndStatDate(channel, today())
            ?: repo.save(ChannelDailyQuality(channel = channel, statDate = today()))
        row.leads += 1
        row.cost = row.cost.add(cost)
        row.updatedAt = LocalDateTime.now()
        repo.save(row)
    }

    @Transactional
    fun recordValidEnrollment(channel: String) {
        val row = repo.findByChannelAndStatDate(channel, today())
            ?: repo.save(ChannelDailyQuality(channel = channel, statDate = today()))
        row.validEnrollments += 1
        row.updatedAt = LocalDateTime.now()
        repo.save(row)
    }

    /** 退费核销：有效报名 -1（从转化当天或最近有余额的日期扣减），退款数 +1（记在退款当天） */
    @Transactional
    fun revokeValidEnrollment(channel: String) {
        val todayRow = repo.findByChannelAndStatDate(channel, today())
            ?: repo.save(ChannelDailyQuality(channel = channel, statDate = today()))
        val validRow = if (todayRow.validEnrollments > 0) todayRow
        else repo.findByChannel(channel).filter { it.validEnrollments > 0 }.maxByOrNull { it.statDate } ?: todayRow
        if (validRow.id != todayRow.id && validRow.validEnrollments > 0) {
            validRow.validEnrollments -= 1
            validRow.updatedAt = LocalDateTime.now()
            repo.save(validRow)
        } else if (todayRow.validEnrollments > 0) {
            todayRow.validEnrollments -= 1
        }
        todayRow.refunds += 1
        todayRow.updatedAt = LocalDateTime.now()
        repo.save(todayRow)
    }

    /** 渠道质量汇总：有效率 = 有效报名 / 线索；单条有效报名成本 = 成本 / 有效报名 */
    fun summary(): List<Map<String, Any?>> {
        return repo.findAll().groupBy { it.channel }.map { (channel, rows) ->
            val leads = rows.sumOf { it.leads }
            val valid = rows.sumOf { it.validEnrollments }
            val refunds = rows.sumOf { it.refunds }
            val cost = rows.fold(BigDecimal.ZERO) { a, r -> a.add(r.cost) }
            val validRate = if (leads == 0) BigDecimal.ZERO
            else BigDecimal(valid).divide(BigDecimal(leads), 4, RoundingMode.HALF_UP)
            val costPerValid = if (valid == 0) null
            else cost.divide(BigDecimal(valid), 2, RoundingMode.HALF_UP)
            mapOf(
                "channel" to channel,
                "leads" to leads,
                "validEnrollments" to valid,
                "refunds" to refunds,
                "validRate" to validRate,
                "cost" to cost,
                "costPerValidEnrollment" to costPerValid,
                "daily" to rows.map {
                    mapOf(
                        "date" to it.statDate, "leads" to it.leads,
                        "validEnrollments" to it.validEnrollments, "refunds" to it.refunds, "cost" to it.cost
                    )
                }
            )
        }.sortedBy { it["channel"] as String }
    }
}
