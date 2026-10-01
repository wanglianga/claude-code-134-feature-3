package com.training.crm.service

import com.training.crm.common.AuditService
import com.training.crm.common.BusinessException
import com.training.crm.common.LockService
import com.training.crm.common.bizFail
import com.training.crm.domain.*
import com.training.crm.repo.*
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal

/** 各来源渠道的获客成本（元/条线索） */
val CHANNEL_COSTS: Map<String, BigDecimal> = mapOf(
    "抖音" to BigDecimal("300"),
    "小红书" to BigDecimal("200"),
    "地推" to BigDecimal("100"),
    "转介绍" to BigDecimal("50"),
    "官网" to BigDecimal("80")
)

@Service
class LeadService(
    private val leadRepo: LeadRepo,
    private val consultantRepo: ConsultantRepo,
    private val campusRepo: CampusRepo,
    private val channelFeeRepo: ChannelFeeRepo,
    private val promiseRecordRepo: PromiseRecordRepo,
    private val giftRecordRepo: GiftRecordRepo,
    private val channelQualityService: ChannelQualityService,
    private val lockService: LockService,
    private val auditService: AuditService
) {

    /**
     * 家长留线索。记录年级、目标科目、基础水平、可上课时间、来源渠道、顾问、校区、是否已参加活动。
     * 支持幂等键，防止重复提交产生重复线索。
     */
    @Transactional
    fun createLead(req: LeadCreateReq, idemKey: String?, actor: String): Pair<Boolean, Lead> {
        consultantRepo.findById(req.consultantId).orElseThrow { BusinessException("NOT_FOUND", "顾问不存在: ${req.consultantId}") }
        campusRepo.findById(req.campusId).orElseThrow { BusinessException("NOT_FOUND", "校区不存在: ${req.campusId}") }

        fun doCreate(): Lead {
            val lead = leadRepo.save(
                Lead(
                    studentName = req.studentName,
                    parentPhone = req.parentPhone,
                    grade = req.grade,
                    subject = req.subject,
                    baseLevel = req.baseLevel,
                    availableTime = req.availableTime,
                    sourceChannel = req.sourceChannel,
                    consultantId = req.consultantId,
                    campusId = req.campusId,
                    joinedActivity = req.joinedActivity,
                    remark = req.remark
                )
            )
            // 渠道费用登记
            val cost = CHANNEL_COSTS[req.sourceChannel] ?: BigDecimal("100")
            channelFeeRepo.save(ChannelFee(channel = req.sourceChannel, leadId = lead.id, amount = cost))
            // 渠道投放质量：线索 +1
            channelQualityService.recordLead(req.sourceChannel, cost)
            // 同手机号已有有效报名 → 重复线索风险（允许登记，但不得再次计为有效报名，转化环节会拦截）
            val activeSamePhone = leadRepo.findByParentPhone(req.parentPhone).any { it.status == LeadStatus.CONVERTED && it.validEnrollment }
            auditService.record(
                "LEAD_CREATED",
                "新线索：${lead.studentName}（${lead.grade}，${lead.subject}，基础=${lead.baseLevel}，可上课=${lead.availableTime}，渠道=${lead.sourceChannel}，已参加活动=${lead.joinedActivity}）",
                leadId = lead.id, actor = actor
            )
            auditService.record("CHANNEL_FEE", "渠道费用登记：${lead.sourceChannel} ¥$cost", leadId = lead.id, actor = actor)
            if (activeSamePhone) {
                auditService.record(
                    "DUPLICATE_LEAD_RISK",
                    "家长手机号 ${req.parentPhone} 已有有效报名，该线索标记为重复风险，转化时拦截，避免同一客户被重复算作有效报名",
                    leadId = lead.id, actor = actor
                )
            }
            return lead
        }

        if (idemKey.isNullOrBlank()) {
            return false to doCreate()
        }
        val (existed, id) = lockService.idempotent("lead:$idemKey") { doCreate().id }
        return existed to leadRepo.findById(id).get()
    }

    /** 试听后换校区（转化前允许） */
    @Transactional
    fun changeCampus(leadId: Long, newCampusId: Long, actor: String): Lead {
        val lead = get(leadId)
        if (lead.status == LeadStatus.CONVERTED || lead.status == LeadStatus.LOST) {
            bizFail("INVALID_STATE", "线索已转化或已流失，不能换校区")
        }
        campusRepo.findById(newCampusId).orElseThrow { BusinessException("NOT_FOUND", "校区不存在: $newCampusId") }
        val old = lead.campusId
        lead.campusId = newCampusId
        auditService.record("LEAD_CAMPUS_CHANGED", "线索换校区：$old → $newCampusId", leadId = lead.id, actor = actor)
        return leadRepo.save(lead)
    }

    /** 登记家长承诺（报名前的口头/书面承诺，后续与试听反馈核对） */
    @Transactional
    fun addPromise(leadId: Long, content: String, promisedBy: String, actor: String): PromiseRecord {
        val lead = get(leadId)
        val rec = promiseRecordRepo.save(
            PromiseRecord(leadId = lead.id, content = content, promisedBy = promisedBy)
        )
        auditService.record("PROMISE_RECORDED", "家长承诺登记：$content（承诺人：$promisedBy）", leadId = lead.id, actor = actor)
        return rec
    }

    @Transactional
    fun markLost(leadId: Long, reason: String, actor: String): Lead {
        val lead = get(leadId)
        if (lead.status == LeadStatus.CONVERTED) bizFail("INVALID_STATE", "已转化线索不能标记流失")
        lead.status = LeadStatus.LOST
        // 未转化：已发赠品核销
        giftRecordRepo.findByLeadId(lead.id).filter { it.status == "GRANTED" }.forEach {
            it.status = "WRITTEN_OFF"
            giftRecordRepo.save(it)
            auditService.record("GIFT_WRITTEN_OFF", "体验课赠品核销：${it.giftType}", leadId = lead.id, actor = actor)
        }
        auditService.record("LEAD_LOST", "线索流失：$reason", leadId = lead.id, actor = actor)
        return leadRepo.save(lead)
    }

    fun get(leadId: Long): Lead =
        leadRepo.findById(leadId).orElseThrow { BusinessException("NOT_FOUND", "线索不存在: $leadId") }
}

data class LeadCreateReq(
    val studentName: String,
    val parentPhone: String,
    val grade: String,
    val subject: Subject,
    val baseLevel: Level,
    val availableTime: String,
    val sourceChannel: String,
    val consultantId: Long,
    val campusId: Long,
    val joinedActivity: Boolean = false,
    val remark: String? = null
)
