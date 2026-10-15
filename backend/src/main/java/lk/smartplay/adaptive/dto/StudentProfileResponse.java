package lk.smartplay.adaptive.dto;

import java.util.List;

import lk.smartplay.adaptive.model.Student;

public record StudentProfileResponse(
        Student student,
        double overallAverage,
        long assessmentCount,
        List<DomainProgress> areas) {
}
