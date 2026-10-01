package com.training.crm.domain

import jakarta.persistence.*
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime

/** 正式学员（由线索转化而来） */
@Entity
class Student(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long = 0,
    var leadId: Long = 0,
    var name: String = "",
    var campusId: Long = 0,
    @Enumerated(EnumType.STRING)
    var status: StudentStatus = StudentStatus.ACTIVE,
    var createdAt: LocalDateTime = LocalDateTime.now()
)

/** 课包：课程产品定义 */
@Entity
class CoursePackage(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long = 0,
    var name: String = "",
    @Enumerated(EnumType.STRING)
    var subject: Subject = Subject.MATH,
    @Enumerated(EnumType.STRING)
    var level: Level = Level.BASIC,
    @Enumerated(EnumType.STRING)
    var classType: ClassType = ClassType.REGULAR,
    var totalLessons: Int = 0,
    var price: BigDecimal = BigDecimal.ZERO,
    var materialFee: BigDecimal = BigDecimal.ZERO,
    var textbookVersion: String = "",
    var defaultGiftLessons: Int = 0,
    /** 优惠保留阈值：已上课时占付费课时比例达到该值时，退费不回收优惠 */
    var discountKeepThreshold: BigDecimal = BigDecimal("0.5")
)

/**
 * 合同：课包、优惠、赠课、教材费、课消规则绑定在同一学员账户。
 */
@Entity
class Contract(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long = 0,
    var studentId: Long = 0,
    var coursePackageId: Long = 0,
    var consultantId: Long = 0,
    var campusId: Long = 0,
    /** 课包原价 */
    var totalAmount: BigDecimal = BigDecimal.ZERO,
    /** 优惠金额 */
    var discountAmount: BigDecimal = BigDecimal.ZERO,
    /** 实付金额 = totalAmount - discountAmount */
    var finalAmount: BigDecimal = BigDecimal.ZERO,
    /** 付费课时数 */
    var paidLessons: Int = 0,
    /** 赠课课时数（课包默认赠课 + 额外赠课） */
    var giftLessons: Int = 0,
    var materialFee: BigDecimal = BigDecimal.ZERO,
    var materialsDelivered: Boolean = false,
    /** 分期手续费：分期数>1 时按实付金额×费率计收，退费时不予退还（从应退中扣除） */
    var installmentFee: BigDecimal = BigDecimal.ZERO,
    @Enumerated(EnumType.STRING)
    var consumeOrder: ConsumeOrder = ConsumeOrder.PAID_FIRST,
    /** 报名时一致性校验告警（试听反馈 vs 合同承诺），JSON 数组文本 */
    @Column(length = 2000)
    var consistencyWarnings: String? = null,
    @Enumerated(EnumType.STRING)
    var status: ContractStatus = ContractStatus.ACTIVE,
    var createdAt: LocalDateTime = LocalDateTime.now()
)

/** 分期 */
@Entity
class Installment(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long = 0,
    var contractId: Long = 0,
    var seq: Int = 1,
    var amount: BigDecimal = BigDecimal.ZERO,
    var dueDate: LocalDate = LocalDate.now(),
    var paidAt: LocalDateTime? = null,
    @Enumerated(EnumType.STRING)
    var status: InstallmentStatus = InstallmentStatus.PENDING
)

/** 发票 */
@Entity
class Invoice(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long = 0,
    var contractId: Long = 0,
    var amount: BigDecimal = BigDecimal.ZERO,
    var title: String = "",
    var taxNo: String? = null,
    @Enumerated(EnumType.STRING)
    var status: InvoiceStatus = InvoiceStatus.ISSUED,
    var issuedAt: LocalDateTime = LocalDateTime.now()
)

/** 顾问提成 */
@Entity
class Commission(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long = 0,
    var consultantId: Long = 0,
    var contractId: Long = 0,
    var amount: BigDecimal = BigDecimal.ZERO,
    @Enumerated(EnumType.STRING)
    var status: CommissionStatus = CommissionStatus.ACCRUED,
    var updatedAt: LocalDateTime = LocalDateTime.now()
)

/** 教材库存：按校区 + 教材版本维护；报名发放出库，退费完成回收入库 */
@Entity
class MaterialStock(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long = 0,
    var campusId: Long = 0,
    var textbookVersion: String = "",
    var quantity: Int = 0
)
