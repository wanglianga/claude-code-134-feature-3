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
 * 退费计算规则（与 README 一致）：
 * - 课时单价 = 实付金额 / 付费课时数
 * - 基础退费 = 剩余付费课时 × 单价（赠课不退现）
 * - 赠课抵扣：consumeOrder=GIFT_FIRST 时，已消耗赠课按单价从应退中抵扣
 * - 教材费：教材已发放则全额扣除
 * - 优惠回收：已上课时占比低于课包 discountKeepThreshold 时，全额回收优惠
 * - 分期手续费：合同签订时按实付金额×费率计收（分期数>1），退费时不予退还
 * - 历史投诉：每条未结投诉增加固定赔付（app.complaint-compensation）
 * - 顾问提成：退费申请即冻结，退款完成后追回并按退费原因重新归因（不计入退给家长的金额，仅展示）
 * - 退费金额以已收款为上限；未收分期全部减免
 *
 * 财务审核通过后的联动：
 * 班级名额释放 → 空班未上课时取消（老师排课减少、未使用课表关闭）→ 业绩按原因重新归因 →
 * 承诺不一致触发校区整改 → 教材回收入库 → 线索置为退费失效、渠道费反结算（渠道投放质量更新）。
 */
@Service
class RefundService(
    private val studentRepo: StudentRepo,
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
    private val leadRepo: LeadRepo,
    private val channelFeeRepo: ChannelFeeRepo,
    private val materialStockRepo: MaterialStockRepo,
    private val rectificationReportRepo: RectificationReportRepo,
    private val lockService: LockService,
    private val auditService: AuditService,
    @Value("\${app.complaint-compensation}") private val complaintCompensation: BigDecimal
) {
    private val mapper = jacksonObjectMapper()

    data class RefundBreakdown(
        val contractId: Long,
        val paidLessons: Int,
        val giftLessons: Int,
        val consumedPaidLessons: Int,
        val consumedGiftLessons: Int,
        val remainingPaidLessons: Int,
        val unitPrice: BigDecimal,
        val baseRefund: BigDecimal,
        val materialFeeDeducted: BigDecimal,
        val discountRecovered: BigDecimal,
        val giftValueOffset: BigDecimal,
        val installmentFeeDeducted: BigDecimal,
        val openComplaints: Int,
        val complaintCompensation: BigDecimal,
        val commissionFrozen: BigDecimal,
        val totalReceived: BigDecimal,
        val unpaidInstallmentsWaived: BigDecimal,
        val daysSinceSign: Long,
        val refundAmount: BigDecimal
    )

    /** 退费试算（不落库） */
    fun preview(studentId: Long): RefundBreakdown {
        val contract = activeContractOf(studentId)
        return compute(contract)
    }

    /** 退费申请：落库并冻结顾问提成。幂等键防重复申请。 */
    @Transactional
    fun request(studentId: Long, reason: String, reasonCategory: RefundReasonCategory, idemKey: String?, actor: String): RefundOrder {
        val contract = activeContractOf(studentId)

        fun doCreate(): RefundOrder {
            val existing = refundOrderRepo.findByContractIdAndStatusIn(
                contract.id, listOf(RefundStatus.REQUESTED, RefundStatus.APPROVED)
            )
            if (existing.isNotEmpty()) bizFail("DUPLICATE", "已有进行中的退费单: #${existing.first().id}")
            val breakdown = compute(contract)
            val order = refundOrderRepo.save(
                RefundOrder(
                    studentId = studentId,
                    contractId = contract.id,
                    reason = reason,
                    reasonCategory = reasonCategory,
                    breakdownJson = mapper.writeValueAsString(breakdown),
                    refundAmount = breakdown.refundAmount
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
                "退费申请：应退 ¥${breakdown.refundAmount}（已上付费课时=${breakdown.consumedPaidLessons}，剩余=${breakdown.remainingPaidLessons}，报名至今=${breakdown.daysSinceSign}天，原因=$reason，分类=$reasonCategory）",
                studentId = studentId, actor = actor
            )
            return order
        }

        if (idemKey.isNullOrBlank()) return doCreate()
        val (existed, id) = lockService.idempotent("refund:$idemKey") { doCreate().id }
        return refundOrderRepo.findById(id).get()
    }

    /**
     * 财务审核通过并打款。联动：
     * 1. 合同/学员/报班状态关闭，班级名额释放；
     * 2. 空班未上课时取消（老师排课减少、未使用课表关闭）；
     * 3. 未收分期减免、顾问提成追回并按退费原因重新归因；
     * 4. 退费源于服务承诺不一致 → 生成校区整改任务；
     * 5. 教材回收入库；
     * 6. 线索置为退费失效 + 渠道费反结算（渠道投放质量更新，防止重复计为有效报名）。
     */
    @Transactional
    fun approve(refundId: Long, approver: String): RefundOrder {
        val order = refundOrderRepo.findById(refundId).orElseThrow { BusinessException("NOT_FOUND", "退费单不存在: $refundId") }
        if (order.status != RefundStatus.REQUESTED) bizFail("INVALID_STATE", "退费单状态(${order.status})不能审批")

        val contract = contractRepo.findById(order.contractId).get()
        val student = studentRepo.findById(order.studentId).get()

        // ---- 业绩按退费原因重新归因 ----
        val attribution = when (order.reasonCategory) {
            RefundReasonCategory.PROMISE_MISMATCH -> RefundAttribution.CONSULTANT
            RefundReasonCategory.SERVICE_QUALITY, RefundReasonCategory.TEACHER_ISSUE -> RefundAttribution.CAMPUS
            RefundReasonCategory.PERSONAL, RefundReasonCategory.OTHER -> RefundAttribution.NONE
        }
        order.attribution = attribution
        order.status = RefundStatus.PAID
        order.decidedBy = approver
        order.decidedAt = LocalDateTime.now()
        refundOrderRepo.save(order)

        contract.status = ContractStatus.REFUNDED
        contractRepo.save(contract)
        student.status = StudentStatus.REFUNDED
        studentRepo.save(student)

        // ---- 报班退出：班级名额释放；空班未上课时取消（老师排课减少、未使用课表关闭）----
        enrollmentRepo.findByStudentIdAndStatus(order.studentId, EnrollmentStatus.ACTIVE).forEach { en ->
            en.status = EnrollmentStatus.DROPPED
            enrollmentRepo.save(en)
            val clazz = clazzRepo.findById(en.clazzId).get()
            // 该学员未上课时出勤清理
            lessonRepo.findByClazzIdAndStatus(en.clazzId, LessonStatus.SCHEDULED).forEach { lesson ->
                attendanceRepo.findByLessonIdAndStudentId(lesson.id, order.studentId)?.let { att ->
                    if (att.status == AttendStatus.PENDING) attendanceRepo.delete(att)
                }
            }
            val remaining = enrollmentRepo.findByClazzIdAndStatus(en.clazzId, EnrollmentStatus.ACTIVE).size
            auditService.record(
                "CLASS_SEAT_RELEASED",
                "班级名额释放：${clazz.name}(#${clazz.id})，剩余名额=${clazz.capacity - remaining}",
                studentId = order.studentId, actor = approver
            )
            if (remaining == 0) {
                lessonRepo.findByClazzIdAndStatus(en.clazzId, LessonStatus.SCHEDULED).forEach { lesson ->
                    lesson.status = LessonStatus.CANCELLED
                    lessonRepo.save(lesson)
                    attendanceRepo.findByLessonId(lesson.id).filter { it.status == AttendStatus.PENDING }.forEach { attendanceRepo.delete(it) }
                    auditService.record(
                        "LESSON_CANCELLED_REFUND",
                        "班级无在读学员，未上课时关闭：${lesson.topic}（${lesson.startTime}），老师#${lesson.teacherId}排课减少",
                        studentId = order.studentId, actor = approver
                    )
                }
            }
        }

        // ---- 未收分期减免 ----
        installmentRepo.findByContractId(contract.id).filter { it.status == InstallmentStatus.PENDING }.forEach {
            it.status = InstallmentStatus.WAIVED
            installmentRepo.save(it)
        }
        // ---- 提成追回 ----
        commissionRepo.findByContractId(contract.id).filter { it.status == CommissionStatus.FROZEN }.forEach {
            it.status = CommissionStatus.CLAWED_BACK
            it.updatedAt = LocalDateTime.now()
            commissionRepo.save(it)
        }
        val attributionDesc = when (attribution) {
            RefundAttribution.CONSULTANT -> "计入顾问责任退费"
            RefundAttribution.CAMPUS -> "计入校区责任退费"
            RefundAttribution.NONE -> "非责任退费，不计入顾问/校区考核"
        }
        auditService.record("REFUND_PAID", "退费完成：¥${order.refundAmount}（退费单#$refundId，审批人=$approver）", studentId = order.studentId, actor = approver)
        auditService.record("COMMISSION_CLAWED_BACK", "顾问提成追回（合同#${contract.id}）", studentId = order.studentId, actor = approver)
        auditService.record(
            "REFUND_ATTRIBUTED",
            "顾问业绩重新归因：$attributionDesc（退费原因=${order.reasonCategory}）",
            studentId = order.studentId, actor = approver
        )

        // ---- 服务承诺不一致 → 校区整改任务 ----
        if (order.reasonCategory == RefundReasonCategory.PROMISE_MISMATCH) {
            rectificationReportRepo.save(RectificationReport(refundOrderId = order.id, campusId = contract.campusId))
            auditService.record(
                "RECTIFICATION_REQUIRED",
                "退费源于服务承诺不一致，校区#${contract.campusId}需提交整改说明",
                studentId = order.studentId, actor = approver
            )
        }

        // ---- 教材回收入库 ----
        if (contract.materialsDelivered) {
            val pkg = coursePackageRepo.findById(contract.coursePackageId).get()
            val stock = materialStockRepo.findByCampusIdAndTextbookVersion(contract.campusId, pkg.textbookVersion)
                ?: MaterialStock(campusId = contract.campusId, textbookVersion = pkg.textbookVersion, quantity = 0)
            stock.quantity += 1
            materialStockRepo.save(stock)
            auditService.record(
                "MATERIAL_RESTOCKED",
                "教材回收入库：${pkg.textbookVersion}（校区#${contract.campusId}），当前库存=${stock.quantity}",
                studentId = order.studentId, actor = approver
            )
        }

        // ---- 渠道投放质量：线索置为退费失效（不再计为有效报名）+ 渠道费反结算 ----
        val lead = leadRepo.findById(student.leadId).orElse(null)
        if (lead != null && lead.status == LeadStatus.CONVERTED) {
            lead.status = LeadStatus.REFUNDED
            leadRepo.save(lead)
            auditService.record(
                "LEAD_INVALIDATED",
                "线索#${lead.id}标记为退费失效，不再计入渠道有效报名",
                studentId = order.studentId, leadId = lead.id, actor = approver
            )
        }
        if (lead != null) {
            channelFeeRepo.findByLeadId(lead.id).filter { it.settled }.forEach {
                it.settled = false
                channelFeeRepo.save(it)
                auditService.record(
                    "CHANNEL_FEE_REVERSED",
                    "渠道费反结算：${it.channel} ¥${it.amount}（线索#${lead.id}退费失效）",
                    studentId = order.studentId, leadId = lead.id, actor = approver
                )
            }
        }
        return order
    }

    @Transactional
    fun reject(refundId: Long, approver: String, reason: String): RefundOrder {
        val order = refundOrderRepo.findById(refundId).orElseThrow { BusinessException("NOT_FOUND", "退费单不存在: $refundId") }
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

    /** 校区提交整改说明（仅承诺不一致类退费需要） */
    @Transactional
    fun submitRectification(refundId: Long, content: String, actor: String): RectificationReport {
        val order = refundOrderRepo.findById(refundId).orElseThrow { BusinessException("NOT_FOUND", "退费单不存在: $refundId") }
        if (order.status != RefundStatus.PAID) bizFail("INVALID_STATE", "退费单未完成打款，不能提交整改说明")
        val report = rectificationReportRepo.findByRefundOrderId(refundId).firstOrNull()
            ?: bizFail("NOT_FOUND", "该退费单无需提交整改说明")
        if (report.status == "SUBMITTED") bizFail("DUPLICATE", "整改说明已提交，不能重复提交")
        if (content.isBlank()) bizFail("INVALID_ARGUMENT", "整改说明内容不能为空")
        report.status = "SUBMITTED"
        report.content = content
        report.submittedBy = actor
        report.submittedAt = LocalDateTime.now()
        auditService.record(
            "RECTIFICATION_SUBMITTED",
            "校区#${report.campusId}提交整改说明：$content",
            studentId = order.studentId, actor = actor
        )
        return rectificationReportRepo.save(report)
    }

    /** 待提交的校区整改任务 */
    fun pendingRectifications(): List<Map<String, Any?>> =
        rectificationReportRepo.findByStatus("PENDING").map { r ->
            val order = refundOrderRepo.findById(r.refundOrderId).orElse(null)
            mapOf(
                "id" to r.id,
                "refundOrderId" to r.refundOrderId,
                "campusId" to r.campusId,
                "studentId" to order?.studentId,
                "reason" to order?.reason,
                "status" to r.status,
                "createdAt" to r.createdAt
            )
        }

    fun get(refundId: Long): RefundOrder =
        refundOrderRepo.findById(refundId).orElseThrow { BusinessException("NOT_FOUND", "退费单不存在: $refundId") }

    /** 退费单详情：单据 + 计算明细 + 校区整改情况 */
    fun detail(refundId: Long): Map<String, Any?> {
        val order = get(refundId)
        val rectification = rectificationReportRepo.findByRefundOrderId(refundId).firstOrNull()
        @Suppress("UNCHECKED_CAST")
        val breakdown = mapper.readValue(order.breakdownJson, Map::class.java) as Map<String, Any?>
        return mapOf(
            "id" to order.id,
            "studentId" to order.studentId,
            "contractId" to order.contractId,
            "reason" to order.reason,
            "reasonCategory" to order.reasonCategory,
            "attribution" to order.attribution,
            "refundAmount" to order.refundAmount,
            "status" to order.status,
            "createdAt" to order.createdAt,
            "decidedBy" to order.decidedBy,
            "decidedAt" to order.decidedAt,
            "breakdown" to breakdown,
            "rectification" to rectification
        )
    }

    private fun activeContractOf(studentId: Long): Contract {
        studentRepo.findById(studentId).orElseThrow { BusinessException("NOT_FOUND", "学员不存在: $studentId") }
        return contractRepo.findByStudentId(studentId).firstOrNull { it.status == ContractStatus.ACTIVE || it.status == ContractStatus.SUSPENDED }
            ?: bizFail("INVALID_STATE", "学员没有可退费的合同")
    }

    private fun compute(contract: Contract): RefundBreakdown {
        val pkg = coursePackageRepo.findById(contract.coursePackageId).get()
        val atts = attendanceRepo.findByContractId(contract.id)
        val consumedPaid = atts.sumOf { it.consumedPaid }
        val consumedGift = atts.sumOf { it.consumedGift }
        val remainingPaid = contract.paidLessons - consumedPaid
        val unitPrice = contract.finalAmount.divide(BigDecimal(contract.paidLessons), 2, RoundingMode.HALF_UP)
        val baseRefund = unitPrice.multiply(BigDecimal(remainingPaid))

        val materialFeeDeducted = if (contract.materialsDelivered) contract.materialFee else BigDecimal.ZERO
        val consumedRatio = BigDecimal(consumedPaid).divide(BigDecimal(contract.paidLessons), 4, RoundingMode.HALF_UP)
        val discountRecovered =
            if (consumedRatio < pkg.discountKeepThreshold) contract.discountAmount else BigDecimal.ZERO
        val giftValueOffset =
            if (contract.consumeOrder == ConsumeOrder.GIFT_FIRST) unitPrice.multiply(BigDecimal(consumedGift)) else BigDecimal.ZERO
        // 分期手续费不退：报名时已计入合同
        val installmentFeeDeducted = contract.installmentFee

        val openComplaints = complaintRepo.findByStudentIdAndStatus(contract.studentId, ComplaintStatus.OPEN).size
        val complaintComp = complaintCompensation.multiply(BigDecimal(openComplaints))
        val commissionFrozen = commissionRepo.findByContractId(contract.id)
            .filter { it.status == CommissionStatus.ACCRUED || it.status == CommissionStatus.FROZEN }
            .fold(BigDecimal.ZERO) { acc, c -> acc.add(c.amount) }

        val installments = installmentRepo.findByContractId(contract.id)
        val totalReceived = installments.filter { it.status == InstallmentStatus.PAID }.fold(BigDecimal.ZERO) { acc, i -> acc.add(i.amount) }
        val unpaidWaived = installments.filter { it.status == InstallmentStatus.PENDING }.fold(BigDecimal.ZERO) { acc, i -> acc.add(i.amount) }

        var refund = baseRefund.subtract(materialFeeDeducted).subtract(discountRecovered).subtract(giftValueOffset)
            .subtract(installmentFeeDeducted).add(complaintComp)
        if (refund < BigDecimal.ZERO) refund = BigDecimal.ZERO
        if (refund > totalReceived) refund = totalReceived // 已收款为上限

        return RefundBreakdown(
            contractId = contract.id,
            paidLessons = contract.paidLessons,
            giftLessons = contract.giftLessons,
            consumedPaidLessons = consumedPaid,
            consumedGiftLessons = consumedGift,
            remainingPaidLessons = remainingPaid,
            unitPrice = unitPrice,
            baseRefund = baseRefund,
            materialFeeDeducted = materialFeeDeducted,
            discountRecovered = discountRecovered,
            giftValueOffset = giftValueOffset,
            installmentFeeDeducted = installmentFeeDeducted,
            openComplaints = openComplaints,
            complaintCompensation = complaintComp,
            commissionFrozen = commissionFrozen,
            totalReceived = totalReceived,
            unpaidInstallmentsWaived = unpaidWaived,
            daysSinceSign = ChronoUnit.DAYS.between(contract.createdAt, LocalDateTime.now()),
            refundAmount = refund
        )
    }
}
