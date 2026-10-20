package lk.smartplay.adaptive.controller;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import lk.smartplay.adaptive.dto.ContentAssetResponse;
import lk.smartplay.adaptive.model.ContentAsset;
import lk.smartplay.adaptive.service.ContentAssetService;
import lk.smartplay.adaptive.service.TeacherAccountService;

@RestController
@RequestMapping("/api/content")
public class ContentAssetController {
    private final ContentAssetService contentService;
    private final TeacherAccountService accountService;

    public ContentAssetController(ContentAssetService contentService, TeacherAccountService accountService) {
        this.contentService = contentService;
        this.accountService = accountService;
    }

    @GetMapping
    public List<ContentAssetResponse> getContent(Authentication authentication) {
        return contentService.findAll(ownerId(authentication));
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ContentAssetResponse> upload(
            @RequestParam("title") String title,
            @RequestParam("category") String category,
            @RequestParam(value = "description", required = false) String description,
            @RequestParam("file") MultipartFile file,
            Authentication authentication) {
        ContentAsset asset = contentService.store(ownerId(authentication), title, category, description, file);
        return ResponseEntity.status(HttpStatus.CREATED).body(ContentAssetResponse.from(asset));
    }

    @GetMapping("/{id}/download")
    public ResponseEntity<Resource> download(@PathVariable String id, Authentication authentication) {
        ContentAsset asset = contentService.findOwned(id, ownerId(authentication));
        Path file = contentService.fileFor(asset);
        Resource resource = new FileSystemResource(file);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(asset.getContentType()))
                .contentLength(asset.getSize())
                .cacheControl(CacheControl.noStore())
                .header("X-Content-Type-Options", "nosniff")
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment()
                                .filename(asset.getOriginalFileName(), StandardCharsets.UTF_8)
                                .build()
                                .toString())
                .body(resource);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, String>> delete(@PathVariable String id, Authentication authentication) {
        contentService.delete(id, ownerId(authentication));
        return ResponseEntity.ok(Map.of("message", "Content item deleted"));
    }

    private String ownerId(Authentication authentication) {
        return accountService.accountIdForEmail(authentication.getName());
    }
}
