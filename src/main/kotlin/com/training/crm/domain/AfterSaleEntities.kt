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
 * reasonCategory 决定审批后的业绩归因（attribution）与是否触发校区整改。
 */
@Entity
class RefundOrder(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long = 0,
    var studentId: Long = 0,
    var contractId: Long = 0,
    var reason: String = "",
    @Enumerated(EnumType.STRING)
    var reasonCategory: RefundReasonCategory = RefundReasonCategory.OTHER,
    /** 审批时按退费原因重新归因：顾问责任 / 校区责任 / 非责任退费 */
    @Enumerated(EnumType.STRING)
    var attribution: RefundAttribution? = null,
    @Column(length = 4000)
    var breakdownJson: String = "",
    var refundAmount: BigDecimal = BigDecimal.ZERO,
    @Enumerated(EnumType.STRING)
    var status: RefundStatus = RefundStatus.REQUESTED,
    var createdAt: LocalDateTime = LocalDateTime.now(),
    var decidedBy: String? = null,
    var decidedAt: LocalDateTime? = null
)

/** 校区整改说明：退费源于服务承诺不一致时生成，校区须提交整改内容 */
@Entity
class RectificationReport(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long = 0,
    var refundOrderId: Long = 0,
    var campusId: Long = 0,
    @Column(length = 2000)
    var content: String = "",
    /** PENDING=待提交 / SUBMITTED=已提交 */
    var status: String = "PENDING",
    var submittedBy: String? = null,
    var submittedAt: LocalDateTime? = null,
    var createdAt: LocalDateTime = LocalDateTime.now()
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
