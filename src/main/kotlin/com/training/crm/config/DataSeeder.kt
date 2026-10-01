package com.training.crm.config

import com.training.crm.domain.*
import com.training.crm.repo.*
import com.training.crm.service.DEFAULT_TEXTBOOK
import org.springframework.boot.CommandLineRunner
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.LocalTime

/**
 * 启动种子数据：2 个校区、2 名顾问、3 名老师（张老师跨校区共享）、
 * 教室、老师空档、2 个课包、1 个正式班级。测试账号见 README。
 */
@Component
class DataSeeder(
    private val campusRepo: CampusRepo,
    private val classroomRepo: ClassroomRepo,
    private val consultantRepo: ConsultantRepo,
    private val teacherRepo: TeacherRepo,
    private val teacherAvailabilityRepo: TeacherAvailabilityRepo,
    private val coursePackageRepo: CoursePackageRepo,
    private val clazzRepo: ClazzRepo,
    private val materialStockRepo: MaterialStockRepo
) : CommandLineRunner {

    override fun run(vararg args: String?) {
        if (campusRepo.count() > 0) return

        // 校区
        val c1 = campusRepo.save(Campus(name = "中关村校区", city = "北京"))
        val c2 = campusRepo.save(Campus(name = "望京校区", city = "北京"))

        // 教室
        classroomRepo.save(Classroom(campusId = c1.id, name = "A101", capacity = 4))
        classroomRepo.save(Classroom(campusId = c1.id, name = "A102", capacity = 8))
        classroomRepo.save(Classroom(campusId = c2.id, name = "B201", capacity = 6))

        // 顾问
        consultantRepo.save(Consultant(name = "王顾问", campusId = c1.id, phone = "13800000001"))
        consultantRepo.save(Consultant(name = "李顾问", campusId = c2.id, phone = "13800000002"))

        // 老师：张老师跨校区共享（中关村+望京），刘老师仅中关村，陈老师仅望京
        val t1 = teacherRepo.save(Teacher(name = "张老师", subjects = "MATH", campusIds = "${c1.id},${c2.id}", phone = "13900000001"))
        val t2 = teacherRepo.save(Teacher(name = "刘老师", subjects = "ENGLISH", campusIds = "${c1.id}", phone = "13900000002"))
        val t3 = teacherRepo.save(Teacher(name = "陈老师", subjects = "MATH,PHYSICS", campusIds = "${c2.id}", phone = "13900000003"))

        // 老师空档：工作日 18:00-21:00，周末 09:00-18:00
        listOf(t1.id, t2.id, t3.id).forEach { tid ->
            (1..5).forEach { dow ->
                teacherAvailabilityRepo.save(TeacherAvailability(teacherId = tid, dayOfWeek = dow, startTime = LocalTime.of(18, 0), endTime = LocalTime.of(21, 0)))
            }
            (6..7).forEach { dow ->
                teacherAvailabilityRepo.save(TeacherAvailability(teacherId = tid, dayOfWeek = dow, startTime = LocalTime.of(9, 0), endTime = LocalTime.of(18, 0)))
            }
        }

        // 课包
        coursePackageRepo.save(
            CoursePackage(
                name = "数学思维提高班课包", subject = Subject.MATH, level = Level.INTERMEDIATE, classType = ClassType.REGULAR,
                totalLessons = 48, price = BigDecimal("9600"), materialFee = BigDecimal("300"),
                textbookVersion = "人教版数学", defaultGiftLessons = 4
            )
        )
        coursePackageRepo.save(
            CoursePackage(
                name = "英语听说基础课包", subject = Subject.ENGLISH, level = Level.BASIC, classType = ClassType.REGULAR,
                totalLessons = 48, price = BigDecimal("8800"), materialFee = BigDecimal("200"),
                textbookVersion = "新概念青少版", defaultGiftLessons = 2
            )
        )

        // 正式班级：数学提高1班（中关村，张老师，A101，容量4）
        val room = classroomRepo.findByCampusId(c1.id).first { it.name == "A101" }
        clazzRepo.save(
            Clazz(
                name = "数学提高1班", campusId = c1.id, subject = Subject.MATH, level = Level.INTERMEDIATE,
                classType = ClassType.REGULAR, teacherId = t1.id, classroomId = room.id, capacity = 4,
                textbookVersion = "人教版数学", scheduleDesc = "每周六 10:00-12:00"
            )
        )

        // 教材库存：每校区 × 每科目默认教材 20 册
        listOf(c1.id, c2.id).forEach { campusId ->
            DEFAULT_TEXTBOOK.values.toSet().forEach { version ->
                materialStockRepo.save(MaterialStock(campusId = campusId, textbookVersion = version, quantity = 20))
            }
        }
    }
}
