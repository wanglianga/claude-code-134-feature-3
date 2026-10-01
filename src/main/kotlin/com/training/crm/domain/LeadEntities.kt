package com.training.crm.domain

import jakarta.persistence.*
import java.math.BigDecimal
import java.time.LocalDateTime

/**
 * 线索：家长留下的意向信息。
 * 记录学生年级、目标科目、基础水平、可上课时间、来源渠道、顾问、校区、是否已参加活动。
 */
@Entity
class Lead(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long = 0,
    var studentName: String = "",
    var parentPhone: String = "",
    var grade: String = "",
    @Enumerated(EnumType.STRING)
    var subject: Subject = Subject.MATH,
    @Enumerated(EnumType.STRING)
    var baseLevel: Level = Level.BASIC,
    /** 可上课时间，自由文本，如 "周六上午/周三晚" */
    var availableTime: String = "",
    var sourceChannel: String = "",
    var consultantId: Long = 0,
    var campusId: Long = 0,
    var joinedActivity: Boolean = false,
    @Enumerated(EnumType.STRING)
    var status: LeadStatus = LeadStatus.NEW,
    /**
     * 有效报名标记：转化为正式学员时置 true；该报名退费完成后置 false（渠道质量核销），
     * 防止同一线索退费后仍被重复算作有效报名。
     */
    var validEnrollment: Boolean = false,
    /** 报名已退费（用于同手机号重复线索风险判断） */
    var enrollmentRefunded: Boolean = false,
    var remark: String? = null,
    var createdAt: LocalDateTime = LocalDateTime.now()
)

/** 家长承诺记录：顾问对家长做出的承诺，报名前后均可登记，用于与试听反馈核对 */
@Entity
class PromiseRecord(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long = 0,
    var leadId: Long? = null,
    var studentId: Long? = null,
    var contractId: Long? = null,
    var content: String = "",
    var promisedBy: String = "",
    var createdAt: LocalDateTime = LocalDateTime.now()
)

/** 渠道费用：按来源渠道登记的获客成本，转化后结算 */
@Entity
class ChannelFee(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long = 0,
    var channel: String = "",
    var leadId: Long = 0,
    var amount: BigDecimal = BigDecimal.ZERO,
    var settled: Boolean = false,
    var createdAt: LocalDateTime = LocalDateTime.now()
)

/** 体验课赠品：参加活动的线索到课试听时发放 */
@Entity
class GiftRecord(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long = 0,
    var leadId: Long = 0,
    var giftType: String = "",
    var cost: BigDecimal = BigDecimal.ZERO,
    /** GRANTED=已发放, WRITTEN_OFF=未转化核销 */
    var status: String = "GRANTED",
    var createdAt: LocalDateTime = LocalDateTime.now()
)
