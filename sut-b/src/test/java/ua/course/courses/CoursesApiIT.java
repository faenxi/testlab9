package ua.course.courses;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * API tests: real Spring context + real PostgreSQL (DB_* env variables, migrations applied beforehand).
 * Every test creates unique data and removes it afterwards.
 */
@SpringBootTest
@AutoConfigureMockMvc
class CoursesApiIT {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired StudentRepository studentRepo;
    @Autowired CourseRepository courseRepo;
    @Autowired EnrollmentRepository enrollmentRepo;

    private final List<Long> students = new ArrayList<>();
    private final List<Long> courses = new ArrayList<>();
    private final List<Long> enrollments = new ArrayList<>();

    @AfterEach
    void cleanUp() throws Exception {
        for (long id : enrollments) enrollmentRepo.delete(id);
        for (long id : students) studentRepo.delete(id);
        for (long id : courses) courseRepo.delete(id);
    }

    private static String unique(String prefix) {
        return prefix + UUID.randomUUID().toString().substring(0, 30);
    }

    private ResultActions postJson(String url, String body) throws Exception {
        return mvc.perform(post(url).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private long idOf(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString()).get("id").asLong();
    }

    private long createStudent(boolean regular) throws Exception {
        long id = idOf(postJson("/students",
                "{\"code\":\"" + unique("S-") + "\",\"age\":20,\"entryScore\":90,\"regular\":" + regular + "}")
                .andExpect(status().isCreated()));
        students.add(id);
        return id;
    }

    private long createCourse(int capacity) throws Exception {
        long id = idOf(postJson("/courses",
                "{\"code\":\"" + unique("C-") + "\",\"price\":100000,\"capacity\":" + capacity + ",\"level\":1}")
                .andExpect(status().isCreated()));
        courses.add(id);
        return id;
    }

    private ResultActions enroll(long studentId, long courseId, boolean coupon) throws Exception {
        return postJson("/enrollments", "{\"reference\":\"" + unique("EN-") + "\",\"studentId\":" + studentId
                + ",\"courseId\":" + courseId + ",\"couponValid\":" + coupon + "}");
    }

    private long enrollOk(long studentId, long courseId, boolean coupon) throws Exception {
        long id = idOf(enroll(studentId, courseId, coupon).andExpect(status().isCreated()));
        enrollments.add(id);
        return id;
    }

    // ---------- students / courses ----------

    @Test
    @DisplayName("POST /students -> 201 + Location, GET повертає збережені дані")
    void studentCreatedAndReadable() throws Exception {
        String code = unique("S-");
        var created = postJson("/students", "{\"code\":\"" + code + "\",\"age\":25,\"entryScore\":77,\"regular\":true}")
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"));
        long id = idOf(created);
        students.add(id);

        mvc.perform(get("/students/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.age").value(25))
                .andExpect(jsonPath("$.entryScore").value(77))
                .andExpect(jsonPath("$.regular").value(true));
    }

    @Test
    @DisplayName("POST /students з віком поза діапазоном -> 400")
    void studentInvalidAge() throws Exception {
        postJson("/students", "{\"code\":\"" + unique("S-") + "\",\"age\":5,\"entryScore\":50,\"regular\":false}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    @DisplayName("POST /students з дублікатом code -> 409")
    void studentDuplicateCode() throws Exception {
        String code = unique("S-");
        String body = "{\"code\":\"" + code + "\",\"age\":20,\"entryScore\":50,\"regular\":false}";
        students.add(idOf(postJson("/students", body).andExpect(status().isCreated())));
        postJson("/students", body).andExpect(status().isConflict());
    }

    @Test
    @DisplayName("POST /courses -> 201, GET /courses/{id} -> 200")
    void courseCreatedAndReadable() throws Exception {
        long id = createCourse(5);
        mvc.perform(get("/courses/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.price").value(100000))
                .andExpect(jsonPath("$.capacity").value(5));
    }

    @Test
    @DisplayName("GET неіснуючого слухача -> 404")
    void studentNotFound() throws Exception {
        mvc.perform(get("/students/9000000000000")).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("Некоректний JSON -> 400")
    void malformedJson() throws Exception {
        postJson("/students", "{not json").andExpect(status().isBadRequest());
    }

    // ---------- enrollments ----------

    @Test
    @DisplayName("Повний цикл: зарахування зі знижкою 8% -> скасування з поверненням")
    void enrollAndCancel() throws Exception {
        long student = createStudent(true);
        long course = createCourse(10);

        long id = idOf(enroll(student, course, false)
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.status").value("Enrolled"))
                .andExpect(jsonPath("$.total").value(92000))
                .andExpect(jsonPath("$.prepaid").value(92000))
                .andExpect(jsonPath("$.refund").value(0)));
        enrollments.add(id);

        postJson("/enrollments/" + id + "/cancel", "{\"daysBeforeStart\":7}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("Cancelled"))
                .andExpect(jsonPath("$.refund").value(92000));

        mvc.perform(get("/enrollments/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("Cancelled"))
                .andExpect(jsonPath("$.total").value(92000))
                .andExpect(jsonPath("$.refund").value(92000));
    }

    @Test
    @DisplayName("Промокод має пріоритет над знижкою постійного слухача (15%)")
    void couponPriority() throws Exception {
        long id = enrollOk(createStudent(true), createCourse(10), true);
        mvc.perform(get("/enrollments/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(85000));
    }

    @Test
    @DisplayName("Без знижки: звичайний слухач без промокоду платить повну ціну")
    void noDiscount() throws Exception {
        long id = enrollOk(createStudent(false), createCourse(10), false);
        mvc.perform(get("/enrollments/" + id))
                .andExpect(jsonPath("$.total").value(100000))
                .andExpect(jsonPath("$.prepaid").value(100000));
    }

    @Test
    @DisplayName("Завершення зберігає суми; скасування Completed заборонене (409)")
    void completeThenCancelRejected() throws Exception {
        long id = enrollOk(createStudent(false), createCourse(10), false);

        postJson("/enrollments/" + id + "/complete", "")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("Completed"))
                .andExpect(jsonPath("$.refund").value(0));

        postJson("/enrollments/" + id + "/cancel", "{\"daysBeforeStart\":3}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ENROLLMENT_CONFLICT"));
    }

    @Test
    @DisplayName("Повторне зарахування того самого слухача на курс -> 409")
    void duplicateEnrollment() throws Exception {
        long student = createStudent(false);
        long course = createCourse(10);
        enrollOk(student, course, false);
        enroll(student, course, false).andExpect(status().isConflict());
    }

    @Test
    @DisplayName("Курс заповнений -> 409; після скасування місце вивільняється")
    void capacityLimit() throws Exception {
        long course = createCourse(1);
        long first = enrollOk(createStudent(false), course, false);
        long second = createStudent(false);

        enroll(second, course, false).andExpect(status().isConflict());

        postJson("/enrollments/" + first + "/cancel", "{\"daysBeforeStart\":1}").andExpect(status().isOk());
        enrollOk(second, course, false);
    }

    @Test
    @DisplayName("Зарахування неіснуючого слухача -> 404")
    void enrollUnknownStudent() throws Exception {
        long course = createCourse(10);
        enroll(9000000000000L, course, false).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("GET неіснуючої заявки -> 404; скасування з некоректним терміном -> 400")
    void enrollmentErrors() throws Exception {
        mvc.perform(get("/enrollments/9000000000000")).andExpect(status().isNotFound());

        long id = enrollOk(createStudent(false), createCourse(10), false);
        postJson("/enrollments/" + id + "/cancel", "{\"daysBeforeStart\":-1}").andExpect(status().isBadRequest());
    }
}
