package ua.course.courses;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Integration tests of repositories against real PostgreSQL. */
@SpringBootTest
class EnrollmentRepositoryIT {
    @Autowired StudentRepository students;
    @Autowired CourseRepository courses;
    @Autowired EnrollmentRepository enrollments;

    private final List<Long> studentIds = new ArrayList<>();
    private final List<Long> courseIds = new ArrayList<>();
    private final List<Long> enrollmentIds = new ArrayList<>();

    @AfterEach
    void cleanUp() throws Exception {
        for (long id : enrollmentIds) enrollments.delete(id);
        for (long id : studentIds) students.delete(id);
        for (long id : courseIds) courses.delete(id);
    }

    private static String unique() { return UUID.randomUUID().toString().substring(0, 30); }

    private Student student() throws Exception {
        var s = students.create(new Student(0, "RS-" + unique(), 20, 90, false));
        studentIds.add(s.id());
        return s;
    }

    private Course course(int capacity) throws Exception {
        var c = courses.create(new Course(0, "RC-" + unique(), 100000, capacity, 1));
        courseIds.add(c.id());
        return c;
    }

    private Enrollment enroll(Student s, Course c) throws Exception {
        var e = enrollments.create(new Enrollment(0, "RE-" + unique(), s.id(), c.id(), 100000, 100000, 0, EnrollmentStatus.Enrolled));
        enrollmentIds.add(e.id());
        return e;
    }

    @Test
    @DisplayName("StudentRepository: create ігнорує вхідний id, findById повертає той самий запис")
    void studentRoundTrip() throws Exception {
        var saved = students.create(new Student(777777, "RS-" + unique(), 30, 55, true));
        studentIds.add(saved.id());
        assertNotEquals(777777, saved.id());
        assertEquals(saved, students.findById(saved.id()));
    }

    @Test
    @DisplayName("findById відсутнього запису повертає null")
    void findMissing() throws Exception {
        assertNull(students.findById(9000000000000L));
        assertNull(courses.findById(9000000000000L));
        assertNull(enrollments.findById(9000000000000L));
    }

    @Test
    @DisplayName("update відсутньої картки -> IllegalArgumentException")
    void updateMissing() {
        assertThrows(IllegalArgumentException.class,
                () -> students.update(new Student(9000000000000L, "X", 20, 50, false)));
    }

    @Test
    @DisplayName("countOccupied не враховує Cancelled; findEnrollments фільтрує й сортує за id")
    void occupancyAndSelection() throws Exception {
        var course = course(10);
        var e1 = enroll(student(), course);
        var e2 = enroll(student(), course);
        var e3 = enroll(student(), course);
        enrollments.updateStatus(e2.id(), EnrollmentStatus.Cancelled, 100000);

        assertEquals(2, enrollments.countOccupied(course.id()));
        var active = enrollments.findEnrollments(course.id(), EnrollmentStatus.Enrolled);
        assertEquals(List.of(e1.id(), e3.id()), active.stream().map(Enrollment::id).toList());
        assertEquals(1, enrollments.findEnrollments(course.id(), EnrollmentStatus.Cancelled).size());
    }

    @Test
    @DisplayName("updateStatus неактивної заявки -> EnrollmentRejectedException")
    void updateInactive() throws Exception {
        var e = enroll(student(), course(10));
        enrollments.updateStatus(e.id(), EnrollmentStatus.Completed, 0);
        assertThrows(EnrollmentRejectedException.class,
                () -> enrollments.updateStatus(e.id(), EnrollmentStatus.Cancelled, 100000));
        assertEquals(EnrollmentStatus.Completed, enrollments.findById(e.id()).status());
    }

    @Test
    @DisplayName("Слухача із заявкою видалити не можна (FK RESTRICT)")
    void cannotDeleteStudentWithEnrollment() throws Exception {
        var s = student();
        enroll(s, course(10));
        assertThrows(DataIntegrityViolationException.class, () -> students.delete(s.id()));
    }
}
