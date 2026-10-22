package lk.smartplay.adaptive.config;
import org.springframework.stereotype.Component;
import org.springframework.data.mongodb.core.mapping.event.*;
import lk.smartplay.adaptive.model.ContentAsset;
import lk.smartplay.adaptive.service.StudentAssignmentService;

/** Covers uploads, metadata edits and approved recommendation assignment through one save path. */
@Component
public class ContentAssignmentListener extends AbstractMongoEventListener<ContentAsset> {
    private final StudentAssignmentService assignments;
    public ContentAssignmentListener(StudentAssignmentService assignments) { this.assignments = assignments; }
    @Override public void onAfterDelete(AfterDeleteEvent<ContentAsset> event) {
        Object id=event.getSource().get("_id"); if (id!=null) assignments.deactivate(id.toString());
    }
    @Override public void onAfterSave(AfterSaveEvent<ContentAsset> event) { assignments.synchronize(event.getSource()); }
}
