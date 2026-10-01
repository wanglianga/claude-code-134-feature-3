package com.training.crm.service

import com.training.crm.common.BusinessException
import com.training.crm.domain.*
import com.training.crm.repo.*
import org.springframework.stereotype.Service
import java.math.BigDecimal

@Service
class PrincipalService(
    private val studentRepo: StudentRepo,
    private val leadRepo: LeadRepo,
    private val auditEventRepo: AuditEventRepo,
    private val contractRepo: ContractRepo,
    private val coursePackageRepo: CoursePackageRepo,
    private val installmentRepo: InstallmentRepo,
    private val invoiceRepo: InvoiceRepo,
    private val commissionRepo: CommissionRepo,
    private val attendanceRepo: AttendanceRepo,
    private val refundOrderRepo: RefundOrderRepo,
    private val complaintRepo: ComplaintRepo,
    private val channelFeeRepo: ChannelFeeRepo,
    private val giftRecordRepo: GiftRecordRepo,
    private val promiseRecordRepo: PromiseRecordRepo,
    private val enrollmentRepo: EnrollmentRepo,
    private val clazzRepo: ClazzRepo,
    private val campusRepo: CampusRepo,
    private val consultantRepo: ConsultantRepo,
    private val channelQualityService: ChannelQualityService
) {

    /**
     * 校长视角：学员从线索进入、试听体验、报名承诺、实际上课到退费结束的完整履约轨迹。
     */
    fun timeline(studentId: Long): Map<String, Any?> {
        val student = studentRepo.findById(studentId).orElseThrow { BusinessException("NOT_FOUND", "学员不存在: $studentId") }
        val lead = leadRepo.findById(student.leadId).orElse(null)
        val events = auditEventRepo.findByStudentIdOrLeadIdOrderByCreatedAtAsc(studentId, student.leadId)

        val contracts = contractRepo.findByStudentId(studentId).map { c ->
            val pkg = coursePackageRepo.findById(c.coursePackageId).get()
            val atts = attendanceRepo.findByContractId(c.id)
            val installments = installmentRepo.findByContractId(c.id)
            mapOf(
                "contractId" to c.id,
                "status" to c.status,
                "package" to pkg.name,
                "finalAmount" to c.finalAmount,
                "paidLessons" to c.paidLessons,
                "giftLessons" to c.giftLessons,
                "consumedPaid" to atts.sumOf { it.consumedPaid },
                "consumedGift" to atts.sumOf { it.consumedGift },
                "received" to installments.filter { it.status == InstallmentStatus.PAID }.fold(BigDecimal.ZERO) { a, i -> a.add(i.amount) },
                "installments" to installments.map {
                    mapOf("seq" to it.seq, "amount" to it.amount, "fee" to it.fee, "status" to it.status, "dueDate" to it.dueDate)
                },
                "invoices" to invoiceRepo.findByContractId(c.id).map { mapOf("amount" to it.amount, "title" to it.title, "status" to it.status) },
                "commissions" to commissionRepo.findByContractId(c.id).map {
                    mapOf("amount" to it.amount, "status" to it.status, "settledAmount" to it.settledAmount, "attributionReason" to it.attributionReason)
                },
                "consistencyWarnings" to c.consistencyWarnings
            )
        }

        return mapOf(
            "student" to student,
            "lead" to lead,
            "promises" to promiseRecordRepo.findByStudentId(studentId),
            "channelFees" to (lead?.let { channelFeeRepo.findByLeadId(it.id) } ?: emptyList<ChannelFee>()),
            "gifts" to (lead?.let { giftRecordRepo.findByLeadId(it.id) } ?: emptyList<GiftRecord>()),
            "enrollments" to enrollmentRepo.findByStudentId(studentId).map { en ->
                mapOf("clazz" to clazzRepo.findById(en.clazzId).get().name, "status" to en.status, "midTermInsertion" to en.midTermInsertion, "joinedAt" to en.joinedAt)
            },
            "contracts" to contracts,
            "refunds" to refundOrderRepo.findByStudentId(studentId),
            "complaints" to complaintRepo.findByStudentId(studentId),
            "events" to events.map { mapOf("time" to it.createdAt, "type" to it.eventType, "detail" to it.detail, "actor" to it.actor) }
        )
    }

    /** 经营概览：各校区线索/试听/转化/回款/退费/提成/渠道成本 */
    fun overview(): Map<String, Any?> {
        val leads = leadRepo.findAll()
        val contracts = contractRepo.findAll()
        val perCampus = campusRepo.findAll().map { campus ->
            val campusLeads = leads.filter { it.campusId == campus.id }
            val campusContracts = contracts.filter { it.campusId == campus.id }
            val received = campusContracts.flatMap { installmentRepo.findByContractId(it.id) }
                .filter { it.status == InstallmentStatus.PAID }.fold(BigDecimal.ZERO) { a, i -> a.add(i.amount) }
            mapOf(
                "campus" to campus.name,
                "leads" to campusLeads.size,
                "converted" to campusLeads.count { it.status == LeadStatus.CONVERTED },
                "contracts" to campusContracts.size,
                "received" to received,
                "refunded" to refundOrderRepo.findAll().filter { it.status == RefundStatus.PAID }
                    .filter { r -> campusContracts.any { it.id == r.contractId } }
                    .fold(BigDecimal.ZERO) { a, r -> a.add(r.refundAmount) }
            )
        }
        val perConsultant = consultantRepo.findAll().map { c ->
            val commissions = commissionRepo.findByConsultantId(c.id)
            mapOf(
                "consultant" to c.name,
                "leads" to leads.count { it.consultantId == c.id },
                "converted" to leads.count { it.consultantId == c.id && it.status == LeadStatus.CONVERTED },
                "commissionAccrued" to commissions.filter { it.status == CommissionStatus.ACCRUED }.fold(BigDecimal.ZERO) { a, x -> a.add(x.amount) },
                "commissionFrozen" to commissions.filter { it.status == CommissionStatus.FROZEN }.fold(BigDecimal.ZERO) { a, x -> a.add(x.amount) },
                "commissionClawedBack" to commissions.filter { it.status == CommissionStatus.CLAWED_BACK }.fold(BigDecimal.ZERO) { a, x -> a.add(x.amount) },
                "commissionSettledPartial" to commissions.filter { it.status == CommissionStatus.SETTLED_PARTIAL }.fold(BigDecimal.ZERO) { a, x -> a.add(x.settledAmount) },
                "commissionSettled" to commissions.filter { it.status == CommissionStatus.SETTLED }.fold(BigDecimal.ZERO) { a, x -> a.add(x.amount) }
            )
        }
        return mapOf(
            "campuses" to perCampus,
            "consultants" to perConsultant,
            "channelCosts" to leads.flatMap { channelFeeRepo.findByLeadId(it.id) }
                .groupBy { it.channel }
                .mapValues { entry -> entry.value.fold(BigDecimal.ZERO) { a, f -> a.add(f.amount) } },
            "channelQuality" to channelQualityService.summary()
        )
    }

    /** 渠道投放质量明细 */
    fun channelQuality(): List<Map<String, Any?>> = channelQualityService.summary()
}
