package lk.smartplay.adaptive.model;

import org.springframework.data.mongodb.core.mapping.Document;

/** Activity-import records live separately from general learning records. */
@Document(collection = "classroom_activities")
public class ClassroomActivity extends LearningRecord {
    public ClassroomActivity() {
        super();
    }

    public ClassroomActivity(String studentId, String domain, double score, double maxScore, String notes) {
        super(studentId, domain, score, maxScore, notes);
    }
}
