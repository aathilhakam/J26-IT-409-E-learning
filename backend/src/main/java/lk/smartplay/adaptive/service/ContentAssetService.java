package lk.smartplay.adaptive.service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import lk.smartplay.adaptive.dto.ContentAssetResponse;
import lk.smartplay.adaptive.dto.ContentMetadataRequest;
import lk.smartplay.adaptive.model.ContentAsset;
import lk.smartplay.adaptive.repository.ContentAssetRepository;

@Service
public class ContentAssetService {
    private static final long MAX_FILE_SIZE = 20L * 1024 * 1024;
    private static final Set<String> CATEGORIES = Set.of("Lesson", "Worksheet", "Reading", "Activity", "Other");
    private static final Map<String, String> ALLOWED_TYPES = Map.of(
            "pdf", "application/pdf",
            "jpg", "image/jpeg",
            "jpeg", "image/jpeg",
            "png", "image/png",
            "webp", "image/webp");

    private static final Set<String> MATERIAL_TYPES = Set.of(
            "Teacher Guide", "Lesson Plan", "Worksheet", "Activity Sheet", "Reading Material", "Image", "Other", "Activity Book", "Song Book", "Learning Book");

    private final ContentAssetRepository repository;
    private final Path storageDirectory;
    private final lk.smartplay.adaptive.repository.StudentRepository students;

    public ContentAssetService(
            ContentAssetRepository repository,
            lk.smartplay.adaptive.repository.StudentRepository students,
            @Value("${smartplay.content.storage-dir:./data/content}") String storageDirectory) {
        this.repository = repository;
        this.students = students;
        this.storageDirectory = Path.of(storageDirectory).toAbsolutePath().normalize();
    }

    public List<ContentAssetResponse> findAll(String ownerId) {
        return repository.findByOwnerIdOrderByUploadedAtDesc(ownerId).stream()
                .map(ContentAssetResponse::from)
                .toList();
    }

    public ContentAsset store(
            String ownerId,
            String title,
            String category,
            String description,
            MultipartFile file) {
        return store(ownerId, new ContentMetadataRequest(title, category, description,
                null, null, null, null, null), file);
    }

    public ContentAsset store(String ownerId, ContentMetadataRequest request, MultipartFile file) {
        return store(ownerId, request, file, null);
    }

    public ContentAsset store(String ownerId, ContentMetadataRequest request, MultipartFile file, String requestKey) {
        ContentMetadataRequest metadata = validateMetadata(request);
        Assignment assignment = resolveAssignment(ownerId, request);
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose a file to upload");
        }
        if (file.getSize() > MAX_FILE_SIZE) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "Files must be 20 MB or smaller");
        }

        String originalFileName = safeOriginalFileName(file.getOriginalFilename());
        String extension = fileExtension(originalFileName);
        String expectedContentType = ALLOWED_TYPES.get(extension);
        if (expectedContentType == null || !expectedContentType.equalsIgnoreCase(file.getContentType())) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Use a PDF, JPG, PNG, or WEBP file with a matching file type");
        }

        byte[] signature;
        try (InputStream input = file.getInputStream()) {
            signature = input.readNBytes(12);
        } catch (IOException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Could not read the uploaded file", exception);
        }
        if (!hasValidSignature(extension, signature)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The uploaded file does not match its file type");
        }

        String identity = null, fingerprint = null;
        if (requestKey != null) {
            if (!requestKey.matches("[A-Za-z0-9-]{16,100}")) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid upload request key");
            try {
                var digest = java.security.MessageDigest.getInstance("SHA-256");
                identity = "upload-" + java.util.HexFormat.of().formatHex(digest.digest((ownerId + ":" + requestKey).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
                digest.update(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsBytes(request));
                digest.update(originalFileName.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                try (var input = file.getInputStream()) { byte[] buffer = new byte[8192]; int n; while ((n=input.read(buffer))!=-1) digest.update(buffer,0,n); }
                fingerprint = java.util.HexFormat.of().formatHex(digest.digest());
                var existing = repository.findByIdAndOwnerId(identity,ownerId);
                if (existing.isPresent()) {
                    if (!fingerprint.equals(existing.get().getUploadFingerprint())) throw new ResponseStatusException(HttpStatus.CONFLICT,"Upload request key was already used for different content");
                    return repository.save(existing.get()); // also repairs an interrupted assignment synchronization
                }
            } catch (IOException | java.security.NoSuchAlgorithmException error) { throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,"Could not identify upload",error); }
        }
        String storageFileName = UUID.randomUUID() + "." + extension;
        Path storedFile = resolveStorageFile(storageFileName);
        try {
            Files.createDirectories(storageDirectory);
            try (InputStream input = file.getInputStream()) {
                Files.copy(input, storedFile);
            }
        } catch (IOException exception) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Could not store the uploaded file", exception);
        }

        ContentAsset asset = new ContentAsset(
                ownerId,
                metadata.title(),
                metadata.category(),
                metadata.description(),
                originalFileName,
                storageFileName,
                expectedContentType,
                file.getSize());
        asset.trackIndividualAssignments();
        if (identity != null) asset.uploadIdentity(identity, fingerprint);
        applyMetadata(asset, metadata);
        asset.assign(assignment.mode(), assignment.grade(), assignment.ids());
        try {
            if (identity == null) return repository.save(asset);
            try { return repository.insert(asset); }
            catch (org.springframework.dao.DuplicateKeyException concurrent) {
                var existing = repository.findByIdAndOwnerId(identity,ownerId).orElseThrow(() -> concurrent);
                try { Files.deleteIfExists(storedFile); } catch (IOException cleanup) { throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,"Could not clean up retried upload",cleanup); }
                if (!fingerprint.equals(existing.getUploadFingerprint())) throw new ResponseStatusException(HttpStatus.CONFLICT,"Upload request key was already used for different content");
                return repository.save(existing);
            }
        } catch (RuntimeException exception) {
            try {
                if (asset.getId() == null || repository.findByIdAndOwnerId(asset.getId(),ownerId).isEmpty()) Files.deleteIfExists(storedFile);
            } catch (IOException cleanupException) {
                exception.addSuppressed(cleanupException);
            }
            throw exception;
        }
    }

    public ContentAsset update(String id, String ownerId, ContentMetadataRequest request) {
        ContentAsset asset = findOwned(id, ownerId);
        Assignment assignment = request.assignmentMode() == null
                ? new Assignment(asset.getAssignmentMode(), asset.getAssignmentGrade(), asset.getAssignedStudentIds())
                : resolveAssignment(ownerId, request);
        applyMetadata(asset, validateMetadata(request));
        asset.assign(assignment.mode(), assignment.grade(), assignment.ids());
        return repository.save(asset);
    }

    private record Assignment(String mode, String grade, List<String> ids) {}

    private Assignment resolveAssignment(String ownerId, ContentMetadataRequest request) {
        String mode = text(request.assignmentMode());
        if (mode.isEmpty() || mode.equals("LIBRARY")) return new Assignment("LIBRARY", "", List.of());
        if (!Set.of("STUDENTS", "GRADE", "ALL").contains(mode)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose a valid student assignment");
        }
        var roster = students.findByOwnerId(ownerId).stream().filter(lk.smartplay.adaptive.model.Student::isActive).toList();
        List<String> ids;
        String grade = text(request.assignmentGrade());
        if (mode.equals("STUDENTS")) {
            ids = request.studentIds() == null ? List.of() : request.studentIds().stream().distinct().toList();
            if (ids.isEmpty() || ids.stream().anyMatch(id -> roster.stream().noneMatch(student -> student.getId().equals(id)))) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Select active students from your own classroom");
            }
        } else {
            if (mode.equals("GRADE") && grade.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose an assignment grade");
            ids = roster.stream().filter(student -> mode.equals("ALL") || grade.equals(student.getGrade()))
                    .map(lk.smartplay.adaptive.model.Student::getId).toList();
            if (ids.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No active students match this assignment");
        }
        return new Assignment(mode, mode.equals("GRADE") ? grade : "", ids);
    }

    private static void applyMetadata(ContentAsset asset, ContentMetadataRequest metadata) {
        asset.updateMetadata(metadata.title(), metadata.category(), metadata.description(),
                metadata.subject(), metadata.grade(), metadata.materialType(), metadata.tags(), metadata.visibility());
    }

    private static ContentMetadataRequest validateMetadata(ContentMetadataRequest request) {
        if (request == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Material metadata is required");
        String title = text(request.title());
        String category = text(request.category());
        String description = text(request.description());
        String subject = text(request.subject());
        String grade = text(request.grade());
        String materialType = text(request.materialType());
        String visibility = text(request.visibility());
        if (title.isEmpty() || title.length() > 120) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Title must be between 1 and 120 characters");
        }
        if (!CATEGORIES.contains(category)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose a valid content category");
        }
        if (description.length() > 1000) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Description must be at most 1000 characters");
        }
        // Empty material type supports existing uploads without material-specific metadata.
        if (!materialType.isEmpty() && !MATERIAL_TYPES.contains(materialType)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose a valid material type");
        }
        if (subject.length() > 100 || grade.length() > 60
                || (!materialType.isEmpty() && grade.isEmpty())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Materials require a grade or age group (up to 60 characters); optional subjects must be at most 100 characters");
        }
        List<String> tags = request.tags() == null ? List.of() : request.tags();
        if (tags.size() > 20 || tags.stream().anyMatch(tag -> tag == null || text(tag).isEmpty() || text(tag).length() > 40)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Use up to 20 tags, each between 1 and 40 characters");
        }
        if (!visibility.isEmpty() && !visibility.equals("TEACHER_ONLY")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Only teacher-only visibility is supported");
        }
        return new ContentMetadataRequest(title, category, description, subject, grade, materialType,
                tags.stream().map(String::trim).distinct().toList(), "TEACHER_ONLY");
    }

    private static String text(String value) { return value == null ? "" : value.trim(); }

    public ContentAsset findOwned(String id, String ownerId) {
        return repository.findByIdAndOwnerId(id, ownerId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Content item not found"));
    }

    public Path fileFor(ContentAsset asset) {
        Path file = resolveStorageFile(asset.getStorageFileName());
        if (!Files.isRegularFile(file)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Stored content file not found");
        }
        return file;
    }

    public void delete(String id, String ownerId) {
        ContentAsset asset = findOwned(id, ownerId);
        Path storedFile = resolveStorageFile(asset.getStorageFileName());
        try {
            Files.deleteIfExists(storedFile);
        } catch (IOException exception) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Could not delete the stored file", exception);
        }
        repository.delete(asset);
    }

    private Path resolveStorageFile(String storageFileName) {
        Path file = storageDirectory.resolve(storageFileName).normalize();
        if (!file.startsWith(storageDirectory)) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Invalid stored file path");
        }
        return file;
    }

    private static String safeOriginalFileName(String suppliedName) {
        if (suppliedName == null || suppliedName.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Uploaded file must have a name");
        }
        String basename = suppliedName.replace('\\', '/');
        basename = basename.substring(basename.lastIndexOf('/') + 1).replaceAll("[\\p{Cntrl}]", "").trim();
        if (basename.isEmpty() || basename.length() > 180) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "File name must be 1 to 180 characters");
        }
        return basename;
    }

    private static String fileExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) return "";
        return fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static boolean hasValidSignature(String extension, byte[] bytes) {
        return switch (extension) {
            case "pdf" -> startsWith(bytes, "%PDF-".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            case "png" -> startsWith(bytes, new byte[] {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a});
            case "jpg", "jpeg" -> bytes.length >= 3
                    && (bytes[0] & 0xff) == 0xff && (bytes[1] & 0xff) == 0xd8 && (bytes[2] & 0xff) == 0xff;
            case "webp" -> bytes.length >= 12
                    && startsWith(bytes, "RIFF".getBytes(java.nio.charset.StandardCharsets.US_ASCII))
                    && new String(bytes, 8, 4, java.nio.charset.StandardCharsets.US_ASCII).equals("WEBP");
            default -> false;
        };
    }

    private static boolean startsWith(byte[] source, byte[] prefix) {
        if (source.length < prefix.length) return false;
        for (int index = 0; index < prefix.length; index++) {
            if (source[index] != prefix[index]) return false;
        }
        return true;
    }
}
