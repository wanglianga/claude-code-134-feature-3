package com.training.crm.domain

import jakarta.persistence.*
import java.math.BigDecimal
import java.time.LocalDateTime

/**
 * 教材库存：按「科目 + 教材版本」维护校区/全局可用数量。
 * 报名发放（materialsDelivered=true）时出库；退费完成、教材交回时入库。
 */
@Entity
class MaterialStock(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long = 0,
    @Enumerated(EnumType.STRING)
    var subject: Subject = Subject.MATH,
    var textbookVersion: String = "",
    var available: Int = 0,
    var updatedAt: LocalDateTime = LocalDateTime.now()
)

/** 教材出入库流水 */
@Entity
class MaterialMovement(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long = 0,
    var stockId: Long = 0,
    @Enumerated(EnumType.STRING)
    var subject: Subject = Subject.MATH,
    var textbookVersion: String = "",
    /** OUT=报名发放出库，IN=退费交回入库 */
    var direction: String = "OUT",
    var quantity: Int = 1,
    /** 关联合同/退费单，便于追溯 */
    var contractId: Long? = null,
    var refundOrderId: Long? = null,
    var studentId: Long? = null,
    var createdAt: LocalDateTime = LocalDateTime.now()
)

/**
 * 渠道每日投放质量（按渠道+日期聚合，DataSeeder 预置基准）。
 * 退费完成时把该线索从「有效报名」中核销，validEnrollments 递减、refunds 递增，
 * 渠道有效率与单条有效报名成本随之更新，防止同一线索被重复算作有效报名。
 */
@Entity
class ChannelDailyQuality(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long = 0,
    var channel: String = "",
    var statDate: String = "",
    var leads: Int = 0,
    var validEnrollments: Int = 0,
    var refunds: Int = 0,
    var cost: BigDecimal = BigDecimal.ZERO,
    var updatedAt: LocalDateTime = LocalDateTime.now()
)
