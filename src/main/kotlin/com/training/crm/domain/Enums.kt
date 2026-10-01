package com.training.crm.domain

/** 科目 */
enum class Subject { MATH, ENGLISH, CHINESE, PHYSICS, CHEMISTRY }

/** 学生基础水平 / 班级难度等级 */
enum class Level { BEGINNER, BASIC, INTERMEDIATE, ADVANCED }

/** 班型 */
enum class ClassType { REGULAR, INTENSIVE, ONE_ON_ONE, SPRINT }

/** 线索状态：新建 → 已约试听 → 试听完成 → 已转化 / 已流失；退费完成后置为「退费失效」，不再计入有效报名 */
enum class LeadStatus { NEW, TRIAL_SCHEDULED, TRIAL_DONE, CONVERTED, LOST, REFUNDED }

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

/** 顾问提成状态：计提 → 冻结（退费中）→ 追回 / 结算 */
enum class CommissionStatus { ACCRUED, FROZEN, CLAWED_BACK, SETTLED }

/** 退费单状态 */
enum class RefundStatus { REQUESTED, APPROVED, REJECTED, PAID }

/**
 * 退费原因分类：决定顾问业绩重新归因与是否触发校区整改。
 * PERSONAL=家长个人原因；SERVICE_QUALITY=服务质量不满意；PROMISE_MISMATCH=服务承诺不一致；
 * TEACHER_ISSUE=老师问题；OTHER=其他
 */
enum class RefundReasonCategory { PERSONAL, SERVICE_QUALITY, PROMISE_MISMATCH, TEACHER_ISSUE, OTHER }

/** 退费业绩归因：CONSULTANT=计入顾问责任；CAMPUS=计入校区责任；NONE=非责任退费 */
enum class RefundAttribution { CONSULTANT, CAMPUS, NONE }

/** 投诉状态 */
enum class ComplaintStatus { OPEN, RESOLVED }

/** 课消扣课顺序：先扣付费课时 / 先扣赠课 */
enum class ConsumeOrder { PAID_FIRST, GIFT_FIRST }
