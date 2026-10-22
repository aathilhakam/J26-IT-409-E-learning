package lk.smartplay.adaptive.service;

import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import lk.smartplay.adaptive.model.*;
import lk.smartplay.adaptive.repository.*;

@Service
public class StudentAssignmentService {
    private final org.springframework.context.ApplicationEventPublisher events;
    private final MongoTemplate mongo;
    private final StudentRepository students;
    private final ContentAssetRepository materials;
    public StudentAssignmentService(MongoTemplate mongo, StudentRepository students, ContentAssetRepository materials,org.springframework.context.ApplicationEventPublisher events) {
        this.events=events; this.mongo = mongo; this.students = students; this.materials = materials;
    }
    public void synchronize(ContentAsset material) {
        var allowed = students.findByOwnerId(material.getOwnerId()).stream().map(Student::getId).collect(java.util.stream.Collectors.toSet());
        var ids = material.getAssignmentMode().equals("LIBRARY") ? List.<String>of() : material.getAssignedStudentIds().stream().filter(allowed::contains).distinct().toList();
        mongo.updateMulti(Query.query(Criteria.where("ownerId").is(material.getOwnerId()).and("materialId").is(material.getId()).and("studentId").nin(ids)), new Update().set("active", false), StudentAssignment.class);
        for (String studentId : ids) {
            // Deterministic primary key makes concurrent saves/retries idempotent per pair.
            String id = material.getId() + ":" + studentId;
            var query = Query.query(Criteria.where("_id").is(id));
            var update = new Update().setOnInsert("ownerId", material.getOwnerId()).setOnInsert("materialId", material.getId())
                .setOnInsert("studentId", studentId).setOnInsert("status", material.isIndividualAssignmentsTracked() ? "NOT_STARTED" : "UNRECORDED").setOnInsert("assignedAt", material.isIndividualAssignmentsTracked() ? Instant.now() : null).set("active", true);
            try { mongo.upsert(query, update, StudentAssignment.class); }
            catch (org.springframework.dao.DuplicateKeyException race) { mongo.updateFirst(query, new Update().set("active", true), StudentAssignment.class); }
        }
    }
    /** Repair legacy submissions and retries from saved teacher evidence without changing feedback. */
    public void completeFromSubmittedFeedback(ContentAsset material) {
        if ("LIBRARY".equals(material.getAssignmentMode())) return;
        var allowed = students.findByOwnerId(material.getOwnerId()).stream().map(Student::getId).collect(java.util.stream.Collectors.toSet());
        var submitted = mongo.find(Query.query(Criteria.where("ownerId").is(material.getOwnerId()).and("materialId").is(material.getId())
            .and("submissionStatus").is("SUBMITTED").and("submittedAt").ne(null)), LessonFeedback.class);
        var completed = new HashMap<String,Instant>();
        for (var row : submitted) if (row.getSubmittedAt()!=null && allowed.contains(row.getStudentId()) && material.getAssignedStudentIds().contains(row.getStudentId()))
            completed.merge(row.getStudentId(),row.getSubmittedAt(),(a,b)->a.isBefore(b)?a:b);
        if (completed.isEmpty()) return;
        synchronize(material);
        completed.forEach((studentId,at) -> {
            var changed=mongo.updateFirst(Query.query(Criteria.where("_id").is(material.getId()+":"+studentId)
                .and("ownerId").is(material.getOwnerId()).and("materialId").is(material.getId()).and("active").is(true).and("status").ne("COMPLETED")),
                new Update().set("status","COMPLETED").set("completedAt",at).set("updatedAt",Instant.now()),StudentAssignment.class);
            if(changed.getModifiedCount()>0)events.publishEvent(new LessonCompletionEvent(material.getOwnerId(),studentId));
        });
    }
    public void deactivate(String materialId) {
        mongo.updateMulti(Query.query(Criteria.where("materialId").is(materialId)),new Update().set("active",false),StudentAssignment.class);
    }
    public StudentAssignment update(String materialId, String studentId, String ownerId, String status) {
        var material = materials.findByIdAndOwnerId(materialId, ownerId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Material not found"));
        var student = students.findByIdAndOwnerId(studentId, ownerId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Student not found"));
        if (!student.isActive() || material.getAssignmentMode().equals("LIBRARY") || !material.getAssignedStudentIds().contains(studentId))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Student is not an active recipient of this material");
        if (status == null || !Set.of("NOT_STARTED", "IN_PROGRESS", "COMPLETED").contains(status)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose a valid assignment status");
        // An explicit teacher update can initialize a legacy recipient without inventing completion.
        synchronize(material);
        var update = new Update().set("status", status).set("updatedAt", Instant.now());
        if (status.equals("COMPLETED")) update.set("completedAt", Instant.now()); else update.unset("completedAt");
        var saved=mongo.findAndModify(Query.query(Criteria.where("_id").is(materialId + ":" + studentId).and("ownerId").is(ownerId).and("active").is(true)), update,
            org.springframework.data.mongodb.core.FindAndModifyOptions.options().returnNew(true), StudentAssignment.class);
        if(saved!=null && "COMPLETED".equals(saved.getStatus()))events.publishEvent(new LessonCompletionEvent(ownerId,studentId));
        return saved;
    }
}
