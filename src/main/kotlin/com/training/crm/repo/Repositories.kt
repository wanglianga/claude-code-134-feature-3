package com.training.crm.repo

import com.training.crm.domain.*
import org.springframework.data.jpa.repository.JpaRepository
import java.time.LocalDateTime

interface CampusRepo : JpaRepository<Campus, Long>
interface ClassroomRepo : JpaRepository<Classroom, Long> {
    fun findByCampusId(campusId: Long): List<Classroom>
}
interface ConsultantRepo : JpaRepository<Consultant, Long>
interface TeacherRepo : JpaRepository<Teacher, Long>
interface TeacherAvailabilityRepo : JpaRepository<TeacherAvailability, Long> {
    fun findByTeacherId(teacherId: Long): List<TeacherAvailability>
}

interface LeadRepo : JpaRepository<Lead, Long> {
    fun findByConsultantId(consultantId: Long): List<Lead>
}
interface PromiseRecordRepo : JpaRepository<PromiseRecord, Long> {
    fun findByLeadId(leadId: Long): List<PromiseRecord>
    fun findByStudentId(studentId: Long): List<PromiseRecord>
    fun findByContractId(contractId: Long): List<PromiseRecord>
}
interface ChannelFeeRepo : JpaRepository<ChannelFee, Long> {
    fun findByLeadId(leadId: Long): List<ChannelFee>
}
interface GiftRecordRepo : JpaRepository<GiftRecord, Long> {
    fun findByLeadId(leadId: Long): List<GiftRecord>
}

interface TrialClassRepo : JpaRepository<TrialClass, Long> {
    fun findByLeadId(leadId: Long): List<TrialClass>
    fun findByTeacherIdAndStatusAndStartTimeLessThanAndEndTimeGreaterThan(
        teacherId: Long, status: TrialStatus, end: LocalDateTime, start: LocalDateTime
    ): List<TrialClass>
    fun findByClassroomIdAndStatusAndStartTimeLessThanAndEndTimeGreaterThan(
        classroomId: Long, status: TrialStatus, end: LocalDateTime, start: LocalDateTime
    ): List<TrialClass>
}
interface TrialFeedbackRepo : JpaRepository<TrialFeedback, Long> {
    fun findByTrialClassId(trialClassId: Long): TrialFeedback?
}

interface StudentRepo : JpaRepository<Student, Long>
interface CoursePackageRepo : JpaRepository<CoursePackage, Long>
interface ContractRepo : JpaRepository<Contract, Long> {
    fun findByStudentId(studentId: Long): List<Contract>
}
interface InstallmentRepo : JpaRepository<Installment, Long> {
    fun findByContractId(contractId: Long): List<Installment>
}
interface InvoiceRepo : JpaRepository<Invoice, Long> {
    fun findByContractId(contractId: Long): List<Invoice>
}
interface CommissionRepo : JpaRepository<Commission, Long> {
    fun findByContractId(contractId: Long): List<Commission>
    fun findByConsultantId(consultantId: Long): List<Commission>
}
interface MaterialStockRepo : JpaRepository<MaterialStock, Long> {
    fun findByCampusIdAndTextbookVersion(campusId: Long, textbookVersion: String): MaterialStock?
}
interface RectificationReportRepo : JpaRepository<RectificationReport, Long> {
    fun findByRefundOrderId(refundOrderId: Long): List<RectificationReport>
    fun findByStatus(status: String): List<RectificationReport>
}

interface ClazzRepo : JpaRepository<Clazz, Long>
interface EnrollmentRepo : JpaRepository<Enrollment, Long> {
    fun findByStudentIdAndStatus(studentId: Long, status: EnrollmentStatus): List<Enrollment>
    fun findByClazzIdAndStatus(clazzId: Long, status: EnrollmentStatus): List<Enrollment>
    fun findByStudentId(studentId: Long): List<Enrollment>
}
interface LessonRepo : JpaRepository<Lesson, Long> {
    fun findByClazzId(clazzId: Long): List<Lesson>
    fun findByTeacherIdAndStatusAndStartTimeLessThanAndEndTimeGreaterThan(
        teacherId: Long, status: LessonStatus, end: LocalDateTime, start: LocalDateTime
    ): List<Lesson>
    fun findByClassroomIdAndStatusAndStartTimeLessThanAndEndTimeGreaterThan(
        classroomId: Long, status: LessonStatus, end: LocalDateTime, start: LocalDateTime
    ): List<Lesson>
    fun findByClazzIdAndStatus(clazzId: Long, status: LessonStatus): List<Lesson>
}
interface AttendanceRepo : JpaRepository<Attendance, Long> {
    fun findByLessonId(lessonId: Long): List<Attendance>
    fun findByLessonIdAndStudentId(lessonId: Long, studentId: Long): Attendance?
    fun findByContractId(contractId: Long): List<Attendance>
    fun findByStudentId(studentId: Long): List<Attendance>
}
interface LeaveRequestRepo : JpaRepository<LeaveRequest, Long> {
    fun findByStudentIdAndLessonIdAndStatus(studentId: Long, lessonId: Long, status: LeaveStatus): List<LeaveRequest>
}
interface TransferRecordRepo : JpaRepository<TransferRecord, Long>
interface SuspensionRepo : JpaRepository<Suspension, Long> {
    fun findByStudentIdAndStatus(studentId: Long, status: String): List<Suspension>
}

interface ComplaintRepo : JpaRepository<Complaint, Long> {
    fun findByStudentIdAndStatus(studentId: Long, status: ComplaintStatus): List<Complaint>
    fun findByStudentId(studentId: Long): List<Complaint>
}
interface RefundOrderRepo : JpaRepository<RefundOrder, Long> {
    fun findByStudentId(studentId: Long): List<RefundOrder>
    fun findByContractIdAndStatusIn(contractId: Long, statuses: List<RefundStatus>): List<RefundOrder>
}
interface AuditEventRepo : JpaRepository<AuditEvent, Long> {
    fun findByStudentIdOrLeadIdOrderByCreatedAtAsc(studentId: Long, leadId: Long): List<AuditEvent>
    fun findByLeadIdOrderByCreatedAtAsc(leadId: Long): List<AuditEvent>
}
