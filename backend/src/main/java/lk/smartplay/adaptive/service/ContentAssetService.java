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

    private final ContentAssetRepository repository;
    private final Path storageDirectory;

    public ContentAssetService(
            ContentAssetRepository repository,
            @Value("${smartplay.content.storage-dir:./data/content}") String storageDirectory) {
        this.repository = repository;
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
        String normalizedTitle = title == null ? "" : title.trim();
        String normalizedCategory = category == null ? "" : category.trim();
        String normalizedDescription = description == null ? "" : description.trim();
        if (normalizedTitle.isEmpty() || normalizedTitle.length() > 120) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Title must be between 1 and 120 characters");
        }
        if (!CATEGORIES.contains(normalizedCategory)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose a valid content category");
        }
        if (normalizedDescription.length() > 1000) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Description must be at most 1000 characters");
        }
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
                normalizedTitle,
                normalizedCategory,
                normalizedDescription,
                originalFileName,
                storageFileName,
                expectedContentType,
                file.getSize());
        try {
            return repository.save(asset);
        } catch (RuntimeException exception) {
            try {
                Files.deleteIfExists(storedFile);
            } catch (IOException cleanupException) {
                exception.addSuppressed(cleanupException);
            }
            throw exception;
        }
    }

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
