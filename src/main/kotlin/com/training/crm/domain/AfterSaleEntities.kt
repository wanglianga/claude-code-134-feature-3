package com.training.crm.domain

import jakarta.persistence.*
import java.math.BigDecimal
import java.time.LocalDateTime

/** 投诉 */
@Entity
class Complaint(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long = 0,
    var studentId: Long = 0,
    var content: String = "",
    @Enumerated(EnumType.STRING)
    var status: ComplaintStatus = ComplaintStatus.OPEN,
    var createdAt: LocalDateTime = LocalDateTime.now()
)

/**
 * 退费单。breakdownJson 保存完整计算明细：
 * 已上课时、赠课抵扣、教材费、优惠回收、分期手续费、顾问提成冻结、历史投诉赔付。
 *
 * 生命周期：申请 REQUESTED（顾问提成冻结；服务承诺不一致类原因须先补交整改说明）
 * → 财务审核通过 APPROVED 并打款（名额释放、未使用课表关闭、教材回收、提成按原因重归因、渠道有效报名核销）
 * → PAID；驳回 REJECTED（提成解冻）。
 */
@Entity
class RefundOrder(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long = 0,
    var studentId: Long = 0,
    var contractId: Long = 0,
    var reason: String = "",
    /** 结构化退费原因，决定业绩归因与提成处理方式 */
    @Enumerated(EnumType.STRING)
    var reasonCode: RefundReason = RefundReason.OTHER,
    @Enumerated(EnumType.STRING)
    var attribution: Attribution = Attribution.CUSTOMER,
    /** 是否需要校区提交整改说明（服务承诺不一致） */
    var rectificationRequired: Boolean = false,
    var rectificationNote: String? = null,
    var rectificationBy: String? = null,
    var rectificationAt: LocalDateTime? = null,
    @Column(length = 4000)
    var breakdownJson: String = "",
    var refundAmount: BigDecimal = BigDecimal.ZERO,
    @Enumerated(EnumType.STRING)
    var status: RefundStatus = RefundStatus.REQUESTED,
    var createdAt: LocalDateTime = LocalDateTime.now(),
    var decidedBy: String? = null,
    var decidedAt: LocalDateTime? = null,
    // ---- 审核通过后联动结果 ----
    /** 释放的班级名额数 */
    var seatsReleased: Int = 0,
    /** 关闭的未使用课表节数（班级因退费无人在读而取消的待上课时） */
    var lessonsCancelled: Int = 0,
    /** 教材是否已回收入库 */
    var materialsReturned: Boolean = false,
    /** 提成处理结果：CLAWED_BACK / SETTLED_PARTIAL */
    var commissionOutcome: String? = null,
    /** 实际追回提成 */
    var commissionClawedBack: BigDecimal = BigDecimal.ZERO,
    /** 重归因后结算给顾问的提成 */
    var commissionSettled: BigDecimal = BigDecimal.ZERO,
    /** 是否已核销渠道有效报名并更新投放质量 */
    var channelAdjusted: Boolean = false
)

/** 履约轨迹事件：校长视角的完整链路（线索→试听→报名→上课→退费） */
@Entity
class AuditEvent(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long = 0,
    var studentId: Long? = null,
    var leadId: Long? = null,
    var eventType: String = "",
    @Column(length = 2000)
    var detail: String = "",
    var actor: String = "system",
    var createdAt: LocalDateTime = LocalDateTime.now()
)
