package com.training.crm.domain

/** 科目 */
enum class Subject { MATH, ENGLISH, CHINESE, PHYSICS, CHEMISTRY }

/** 学生基础水平 / 班级难度等级 */
enum class Level { BEGINNER, BASIC, INTERMEDIATE, ADVANCED }

/** 班型 */
enum class ClassType { REGULAR, INTENSIVE, ONE_ON_ONE, SPRINT }

/** 线索状态：新建 → 已约试听 → 试听完成 → 已转化 / 已流失 */
enum class LeadStatus { NEW, TRIAL_SCHEDULED, TRIAL_DONE, CONVERTED, LOST }

/** 试听课状态 */
enum class TrialStatus { SCHEDULED, ATTENDED, ABSENT, CANCELLED_TEACHER_LEAVE, CANCELLED }

/** 学员状态 */
enum class StudentStatus { ACTIVE, SUSPENDED, REFUNDED, GRADUATED }

/** 合同状态 */
enum class ContractStatus { ACTIVE, SUSPENDED, REFUNDED, COMPLETED }

/** 分期状态 */
enum class InstallmentStatus { PENDING, PAID, WAIVED }

/** 发票状态 */
enum class InvoiceStatus { ISSUED, VOID }

/** 正式课课时状态 */
enum class LessonStatus { SCHEDULED, COMPLETED, CANCELLED_TEACHER_LEAVE, CANCELLED }

/** 课消出勤状态 */
enum class AttendStatus { PENDING, ATTENDED, ABSENT, LEAVE }

/** 请假单状态 */
enum class LeaveStatus { PENDING, APPROVED, REJECTED }

/** 报班记录状态 */
enum class EnrollmentStatus { ACTIVE, TRANSFERRED_OUT, DROPPED }

/** 顾问提成状态：计提 → 冻结（退费中）→ 全额追回 / 部分结算（按退费原因重归因）/ 全额结算 */
enum class CommissionStatus { ACCRUED, FROZEN, CLAWED_BACK, SETTLED_PARTIAL, SETTLED }

/** 退费单状态 */
enum class RefundStatus { REQUESTED, APPROVED, REJECTED, PAID }

/** 退费业绩归因：顾问责任 / 校区教学服务责任 / 客户侧原因 */
enum class Attribution { CONSULTANT, SERVICE, CUSTOMER }

/**
 * 退费原因（结构化，用于业绩重新归因）：
 * - 服务承诺不一致：顾问销售责任，提成全额追回，且校区必须提交整改说明后方可财务审核
 * - 教学质量/其他服务不满意：校区教学服务责任（非顾问个人），提成按已课消比例部分结算
 * - 排课冲突/搬家/个人原因：客户侧原因，顾问已完成获客与已服务部分，提成按已课消比例部分结算
 */
enum class RefundReason(
    val desc: String,
    val attribution: Attribution,
    val rectificationRequired: Boolean
) {
    SERVICE_PROMISE_MISMATCH("服务承诺不一致（承诺与实际服务不符）", Attribution.CONSULTANT, true),
    TEACHER_QUALITY("教学质量不达标", Attribution.SERVICE, false),
    DISSATISFIED("其他服务不满意", Attribution.SERVICE, false),
    SCHEDULE_CONFLICT("排课/时间冲突", Attribution.CUSTOMER, false),
    MOVING("搬家/异地", Attribution.CUSTOMER, false),
    PERSONAL("个人原因（生病/出国等）", Attribution.CUSTOMER, false),
    OTHER("其他原因", Attribution.CUSTOMER, false);

    companion object {
        fun of(code: String?): RefundReason =
            entries.firstOrNull { it.name == code } ?: OTHER
    }
}

/** 投诉状态 */
enum class ComplaintStatus { OPEN, RESOLVED }

/** 课消扣课顺序：先扣付费课时 / 先扣赠课 */
enum class ConsumeOrder { PAID_FIRST, GIFT_FIRST }
