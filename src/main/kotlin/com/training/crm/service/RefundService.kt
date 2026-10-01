package com.training.crm.service

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.training.crm.common.AuditService
import com.training.crm.common.BusinessException
import com.training.crm.common.LockService
import com.training.crm.common.bizFail
import com.training.crm.domain.*
import com.training.crm.repo.*
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

/**
 * 退费计算规则（家长报名两周后申请退费场景）：
 * - 课时单价 = 实付金额 / 付费课时数
 * - 基础退费 = 剩余付费课时 × 单价（赠课不退现）
 * - 赠课抵扣：consumeOrder=GIFT_FIRST 时，已消耗赠课按单价从应退中抵扣
 * - 教材费：教材已发放则全额扣除；审核打款、教材交回后回收入库
 * - 优惠回收：已上课时占比低于课包 discountKeepThreshold 时，全额回收优惠
 * - 分期手续费：报名满冷静期（默认14天）后申请退费，已回款分期的手续费不退（由机构承担成本回收）；冷静期内免收
 * - 历史投诉：每条未结投诉增加固定赔付（app.complaint-compensation）
 * - 顾问提成：申请即冻结；财务审核通过后按退费原因重新归因：
 *     · 服务承诺不一致（顾问责任）→ 全额追回，且校区须先提交整改说明
 *     · 教学/服务责任、客户侧原因 → 按已课消比例部分结算，其余追回
 * - 审核通过后联动：班级名额释放、班级无人在读时关闭未使用课表（老师排课减少）、
 *   教材回收入库、未收分期（含手续费）减免、渠道有效报名核销并更新投放质量。
 * - 退费金额以已收款（含手续费口径）为上限。
 */
@Service
class RefundService(
    private val studentRepo: StudentRepo,
    private val leadRepo: LeadRepo,
    private val contractRepo: ContractRepo,
    private val coursePackageRepo: CoursePackageRepo,
    private val attendanceRepo: AttendanceRepo,
    private val installmentRepo: InstallmentRepo,
    private val commissionRepo: CommissionRepo,
    private val complaintRepo: ComplaintRepo,
    private val refundOrderRepo: RefundOrderRepo,
    private val enrollmentRepo: EnrollmentRepo,
    private val lessonRepo: LessonRepo,
    private val clazzRepo: ClazzRepo,
    private val inventoryService: InventoryService,
    private val channelQualityService: ChannelQualityService,
    private val lockService: LockService,
    private val auditService: AuditService,
    @Value("\${app.complaint-compensation}") private val complaintCompensation: BigDecimal,
    @Value("\${app.refund-cooling-days}") private val coolingDays: Int
) {
    private val mapper = jacksonObjectMapper()

    data class RefundBreakdown(
        val contractId: Long,
        val paidLessons: Int,
        val giftLessons: Int,
        val consumedPaidLessons: Int,
        val consumedGiftLessons: Int,
        val remainingPaidLessons: Int,
        val consumedRatio: BigDecimal,
        val unitPrice: BigDecimal,
        val baseRefund: BigDecimal,
        val materialFeeDeducted: BigDecimal,
        val discountRecovered: BigDecimal,
        val giftValueOffset: BigDecimal,
        // 分期手续费
        val installmentFeeCharged: BigDecimal,
        val installmentFeeRecovered: BigDecimal,
        val coolingPeriodDays: Int,
        val contractAgeDays: Long,
        val afterCoolingPeriod: Boolean,
        // 投诉
        val openComplaints: Int,
        val complaintCompensation: BigDecimal,
        // 提成
        val commissionFrozen: BigDecimal,
        val commissionServiceRatio: BigDecimal,
        val attribution: Attribution,
        val rectificationRequired: Boolean,
        val commissionSettleEstimate: BigDecimal,
        val commissionClawEstimate: BigDecimal,
        // 收款与应退
        val totalReceived: BigDecimal,
        val unpaidInstallmentsWaived: BigDecimal,
        val refundAmount: BigDecimal
    )

    /** 退费试算（不落库）。appliedAt 可补录家长实际申请时间，reasonCode 决定提成归因。 */
    fun preview(studentId: Long, appliedAt: LocalDateTime?, reasonCode: RefundReason?): RefundBreakdown {
        val contract = activeContractOf(studentId)
        return compute(contract, appliedAt ?: LocalDateTime.now(), reasonCode ?: RefundReason.OTHER)
    }

    /** 退费申请：落库并冻结顾问提成。幂等键防重复申请。 */
    @Transactional
    fun request(
        studentId: Long, reasonCode: RefundReason, reasonText: String,
        appliedAt: LocalDateTime?, idemKey: String?, actor: String
    ): RefundOrder {
        val contract = activeContractOf(studentId)
        val applied = appliedAt ?: LocalDateTime.now()

        fun doCreate(): RefundOrder {
            val existing = refundOrderRepo.findByContractIdAndStatusIn(
                contract.id, listOf(RefundStatus.REQUESTED, RefundStatus.APPROVED)
            )
            if (existing.isNotEmpty()) bizFail("DUPLICATE", "已有进行中的退费单: #${existing.first().id}")
            val breakdown = compute(contract, applied, reasonCode)
            val order = refundOrderRepo.save(
                RefundOrder(
                    studentId = studentId,
                    contractId = contract.id,
                    reason = reasonText.ifBlank { reasonCode.desc },
                    reasonCode = reasonCode,
                    attribution = reasonCode.attribution,
                    rectificationRequired = reasonCode.rectificationRequired,
                    breakdownJson = mapper.writeValueAsString(breakdown),
                    refundAmount = breakdown.refundAmount,
                    createdAt = applied
                )
            )
            // 冻结顾问本单提成
            commissionRepo.findByContractId(contract.id).filter { it.status == CommissionStatus.ACCRUED }.forEach {
                it.status = CommissionStatus.FROZEN
                it.updatedAt = LocalDateTime.now()
                commissionRepo.save(it)
                auditService.record("COMMISSION_FROZEN", "顾问提成冻结：¥${it.amount}（合同#${contract.id}）", studentId = studentId, actor = actor)
            }
            auditService.record(
                "REFUND_REQUESTED",
                "退费申请：原因=${reasonCode.desc}（归因=${reasonCode.attribution}），应退 ¥${breakdown.refundAmount}" +
                    "（已上付费课时=${breakdown.consumedPaidLessons}，剩余=${breakdown.remainingPaidLessons}，账龄=${breakdown.contractAgeDays}天" +
                    "，手续费回收=${breakdown.installmentFeeRecovered}，需整改=${breakdown.rectificationRequired}）",
                studentId = studentId, actor = actor
            )
            return order
        }

        if (idemKey.isNullOrBlank()) return doCreate()
        val (existed, id) = lockService.idempotent("refund:$idemKey") { doCreate().id }
        return refundOrderRepo.findById(id).get()
    }

    /** 校区提交整改说明（服务承诺不一致类退费，财务审核前必须完成） */
    @Transactional
    fun submitRectification(refundId: Long, note: String, actor: String): RefundOrder {
        val order = mustGet(refundId)
        if (order.status != RefundStatus.REQUESTED) bizFail("INVALID_STATE", "退费单状态(${order.status})不能提交整改说明")
        if (!order.rectificationRequired) bizFail("RECTIFICATION_NOT_REQUIRED", "该退费原因不要求提交整改说明")
        if (note.isBlank()) bizFail("INVALID_ARGUMENT", "整改说明不能为空")
        order.rectificationNote = note
        order.rectificationBy = actor
        order.rectificationAt = LocalDateTime.now()
        refundOrderRepo.save(order)
        auditService.record("RECTIFICATION_SUBMITTED", "校区整改说明已提交：$note", studentId = order.studentId, actor = actor)
        return order
    }

    /** 财务审核通过并打款：名额释放、课表关闭、教材回收、分期减免、提成重归因、渠道核销 */
    @Transactional
    fun approve(refundId: Long, approver: String, materialsReturned: Boolean): RefundOrder {
        val order = mustGet(refundId)
        if (order.status != RefundStatus.REQUESTED) bizFail("INVALID_STATE", "退费单状态(${order.status})不能审批")
        if (order.rectificationRequired && order.rectificationNote.isNullOrBlank()) {
            bizFail("RECTIFICATION_REQUIRED", "服务承诺不一致类退费须由校区先提交整改说明，再提交财务审核")
        }

        val contract = contractRepo.findById(order.contractId).get()
        val pkg = coursePackageRepo.findById(contract.coursePackageId).get()

        order.status = RefundStatus.PAID
        order.decidedBy = approver
        order.decidedAt = LocalDateTime.now()

        // ---- 1. 合同 / 学员关闭 ----
        contract.status = ContractStatus.REFUNDED
        contractRepo.save(contract)
        val student = studentRepo.findById(order.studentId).get()
        student.status = StudentStatus.REFUNDED
        studentRepo.save(student)

        // ---- 2. 班级名额释放；无人在读的班级关闭未使用课表（老师排课减少）----
        var seats = 0
        var lessonsCancelled = 0
        val activeEnrollments = enrollmentRepo.findByStudentIdAndStatus(order.studentId, EnrollmentStatus.ACTIVE)
        activeEnrollments.forEach { en ->
            val clazz = clazzRepo.findById(en.clazzId).get()
            en.status = EnrollmentStatus.DROPPED
            enrollmentRepo.save(en)
            seats += 1
            // 该学员未使用的待上课出勤记录清除
            lessonRepo.findByClazzIdAndStatus(en.clazzId, LessonStatus.SCHEDULED).forEach { lesson ->
                attendanceRepo.findByLessonIdAndStudentId(lesson.id, order.studentId)?.let { att ->
                    if (att.status == AttendStatus.PENDING) attendanceRepo.delete(att)
                }
            }
            val stillActive = enrollmentRepo.findByClazzIdAndStatus(en.clazzId, EnrollmentStatus.ACTIVE).size
            auditService.record(
                "CLASS_SEAT_RELEASED",
                "退费释放名额：班级=${clazz.name}(#${en.clazzId})，剩余在读=$stillActive/${clazz.capacity}",
                studentId = order.studentId, actor = approver
            )
            // 班级已无在读学员 → 关闭剩余未使用课表，老师排课相应减少
            if (stillActive == 0) {
                clazz.status = "CLOSED"
                clazzRepo.save(clazz)
                val futureLessons = lessonRepo.findByClazzIdAndStatus(en.clazzId, LessonStatus.SCHEDULED)
                futureLessons.forEach { lesson ->
                    lesson.status = LessonStatus.CANCELLED
                    lessonRepo.save(lesson)
                    auditService.record(
                        "SCHEDULE_CLOSED",
                        "班级无人在读，关闭未使用课表：${clazz.name} ${lesson.startTime}~${lesson.endTime}（${lesson.topic}），老师排课减少1节",
                        studentId = order.studentId, actor = approver
                    )
                }
                lessonsCancelled += futureLessons.size
            }
        }
        order.seatsReleased = seats
        order.lessonsCancelled = lessonsCancelled

        // ---- 3. 教材回收入库 ----
        if (contract.materialsDelivered && materialsReturned) {
            inventoryService.receiveBack(pkg.subject, pkg.textbookVersion, contract.id, order.id, order.studentId)
            order.materialsReturned = true
            auditService.record(
                "MATERIAL_RETURNED",
                "退费交回教材并回收入库：${pkg.textbookVersion}（合同#${contract.id}）",
                studentId = order.studentId, actor = approver
            )
        }

        // ---- 4. 未收分期（本金+手续费）减免 ----
        installmentRepo.findByContractId(contract.id).filter { it.status == InstallmentStatus.PENDING }.forEach {
            it.status = InstallmentStatus.WAIVED
            installmentRepo.save(it)
        }

        // ---- 5. 顾问业绩按退费原因重新归因 ----
        val breakdown: RefundBreakdown = mapper.readValue(order.breakdownJson, RefundBreakdown::class.java)
        var clawedTotal = BigDecimal.ZERO
        var settledTotal = BigDecimal.ZERO
        var allFullSettled = true
        var anyPartial = false
        commissionRepo.findByContractId(contract.id).filter { it.status == CommissionStatus.FROZEN }.forEach { c ->
            val settle = c.amount.multiply(breakdown.commissionServiceRatio).setScale(2, RoundingMode.HALF_UP)
            when {
                order.attribution == Attribution.CONSULTANT || settle <= BigDecimal.ZERO -> {
                    c.status = CommissionStatus.CLAWED_BACK
                    c.settledAmount = BigDecimal.ZERO
                    c.attributionReason = order.reasonCode.name
                    clawedTotal = clawedTotal.add(c.amount)
                    allFullSettled = false
                }
                settle >= c.amount -> {
                    c.status = CommissionStatus.SETTLED
                    c.settledAmount = c.amount
                    c.attributionReason = order.reasonCode.name
                    settledTotal = settledTotal.add(c.amount)
                }
                else -> {
                    c.status = CommissionStatus.SETTLED_PARTIAL
                    c.settledAmount = settle
                    c.attributionReason = order.reasonCode.name
                    clawedTotal = clawedTotal.add(c.amount.subtract(settle))
                    settledTotal = settledTotal.add(settle)
                    allFullSettled = false
                    anyPartial = true
                }
            }
            c.updatedAt = LocalDateTime.now()
            commissionRepo.save(c)
        }
        order.commissionClawedBack = clawedTotal
        order.commissionSettled = settledTotal
        order.commissionOutcome = when {
            settledTotal <= BigDecimal.ZERO -> "CLAWED_BACK"
            anyPartial || !allFullSettled -> "SETTLED_PARTIAL"
            else -> "SETTLED"
        }
        auditService.record(
            "COMMISSION_REATTRIBUTED",
            "顾问业绩按退费原因(${order.reasonCode.name}/${order.attribution})重新归因：结算 ¥$settledTotal，追回 ¥$clawedTotal",
            studentId = order.studentId, actor = approver
        )
        if (clawedTotal > BigDecimal.ZERO) {
            auditService.record("COMMISSION_CLAWED_BACK", "顾问提成追回 ¥$clawedTotal（合同#${contract.id}）", studentId = order.studentId, actor = approver)
        }
        if (settledTotal > BigDecimal.ZERO) {
            auditService.record("COMMISSION_SETTLED_PARTIAL", "顾问提成按已课消比例结算 ¥$settledTotal（合同#${contract.id}）", studentId = order.studentId, actor = approver)
        }

        // ---- 6. 渠道有效报名核销，更新投放质量，防止同一线索重复算作有效报名 ----
        val lead = leadRepo.findById(student.leadId).orElse(null)
        if (lead != null) {
            lead.validEnrollment = false
            lead.enrollmentRefunded = true
            leadRepo.save(lead)
            channelQualityService.revokeValidEnrollment(lead.sourceChannel)
            order.channelAdjusted = true
            auditService.record(
                "CHANNEL_QUALITY_ADJUSTED",
                "渠道[${lead.sourceChannel}]核销有效报名1条并更新投放质量（线索#${lead.id}已退费，不再计为有效报名）",
                studentId = order.studentId, leadId = lead.id, actor = approver
            )
        }

        refundOrderRepo.save(order)
        auditService.record(
            "REFUND_PAID",
            "财务审核通过，退费完成：¥${order.refundAmount}（退费单#$refundId，审批人=$approver，释放名额=$seats，关闭课表=$lessonsCancelled 节）",
            studentId = order.studentId, actor = approver
        )
        return order
    }

    @Transactional
    fun reject(refundId: Long, approver: String, reason: String): RefundOrder {
        val order = mustGet(refundId)
        if (order.status != RefundStatus.REQUESTED) bizFail("INVALID_STATE", "退费单状态(${order.status})不能驳回")
        order.status = RefundStatus.REJECTED
        order.decidedBy = approver
        order.decidedAt = LocalDateTime.now()
        refundOrderRepo.save(order)
        // 解冻提成
        commissionRepo.findByContractId(order.contractId).filter { it.status == CommissionStatus.FROZEN }.forEach {
            it.status = CommissionStatus.ACCRUED
            it.updatedAt = LocalDateTime.now()
            commissionRepo.save(it)
        }
        auditService.record("REFUND_REJECTED", "退费驳回：$reason", studentId = order.studentId, actor = approver)
        return order
    }

    fun get(refundId: Long): RefundOrder = mustGet(refundId)

    private fun mustGet(refundId: Long): RefundOrder =
        refundOrderRepo.findById(refundId).orElseThrow { BusinessException("NOT_FOUND", "退费单不存在: $refundId") }

    private fun activeContractOf(studentId: Long): Contract {
        studentRepo.findById(studentId).orElseThrow { BusinessException("NOT_FOUND", "学员不存在: $studentId") }
        return contractRepo.findByStudentId(studentId).firstOrNull { it.status == ContractStatus.ACTIVE || it.status == ContractStatus.SUSPENDED }
            ?: bizFail("INVALID_STATE", "学员没有可退费的合同")
    }

    private fun compute(contract: Contract, appliedAt: LocalDateTime, reasonCode: RefundReason): RefundBreakdown {
        val pkg = coursePackageRepo.findById(contract.coursePackageId).get()
        val atts = attendanceRepo.findByContractId(contract.id)
        val consumedPaid = atts.sumOf { it.consumedPaid }
        val consumedGift = atts.sumOf { it.consumedGift }
        val remainingPaid = contract.paidLessons - consumedPaid
        val unitPrice = contract.finalAmount.divide(BigDecimal(contract.paidLessons), 2, RoundingMode.HALF_UP)
        val baseRefund = unitPrice.multiply(BigDecimal(remainingPaid))

        val materialFeeDeducted = if (contract.materialsDelivered) contract.materialFee else BigDecimal.ZERO
        // 已课消占比（6 位精度，用于优惠阈值与提成按已服务比例结算）
        val consumedRatio = BigDecimal(consumedPaid).divide(BigDecimal(contract.paidLessons), 6, RoundingMode.HALF_UP)
        val discountRecovered =
            if (consumedRatio < pkg.discountKeepThreshold) contract.discountAmount else BigDecimal.ZERO
        val giftValueOffset =
            if (contract.consumeOrder == ConsumeOrder.GIFT_FIRST) unitPrice.multiply(BigDecimal(consumedGift)) else BigDecimal.ZERO

        // ---- 分期手续费：报名满冷静期后，已回款分期的手续费不退 ----
        val installments = installmentRepo.findByContractId(contract.id)
        val paidInstallments = installments.filter { it.status == InstallmentStatus.PAID }
        val receivedPrincipal = paidInstallments.fold(BigDecimal.ZERO) { acc, i -> acc.add(i.amount) }
        val receivedFees = paidInstallments.fold(BigDecimal.ZERO) { acc, i -> acc.add(i.fee) }
        val ageDays = ChronoUnit.DAYS.between(contract.signedAt, appliedAt).coerceAtLeast(0)
        val afterCooling = ageDays >= coolingDays
        val feeRecovered = if (afterCooling) receivedFees else BigDecimal.ZERO

        val openComplaints = complaintRepo.findByStudentIdAndStatus(contract.studentId, ComplaintStatus.OPEN).size
        val complaintComp = complaintCompensation.multiply(BigDecimal(openComplaints))
        val commissionFrozen = commissionRepo.findByContractId(contract.id)
            .filter { it.status == CommissionStatus.ACCRUED || it.status == CommissionStatus.FROZEN }
            .fold(BigDecimal.ZERO) { acc, c -> acc.add(c.amount) }

        val unpaidWaived = installments.filter { it.status == InstallmentStatus.PENDING }
            .fold(BigDecimal.ZERO) { acc, i -> acc.add(i.amount).add(i.fee) }

        // ---- 提成重归因测算：顾问责任全追回；其余按已课消比例结算 ----
        val settleEstimate: BigDecimal
        val clawEstimate: BigDecimal
        if (reasonCode.attribution == Attribution.CONSULTANT) {
            settleEstimate = BigDecimal.ZERO
            clawEstimate = commissionFrozen
        } else {
            settleEstimate = commissionFrozen.multiply(consumedRatio).setScale(2, RoundingMode.HALF_UP)
            clawEstimate = commissionFrozen.subtract(settleEstimate)
        }

        var refund = baseRefund.subtract(materialFeeDeducted).subtract(discountRecovered)
            .subtract(giftValueOffset).subtract(feeRecovered).add(complaintComp)
        if (refund < BigDecimal.ZERO) refund = BigDecimal.ZERO
        // 已收款为上限（含已收手续费扣除回收部分）
        val cap = receivedPrincipal.add(receivedFees).subtract(feeRecovered)
        if (refund > cap) refund = cap

        return RefundBreakdown(
            contractId = contract.id,
            paidLessons = contract.paidLessons,
            giftLessons = contract.giftLessons,
            consumedPaidLessons = consumedPaid,
            consumedGiftLessons = consumedGift,
            remainingPaidLessons = remainingPaid,
            consumedRatio = consumedRatio,
            unitPrice = unitPrice,
            baseRefund = baseRefund,
            materialFeeDeducted = materialFeeDeducted,
            discountRecovered = discountRecovered,
            giftValueOffset = giftValueOffset,
            installmentFeeCharged = contract.installmentFeeCharged,
            installmentFeeRecovered = feeRecovered,
            coolingPeriodDays = coolingDays,
            contractAgeDays = ageDays,
            afterCoolingPeriod = afterCooling,
            openComplaints = openComplaints,
            complaintCompensation = complaintComp,
            commissionFrozen = commissionFrozen,
            commissionServiceRatio = consumedRatio,
            attribution = reasonCode.attribution,
            rectificationRequired = reasonCode.rectificationRequired,
            commissionSettleEstimate = settleEstimate,
            commissionClawEstimate = clawEstimate,
            totalReceived = receivedPrincipal,
            unpaidInstallmentsWaived = unpaidWaived,
            refundAmount = refund
        )
    }
}
