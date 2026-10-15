package lk.smartplay.adaptive.service;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

import lk.smartplay.adaptive.dto.RegisterRequest;
import lk.smartplay.adaptive.model.TeacherAccount;
import lk.smartplay.adaptive.repository.TeacherAccountRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class TeacherAccountService {
    private final TeacherAccountRepository repository;
    private final PasswordEncoder passwordEncoder;

    public TeacherAccountService(TeacherAccountRepository repository, PasswordEncoder passwordEncoder) {
        this.repository = repository;
        this.passwordEncoder = passwordEncoder;
    }

    public TeacherAccount register(RegisterRequest request) {
        String email = normalizeEmail(request.email());
        if (request.password().getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Password must be at most 72 UTF-8 bytes");
        }
        if (repository.existsByEmail(email)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "An account with this email already exists");
        }

        TeacherAccount account = new TeacherAccount(
                request.name().trim(),
                email,
                passwordEncoder.encode(request.password()));
        try {
            return repository.save(account);
        } catch (DuplicateKeyException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "An account with this email already exists");
        }
    }

    public static String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    public String accountIdForEmail(String email) {
        return repository.findByEmail(normalizeEmail(email))
                .map(TeacherAccount::getId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Please log in to continue"));
    }
}
