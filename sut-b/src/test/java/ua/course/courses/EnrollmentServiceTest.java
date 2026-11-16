package ua.course.courses;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Unit tests: no database, no network. All dependencies are mocked. */
class EnrollmentServiceTest {
    private StudentRepository students;
    private CourseRepository courses;
    private EnrollmentRepository enrollments;
    private EnrollmentPolicy policy;
    private EnrollmentService service;

    @BeforeEach
    void setUp() {
        students = mock(StudentRepository.class);
        courses = mock(CourseRepository.class);
        enrollments = mock(EnrollmentRepository.class);
        policy = mock(EnrollmentPolicy.class);
        service = new EnrollmentService(students, courses, enrollments, policy);
    }

    private void givenStudentAndCourse(boolean regular, int capacity) throws Exception {
        when(students.findById(1L)).thenReturn(new Student(1, "S-1", 20, 90, regular));
        when(courses.findById(2L)).thenReturn(new Course(2, "C-1", 100000, capacity, 1));
    }

    // ---------- calculateTotal ----------

    @ParameterizedTest(name = "price={0}, regular={1}, coupon={2} -> {3}")
    @CsvSource({
            "100000, false, false, 100000",
            "100000, true,  false, 92000",
            "100000, false, true,  85000",
            "100000, true,  true,  85000",   // промокод має пріоритет, знижки не додаються
            "1001,   true,  false, 921",     // 8% = 80.08 -> округлення донизу
            "100,    false, true,  85",      // нижня межа ціни
            "10000000, false, false, 10000000" // верхня межа ціни
    })
    @DisplayName("calculateTotal: знижки й округлення")
    void calculateTotal(long price, boolean regular, boolean coupon, long expected) {
        assertEquals(expected, service.calculateTotal(price, regular, coupon));
    }

    @Test
    @DisplayName("calculateTotal: некоректна ціна -> IllegalArgumentException")
    void calculateTotalInvalidPrice() {
        assertThrows(IllegalArgumentException.class, () -> service.calculateTotal(99, false, false));
        assertThrows(IllegalArgumentException.class, () -> service.calculateTotal(10000001, false, false));
    }

    // ---------- isEligible / calculateRefund ----------

    @Test
    @DisplayName("isEligible: коректні межові значення")
    void isEligibleBoundaries() {
        assertTrue(service.isEligible(14, 0, 1, false));
        assertTrue(service.isEligible(100, 100, 3, true));
    }

    @Test
    @DisplayName("isEligible: некоректні входи -> IllegalArgumentException")
    void isEligibleInvalid() {
        assertThrows(IllegalArgumentException.class, () -> service.isEligible(13, 50, 1, false));
        assertThrows(IllegalArgumentException.class, () -> service.isEligible(20, 101, 1, false));
        assertThrows(IllegalArgumentException.class, () -> service.isEligible(20, 50, 4, false));
    }

    @Test
    @DisplayName("calculateRefund: повертається вся передоплата")
    void calculateRefund() {
        assertEquals(92000, service.calculateRefund(92000, 0));
        assertEquals(92000, service.calculateRefund(92000, 36500));
    }

    @Test
    @DisplayName("calculateRefund: некоректні входи -> IllegalArgumentException")
    void calculateRefundInvalid() {
        assertThrows(IllegalArgumentException.class, () -> service.calculateRefund(-1, 5));
        assertThrows(IllegalArgumentException.class, () -> service.calculateRefund(100, 36501));
    }

    // ---------- enroll ----------

    @Test
    @DisplayName("enroll: успіх створює Enrolled з total=prepaid і refund=0")
    void enrollSuccess() throws Exception {
        givenStudentAndCourse(true, 10);
        when(enrollments.hasEnrollment(1, 2)).thenReturn(false);
        when(enrollments.countOccupied(2)).thenReturn(0L);
        when(policy.isAllowed(any(), any())).thenReturn(true);
        when(enrollments.create(any())).thenAnswer(inv -> inv.getArgument(0));

        service.enroll(new EnrollmentRequest("EN-1", 1, 2, false));

        var captor = ArgumentCaptor.forClass(Enrollment.class);
        verify(enrollments).create(captor.capture());
        var saved = captor.getValue();
        assertEquals(EnrollmentStatus.Enrolled, saved.status());
        assertEquals(92000, saved.total());
        assertEquals(92000, saved.prepaid());
        assertEquals(0, saved.refund());
        verify(policy, times(1)).isAllowed(any(), any());
    }

    @Test
    @DisplayName("enroll: слухача не знайдено -> ResourceNotFoundException")
    void enrollStudentMissing() throws Exception {
        when(students.findById(1L)).thenReturn(null);
        assertThrows(ResourceNotFoundException.class, () -> service.enroll(new EnrollmentRequest("EN-1", 1, 2, false)));
        verify(enrollments, never()).create(any());
    }

    @Test
    @DisplayName("enroll: курс не знайдено -> ResourceNotFoundException")
    void enrollCourseMissing() throws Exception {
        when(students.findById(1L)).thenReturn(new Student(1, "S-1", 20, 90, false));
        when(courses.findById(2L)).thenReturn(null);
        assertThrows(ResourceNotFoundException.class, () -> service.enroll(new EnrollmentRequest("EN-1", 1, 2, false)));
        verify(enrollments, never()).create(any());
    }

    @Test
    @DisplayName("enroll: немає вільних місць -> відмова, policy не викликається")
    void enrollNoSeats() throws Exception {
        givenStudentAndCourse(false, 1);
        when(enrollments.hasEnrollment(1, 2)).thenReturn(false);
        when(enrollments.countOccupied(2)).thenReturn(1L);

        assertThrows(EnrollmentRejectedException.class, () -> service.enroll(new EnrollmentRequest("EN-1", 1, 2, false)));
        verify(policy, never()).isAllowed(any(), any());
        verify(enrollments, never()).create(any());
    }

    @Test
    @DisplayName("enroll: повторне зарахування -> відмова, policy не викликається")
    void enrollDuplicate() throws Exception {
        givenStudentAndCourse(false, 10);
        when(enrollments.hasEnrollment(1, 2)).thenReturn(true);

        assertThrows(EnrollmentRejectedException.class, () -> service.enroll(new EnrollmentRequest("EN-1", 1, 2, false)));
        verify(policy, never()).isAllowed(any(), any());
        verify(enrollments, never()).create(any());
    }

    @Test
    @DisplayName("enroll: зовнішня політика каже false -> відмова без запису")
    void enrollPolicyDenied() throws Exception {
        givenStudentAndCourse(false, 10);
        when(enrollments.hasEnrollment(1, 2)).thenReturn(false);
        when(enrollments.countOccupied(2)).thenReturn(0L);
        when(policy.isAllowed(any(), any())).thenReturn(false);

        assertThrows(EnrollmentRejectedException.class, () -> service.enroll(new EnrollmentRequest("EN-1", 1, 2, false)));
        verify(enrollments, never()).create(any());
    }

    @Test
    @DisplayName("enroll: виняток policy передається виклику без запису")
    void enrollPolicyFails() throws Exception {
        givenStudentAndCourse(false, 10);
        when(enrollments.hasEnrollment(1, 2)).thenReturn(false);
        when(enrollments.countOccupied(2)).thenReturn(0L);
        when(policy.isAllowed(any(), any())).thenThrow(new IllegalStateException("down"));

        assertThrows(IllegalStateException.class, () -> service.enroll(new EnrollmentRequest("EN-1", 1, 2, false)));
        verify(enrollments, never()).create(any());
    }

    @Test
    @DisplayName("enroll: некоректна заявка -> IllegalArgumentException")
    void enrollInvalidRequest() {
        assertThrows(IllegalArgumentException.class, () -> service.enroll(null));
        assertThrows(IllegalArgumentException.class, () -> service.enroll(new EnrollmentRequest(" ", 1, 2, false)));
        assertThrows(IllegalArgumentException.class, () -> service.enroll(new EnrollmentRequest("X".repeat(41), 1, 2, false)));
        assertThrows(IllegalArgumentException.class, () -> service.enroll(new EnrollmentRequest("EN-1", 0, 2, false)));
        assertThrows(IllegalArgumentException.class, () -> service.enroll(new EnrollmentRequest("EN-1", 1, -5, false)));
    }

    // ---------- complete / cancel ----------

    private Enrollment stored(EnrollmentStatus status) {
        return new Enrollment(5, "EN-1", 1, 2, 92000, 92000, 0, status);
    }

    @Test
    @DisplayName("cancel: refund = збережена передоплата, статус Cancelled")
    void cancelEnrolled() throws Exception {
        when(enrollments.findById(5L)).thenReturn(stored(EnrollmentStatus.Enrolled));

        var result = service.cancel(5, 7);

        assertEquals(EnrollmentStatus.Cancelled, result.status());
        assertEquals(92000, result.refund());
        assertEquals(92000, result.total());
        verify(enrollments).updateStatus(5, EnrollmentStatus.Cancelled, 92000);
        verify(policy, never()).isAllowed(any(), any());
    }

    @Test
    @DisplayName("complete: статус Completed, суми не змінюються")
    void completeEnrolled() throws Exception {
        when(enrollments.findById(5L)).thenReturn(stored(EnrollmentStatus.Enrolled));

        var result = service.complete(5);

        assertEquals(EnrollmentStatus.Completed, result.status());
        assertEquals(92000, result.prepaid());
        assertEquals(0, result.refund());
        verify(enrollments).updateStatus(5, EnrollmentStatus.Completed, 0);
    }

    @Test
    @DisplayName("cancel/complete: заборонені переходи зі статусів Completed і Cancelled")
    void forbiddenTransitions() throws Exception {
        when(enrollments.findById(6L)).thenReturn(stored(EnrollmentStatus.Completed));
        when(enrollments.findById(7L)).thenReturn(stored(EnrollmentStatus.Cancelled));

        assertThrows(EnrollmentRejectedException.class, () -> service.cancel(6, 3));
        assertThrows(EnrollmentRejectedException.class, () -> service.complete(6));
        assertThrows(EnrollmentRejectedException.class, () -> service.cancel(7, 3));
        assertThrows(EnrollmentRejectedException.class, () -> service.complete(7));
        verify(enrollments, never()).updateStatus(anyLong(), any(), anyLong());
    }

    @Test
    @DisplayName("cancel/complete: відсутня заявка або некоректні входи")
    void cancelCompleteInvalid() throws Exception {
        when(enrollments.findById(99L)).thenReturn(null);
        assertThrows(ResourceNotFoundException.class, () -> service.cancel(99, 1));
        assertThrows(ResourceNotFoundException.class, () -> service.complete(99));
        assertThrows(IllegalArgumentException.class, () -> service.cancel(5, -1));
        assertThrows(IllegalArgumentException.class, () -> service.cancel(5, 36501));
        assertThrows(IllegalArgumentException.class, () -> service.complete(0));
    }
}
