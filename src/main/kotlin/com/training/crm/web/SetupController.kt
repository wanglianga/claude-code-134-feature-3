package com.training.crm.web

import com.training.crm.common.BusinessException
import com.training.crm.domain.*
import com.training.crm.repo.*
import org.springframework.web.bind.annotation.*
import java.time.LocalTime

/** 基础数据维护：校区/教室/老师/顾问/课包/班级 */
@RestController
@RequestMapping("/api/setup")
class SetupController(
    private val campusRepo: CampusRepo,
    private val classroomRepo: ClassroomRepo,
    private val teacherRepo: TeacherRepo,
    private val teacherAvailabilityRepo: TeacherAvailabilityRepo,
    private val consultantRepo: ConsultantRepo,
    private val coursePackageRepo: CoursePackageRepo,
    private val clazzRepo: ClazzRepo,
    private val materialStockRepo: MaterialStockRepo
) {
    @PostMapping("/campuses")
    fun createCampus(@RequestBody req: Campus) = campusRepo.save(req)

    @GetMapping("/campuses")
    fun campuses() = campusRepo.findAll()

    @PostMapping("/classrooms")
    fun createClassroom(@RequestBody req: Classroom) = classroomRepo.save(req)

    @PostMapping("/teachers")
    fun createTeacher(@RequestBody req: Teacher) = teacherRepo.save(req)

    @GetMapping("/teachers")
    fun teachers() = teacherRepo.findAll()

    @PostMapping("/teachers/{id}/availability")
    fun addAvailability(@PathVariable id: Long, @RequestBody req: AvailabilityReq): TeacherAvailability {
        teacherRepo.findById(id).orElseThrow { BusinessException("NOT_FOUND", "老师不存在: $id") }
        return teacherAvailabilityRepo.save(
            TeacherAvailability(teacherId = id, dayOfWeek = req.dayOfWeek, startTime = req.startTime, endTime = req.endTime)
        )
    }

    @PostMapping("/consultants")
    fun createConsultant(@RequestBody req: Consultant) = consultantRepo.save(req)

    @PostMapping("/packages")
    fun createPackage(@RequestBody req: CoursePackage) = coursePackageRepo.save(req)

    @GetMapping("/packages")
    fun packages() = coursePackageRepo.findAll()

    @PostMapping("/classes")
    fun createClass(@RequestBody req: Clazz) = clazzRepo.save(req)

    @GetMapping("/classes")
    fun classes() = clazzRepo.findAll()

    /** 教材库存：按校区 + 教材版本 */
    @GetMapping("/material-stock")
    fun materialStock() = materialStockRepo.findAll()
}

data class AvailabilityReq(val dayOfWeek: Int, val startTime: LocalTime, val endTime: LocalTime)
