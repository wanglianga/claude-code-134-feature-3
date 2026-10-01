package com.training.crm.service

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.training.crm.common.AuditService
import com.training.crm.common.BusinessException
import com.training.crm.common.bizFail
import com.training.crm.domain.*
import com.training.crm.repo.*
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate

@Service
class ConversionService(
    private val leadRepo: LeadRepo,
    private val studentRepo: StudentRepo,
    private val contractRepo: ContractRepo,
    private val coursePackageRepo: CoursePackageRepo,
    private val installmentRepo: InstallmentRepo,
    private val invoiceRepo: InvoiceRepo,
    private val commissionRepo: CommissionRepo,
    private val trialClassRepo: TrialClassRepo,
    private val trialFeedbackRepo: TrialFeedbackRepo,
    private val promiseRecordRepo: PromiseRecordRepo,
    private val channelFeeRepo: ChannelFeeRepo,
    private val inventoryService: InventoryService,
    private val channelQualityService: ChannelQualityService,
    private val auditService: AuditService,
    @Value("\${app.commission-rate}") private val commissionRate: BigDecimal,
    @Value("\${app.installment-fee-rate}") private val defaultInstallmentFeeRate: BigDecimal
) {
    private val mapper = jacksonObjectMapper()

    data class ConvertResult(
        val student: Student,
        val contract: Contract,
        val installments: List<Installment>,
        val invoice: Invoice?,
        val commission: Commission,
        val consistencyWarnings: List<String>
    )

    /**
     * 报名转化：线索 → 正式学员。
     * 课包、优惠、赠课、分期、发票、课消规则绑定到同一学员账户；
     * 报名前校验试听反馈与合同承诺的一致性，不一致需 forceConfirm 并留痕。
     */
    @Transactional
    fun convert(req: ConvertReq, actor: String): ConvertResult {
        val lead = leadRepo.findById(req.leadId).orElseThrow { BusinessException("NOT_FOUND", "线索不存在: ${req.leadId}") }
        if (lead.status != LeadStatus.TRIAL_DONE) {
            bizFail("INVALID_STATE", "线索状态(${lead.status})不能报名：需先完成试听并由老师提交反馈")
        }
        // 防止同一线索（家长手机号）在已有有效报名时重复报名
        val duplicate = leadRepo.findByParentPhone(lead.parentPhone).any {
            it.id != lead.id && it.status == LeadStatus.CONVERTED && it.validEnrollment
        }
        if (duplicate) bizFail("DUPLICATE_ENROLLMENT", "该家长手机号已有有效报名，不能重复转化（报名退费后核销可重新报名）")
        val pkg = coursePackageRepo.findById(req.coursePackageId)
            .orElseThrow { BusinessException("NOT_FOUND", "课包不存在: ${req.coursePackageId}") }

        // ---- 一致性校验：试听反馈 vs 合同承诺 ----
        val warnings = consistencyCheck(lead, pkg, req.promises)
        if (warnings.isNotEmpty() && !req.forceConfirm) {
            bizFail("CONSISTENCY_WARNING", "试听反馈与合同承诺不一致：${warnings.joinToString("；")}。确认无误请带 forceConfirm=true 重新提交")
        }

        // ---- 学员与合同 ----
        val student = studentRepo.save(Student(leadId = lead.id, name = lead.studentName, campusId = lead.campusId))
        val finalAmount = pkg.price.subtract(req.discountAmount)
        if (finalAmount < BigDecimal.ZERO) bizFail("INVALID_AMOUNT", "优惠金额不能超过课包价格")
        val giftLessons = pkg.defaultGiftLessons + req.extraGiftLessons
        val signedAt = req.signedAt ?: java.time.LocalDateTime.now()
        val contract = contractRepo.save(
            Contract(
                studentId = student.id,
                coursePackageId = pkg.id,
                consultantId = lead.consultantId,
                campusId = lead.campusId,
                totalAmount = pkg.price,
                discountAmount = req.discountAmount,
                finalAmount = finalAmount,
                paidLessons = pkg.totalLessons,
                giftLessons = giftLessons,
                materialFee = pkg.materialFee,
                materialsDelivered = req.materialsDelivered,
                consumeOrder = req.consumeOrder,
                installmentFeeRate = req.installmentFeeRate ?: defaultInstallmentFeeRate,
                signedAt = signedAt,
                consistencyWarnings = if (warnings.isEmpty()) null else mapper.writeValueAsString(warnings)
            )
        )

        // ---- 分期（分期数>1 时按期本金计收手续费）----
        val plan = req.installments.ifEmpty { listOf(InstallmentPlan(finalAmount, LocalDate.now())) }
        val planSum = plan.fold(BigDecimal.ZERO) { acc, p -> acc.add(p.amount) }
        if (planSum.compareTo(finalAmount) != 0) {
            bizFail("INVALID_INSTALLMENT", "分期总额($planSum)必须等于实付金额($finalAmount)")
        }
        val feeRate = contract.installmentFeeRate
        val withFee = plan.size > 1
        val installments = plan.mapIndexed { i, p ->
            val fee = if (withFee) p.amount.multiply(feeRate).setScale(2, RoundingMode.HALF_UP) else BigDecimal.ZERO
            installmentRepo.save(
                Installment(contractId = contract.id, seq = i + 1, amount = p.amount, fee = fee, dueDate = p.dueDate)
            )
        }
        val feeTotal = installments.fold(BigDecimal.ZERO) { acc, ins -> acc.add(ins.fee) }
        contract.installmentFeeCharged = feeTotal
        contractRepo.save(contract)

        // ---- 发票 ----
        val invoice = req.invoiceTitle?.let {
            invoiceRepo.save(Invoice(contractId = contract.id, amount = finalAmount, title = it, taxNo = req.invoiceTaxNo))
        }

        // ---- 教材发放出库 ----
        if (req.materialsDelivered) {
            inventoryService.issue(pkg.subject, pkg.textbookVersion, contract.id, student.id)
        }

        // ---- 顾问提成 ----
        val commission = commissionRepo.save(
            Commission(consultantId = lead.consultantId, contractId = contract.id, amount = finalAmount.multiply(commissionRate))
        )

        // ---- 家长承诺记录绑定到合同 ----
        req.promises.forEach { content ->
            promiseRecordRepo.save(
                PromiseRecord(leadId = lead.id, studentId = student.id, contractId = contract.id, content = content, promisedBy = actor)
            )
        }

        // ---- 渠道费结算 + 有效报名计入渠道投放质量 ----
        channelFeeRepo.findByLeadId(lead.id).forEach { it.settled = true; channelFeeRepo.save(it) }
        lead.validEnrollment = true

        lead.status = LeadStatus.CONVERTED
        leadRepo.save(lead)
        channelQualityService.recordValidEnrollment(lead.sourceChannel)

        auditService.record("CONVERTED", "线索转化为正式学员（学员ID=${student.id}）", studentId = student.id, leadId = lead.id, actor = actor)
        auditService.record(
            "CONTRACT_CREATED",
            "合同#${contract.id}：课包=${pkg.name}，原价=${pkg.price}，优惠=${req.discountAmount}，实付=$finalAmount，付费课时=${pkg.totalLessons}，赠课=$giftLessons，教材费=${pkg.materialFee}，课消顺序=${req.consumeOrder}",
            studentId = student.id, leadId = lead.id, actor = actor
        )
        auditService.record(
            "INSTALLMENT_PLAN",
            "分期${installments.size}期（手续费率=$feeRate）：${installments.joinToString { "第${it.seq}期本金¥${it.amount}+手续费¥${it.fee}(${it.dueDate})" }}，手续费合计 ¥$feeTotal",
            studentId = student.id, leadId = lead.id, actor = actor
        )
        if (req.materialsDelivered) {
            auditService.record("MATERIAL_ISSUED", "教材发放出库：${pkg.textbookVersion} ×1", studentId = student.id, leadId = lead.id, actor = actor)
        }
        if (invoice != null) auditService.record("INVOICE_ISSUED", "发票开具：¥${invoice.amount}，抬头=${invoice.title}", studentId = student.id, leadId = lead.id, actor = actor)
        auditService.record("COMMISSION_ACCRUED", "顾问提成计提：¥${commission.amount}（顾问=${lead.consultantId}）", studentId = student.id, leadId = lead.id, actor = actor)
        req.promises.forEach { auditService.record("PROMISE_RECORDED", "合同承诺：$it", studentId = student.id, leadId = lead.id, actor = actor) }
        warnings.forEach { auditService.record("CONSISTENCY_WARNING", "一致性告警：$it", studentId = student.id, leadId = lead.id, actor = actor) }
        auditService.record("CHANNEL_VALID_ENROLLMENT", "渠道[${lead.sourceChannel}]有效报名+1", studentId = student.id, leadId = lead.id, actor = actor)

        return ConvertResult(student, contract, installments, invoice, commission, warnings)
    }

    /**
     * 一致性校验规则：
     * 1. 老师建议班型 ≠ 报名课包班型 → 告警
     * 2. 老师建议水平 ≠ 课包水平 → 告警
     * 3. 反馈有风险提醒，而承诺中含「保过/保分/包过」类字样 → 告警
     */
    fun consistencyCheck(lead: Lead, pkg: CoursePackage, promises: List<String>): List<String> {
        val trials = trialClassRepo.findByLeadId(lead.id)
        val feedback = trials.mapNotNull { trialFeedbackRepo.findByTrialClassId(it.id) }.maxByOrNull { it.createdAt }
            ?: return listOf("未找到试听反馈，无法核对合同承诺")
        val warnings = mutableListOf<String>()
        if (feedback.suitableClassType != pkg.classType) {
            warnings.add("试听反馈适合班型=${feedback.suitableClassType}，报名课包班型=${pkg.classType}")
        }
        if (feedback.suggestedLevel != pkg.level) {
            warnings.add("试听反馈建议水平=${feedback.suggestedLevel}，课包水平=${pkg.level}")
        }
        val riskyPromise = promises.any { p -> listOf("保过", "保分", "包过", "不过退").any { p.contains(it) } }
        if (riskyPromise && !feedback.riskWarning.isNullOrBlank()) {
            warnings.add("老师风险提醒「${feedback.riskWarning}」与保过类承诺冲突")
        }
        return warnings
    }

    /** 分期回款 */
    @Transactional
    fun payInstallment(installmentId: Long, actor: String): Installment {
        val inst = installmentRepo.findById(installmentId).orElseThrow { BusinessException("NOT_FOUND", "分期不存在: $installmentId") }
        if (inst.status == InstallmentStatus.PAID) bizFail("INVALID_STATE", "该期已支付")
        if (inst.status == InstallmentStatus.WAIVED) bizFail("INVALID_STATE", "该期已减免")
        inst.status = InstallmentStatus.PAID
        inst.paidAt = java.time.LocalDateTime.now()
        val contract = contractRepo.findById(inst.contractId).get()
        auditService.record(
            "INSTALLMENT_PAID",
            "第${inst.seq}期回款 本金¥${inst.amount}+手续费¥${inst.fee}（合同#${inst.contractId}）",
            studentId = contract.studentId, actor = actor
        )
        return installmentRepo.save(inst)
    }
}

data class ConvertReq(
    val leadId: Long,
    val coursePackageId: Long,
    val discountAmount: BigDecimal = BigDecimal.ZERO,
    val extraGiftLessons: Int = 0,
    val installments: List<InstallmentPlan> = emptyList(),
    val invoiceTitle: String? = null,
    val invoiceTaxNo: String? = null,
    val promises: List<String> = emptyList(),
    val materialsDelivered: Boolean = true,
    val consumeOrder: ConsumeOrder = ConsumeOrder.PAID_FIRST,
    /** 分期手续费率，缺省取 app.installment-fee-rate（分期数>1 时生效） */
    val installmentFeeRate: BigDecimal? = null,
    /** 合同签署时间，缺省为当前时间（用于补录报名两周后退费场景的账龄） */
    val signedAt: java.time.LocalDateTime? = null,
    val forceConfirm: Boolean = false
)

data class InstallmentPlan(val amount: BigDecimal, val dueDate: LocalDate)
