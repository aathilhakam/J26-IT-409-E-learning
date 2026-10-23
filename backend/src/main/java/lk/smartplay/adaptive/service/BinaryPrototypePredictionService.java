package lk.smartplay.adaptive.service;

import org.springframework.stereotype.Service;

@Service
public class BinaryPrototypePredictionService {
    private final BinaryPrototypeFeatureService features;
    private final lk.smartplay.adaptive.repository.BinaryPrototypeFeatureSnapshotRepository snapshots;
    private final StudentService students;
    private final AIRecommendationClient ai;
    public BinaryPrototypePredictionService(BinaryPrototypeFeatureService features,
            lk.smartplay.adaptive.repository.BinaryPrototypeFeatureSnapshotRepository snapshots,
            StudentService students, AIRecommendationClient ai) {
        this.features = features; this.snapshots = snapshots; this.students = students; this.ai = ai;
    }
    public lk.smartplay.adaptive.dto.BinaryPrototypePrediction predict(String studentId, String ownerId) {
        ai.requireAuthenticatedInference();
        var student = students.findById(studentId, ownerId);
        if (!student.isActive()) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT, "Cannot predict for an archived student");
        var saved = snapshots == null ? java.util.Optional.<lk.smartplay.adaptive.model.BinaryPrototypeFeatureSnapshot>empty()
                : snapshots.findTopByStudentIdAndOwnerIdOrderByCreatedAtDesc(studentId, ownerId);

        // Prefer features built from the latest saved activity and lesson records. This lets
        // worksheet score imports participate in predictions without editing an older template.
        try {
            var current = features.generate(studentId, ownerId);
            if (current != null) return ai.predictBinaryPrototype(current.features(), current.sourceRecordIds());
        } catch (org.springframework.web.server.ResponseStatusException exception) {
            if (exception.getStatusCode().value() != org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY.value()) throw exception;
            if (saved.isEmpty()) {
                String detail = exception.getReason() == null ? "required student records are unavailable" : exception.getReason();
                throw new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY,
                        "No imported experimental feature template was found for this student. "
                                + "Automatic generation also requires: " + detail
                                + ". Save the student's six numeric values using the Experimental Model template, "
                                + "or add actual activity scores and lesson history. Participation, attention, "
                                + "collaboration, and learning difficulty must come from saved teacher feedback.",
                        exception);
            }
        }
        if (snapshots != null) {
            if (saved.isPresent()) {
                var imported = saved.get().getFeatures();
                if (imported.keySet().containsAll(BinaryPrototypeFeatureService.FEATURE_ORDER))
                    return ai.predictBinaryPrototype(imported, java.util.List.of("prototype_snapshot:" + saved.get().getId()));

                // New workbooks collect six numeric values; the four observation
                // categories come from saved teacher feedback for this student.
                var current = features.completeImportedTemplate(studentId, ownerId, imported);
                var evidence = new java.util.ArrayList<String>();
                evidence.add("prototype_snapshot:" + saved.get().getId());
                evidence.addAll(current.sourceRecordIds());
                return ai.predictBinaryPrototype(current.features(), evidence);
            }
        }
        throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY,
                "No complete saved activity data or imported experimental feature snapshot is available for this student.");
    }
}
