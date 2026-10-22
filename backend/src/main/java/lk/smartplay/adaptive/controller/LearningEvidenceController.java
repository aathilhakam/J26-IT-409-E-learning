package lk.smartplay.adaptive.controller;

import java.util.List;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import lk.smartplay.adaptive.dto.*;
import lk.smartplay.adaptive.model.*;
import lk.smartplay.adaptive.service.*;

@RestController
@RequestMapping("/api/students/{studentId}")
public class LearningEvidenceController {
    private final TeacherObservationService observations;
    private final HistoricalLessonService history;
    private final TeacherAccountService accounts;
    public LearningEvidenceController(TeacherObservationService observations, HistoricalLessonService history, TeacherAccountService accounts) {
        this.observations = observations; this.history = history; this.accounts = accounts;
    }
    @PostMapping("/observations") @ResponseStatus(HttpStatus.CREATED)
    public TeacherObservation observe(@PathVariable String studentId, @Valid @RequestBody CreateTeacherObservationRequest request, Authentication authentication) {
        return observations.create(studentId, accounts.accountIdForEmail(authentication.getName()), request);
    }
    @GetMapping("/observations")
    public List<TeacherObservation> observations(@PathVariable String studentId, Authentication authentication) {
        return observations.findForStudent(studentId, accounts.accountIdForEmail(authentication.getName()));
    }
    @PostMapping("/history") @ResponseStatus(HttpStatus.CREATED)
    public HistoricalLesson record(@PathVariable String studentId, @Valid @RequestBody CreateHistoricalLessonRequest request, Authentication authentication) {
        return history.create(studentId, accounts.accountIdForEmail(authentication.getName()), request);
    }
    @GetMapping("/history")
    public List<HistoricalLesson> history(@PathVariable String studentId, Authentication authentication) {
        return history.findForStudent(studentId, accounts.accountIdForEmail(authentication.getName()));
    }
}
