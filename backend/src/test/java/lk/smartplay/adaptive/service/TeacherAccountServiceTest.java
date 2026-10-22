package lk.smartplay.adaptive.service;

import lk.smartplay.adaptive.dto.RegisterRequest;
import lk.smartplay.adaptive.model.TeacherAccount;
import lk.smartplay.adaptive.repository.TeacherAccountRepository;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TeacherAccountServiceTest {
    private final TeacherAccountRepository repository = mock(TeacherAccountRepository.class);
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder(4);
    private final TeacherAccountService service = new TeacherAccountService(repository, passwordEncoder);

    @Test
    void registerNormalizesEmailAndStoresOnlyPasswordHash() {
        when(repository.existsByEmail("teacher@example.com")).thenReturn(false);
        when(repository.save(any(TeacherAccount.class))).thenAnswer(invocation -> invocation.getArgument(0));

        TeacherAccount account = service.register(
                new RegisterRequest(" Teacher Name ", " Teacher@Example.com ", "StrongPass123"));

        assertEquals("Teacher Name", account.getName());
        assertEquals("teacher@example.com", account.getEmail());
        assertNotEquals("StrongPass123", account.getPasswordHash());
        assertTrue(passwordEncoder.matches("StrongPass123", account.getPasswordHash()));
        verify(repository).save(any(TeacherAccount.class));
    }

    @Test
    void registerRejectsAnExistingEmail() {
        when(repository.existsByEmail("teacher@example.com")).thenReturn(true);

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> service.register(new RegisterRequest("Teacher", "TEACHER@example.com", "StrongPass123")));

        assertEquals(HttpStatus.CONFLICT, exception.getStatusCode());
    }

    @Test
    void registerHandlesDuplicateEmailRaceFromMongoIndex() {
        when(repository.existsByEmail("teacher@example.com")).thenReturn(false);
        when(repository.save(any(TeacherAccount.class))).thenThrow(new DuplicateKeyException("duplicate"));

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> service.register(new RegisterRequest("Teacher", "teacher@example.com", "StrongPass123")));

        assertEquals(HttpStatus.CONFLICT, exception.getStatusCode());
    }
}
