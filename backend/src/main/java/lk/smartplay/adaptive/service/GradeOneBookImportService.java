package lk.smartplay.adaptive.service;

import java.nio.file.*;
import java.util.*;
import java.security.MessageDigest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import lk.smartplay.adaptive.dto.*;
import lk.smartplay.adaptive.repository.ContentAssetRepository;

@Service
public class GradeOneBookImportService {
    public record Book(String filename, String title, String description, String subject, String materialType) {}
    public record Candidate(Book book, String status, String message, long size) {}
    public record Result(String filename, String status, String message, ContentAssetResponse material) {}
    public static final List<Book> BOOKS = List.of(
        new Book("eGr1_2OM Birds.pdf", "Birds", "Grade 1 learning book: Birds.", "", "Learning Book"),
        new Book("eGr1_2OM My Family.pdf", "My Family", "Grade 1 learning book: My Family.", "", "Learning Book"),
        new Book("eGr1_2OM New Year Festival.pdf", "New Year Festival", "Grade 1 learning book: New Year Festival.", "", "Learning Book"),
        new Book("eGr1_2OM Veggie Friends.pdf", "Veggie Friends", "Grade 1 learning book: Veggie Friends.", "", "Learning Book"),
        new Book("eGr1_2TG ActBasedOrelEng (1).pdf", "Activity-Based Oral English Teacher's Handbook", "Grade 1 activity-based oral English teacher handbook.", "English", "Teacher Guide"),
        new Book("eGr01OM ActivityBook.pdf", "My Activity Book", "Grade 1 activity book.", "", "Activity Book"),
        new Book("eGr01OM Song Book.pdf", "Song Book — Grade 1", "Grade 1 song book.", "", "Song Book"),
        new Book("Weather.pdf", "Weather", "Grade 1 learning book: Weather.", "", "Learning Book"));
    private final Path source;
    private final long limit;
    private final ContentAssetService content;
    private final ContentAssetRepository repository;
    public GradeOneBookImportService(ContentAssetService content, ContentAssetRepository repository,
            @Value("${smartplay.content.grade-one-source-dir:../AI-Recommendation/data/raw/original_files/teacher_guides}") String directory,
            @Value("${spring.servlet.multipart.max-file-size:20MB}") String limit) {
        this.content=content; this.repository=repository; this.source=Path.of(directory).toAbsolutePath().normalize(); this.limit=org.springframework.util.unit.DataSize.parse(limit).toBytes();
    }
    private Path file(String name) throws java.io.IOException {
        if (BOOKS.stream().noneMatch(b -> b.filename().equals(name))) throw new IllegalArgumentException("Unapproved book");
        Path path=source.resolve(name).normalize();
        if (!path.getParent().equals(source) || Files.isSymbolicLink(path)) throw new IllegalArgumentException("Invalid source file");
        if (!Files.isRegularFile(path)) throw new NoSuchFileException(name);
        if (!path.toRealPath().getParent().equals(source.toRealPath())) throw new IllegalArgumentException("Invalid source file");
        long size=Files.size(path);
        if (size==0 || size>limit) throw new IllegalArgumentException("PDF must be non-empty and within the configured file-size limit");
        try(var input=Files.newInputStream(path)) {
            if (!Arrays.equals(input.readNBytes(5), "%PDF-".getBytes(java.nio.charset.StandardCharsets.US_ASCII))) throw new IllegalArgumentException("Invalid PDF file type");
        }
        return path;
    }
    public List<Candidate> candidates() {
        return BOOKS.stream().map(b -> {
            try { var p=file(b.filename()); return new Candidate(b,"READY","Ready for private-library import",Files.size(p)); }
            catch(NoSuchFileException e) { return new Candidate(b,"MISSING","Source PDF is missing",0); }
            catch(Exception e) { return new Candidate(b,"FAILED",safeMessage(e),0); }
        }).toList();
    }
    private String safeMessage(Exception e) { return e instanceof IllegalArgumentException ? e.getMessage() : "Could not read or save this PDF; retry or contact the administrator"; }
    public synchronized List<Result> importBooks(String owner, List<Book> requests) {
        if (requests==null || requests.isEmpty() || requests.size()>8 || requests.stream().anyMatch(Objects::isNull)
                || requests.stream().map(Book::filename).distinct().count()!=requests.size()
                || requests.stream().anyMatch(b -> BOOKS.stream().noneMatch(a -> a.filename().equals(b.filename()))))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Select unique approved Grade 1 books");
        var results=new ArrayList<Result>();
        for(var b:requests) {
            try {
                Path p=file(b.filename());
                var digest=MessageDigest.getInstance("SHA-256");
                try(var input=Files.newInputStream(p)) { byte[] buffer=new byte[8192]; int n; while((n=input.read(buffer))!=-1) digest.update(buffer,0,n); }
                String key="grade1-"+HexFormat.of().formatHex(digest.digest());
                String id="upload-"+HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest((owner+":"+key).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
                var existing=repository.findByIdAndOwnerId(id,owner);
                if(existing.isPresent()) { results.add(new Result(b.filename(),"SKIPPED","Already imported for this teacher",ContentAssetResponse.from(existing.get()))); continue; }
                var metadata=new ContentMetadataRequest(b.title(), "Teacher Guide".equals(b.materialType())?"Lesson":"Reading",b.description(),b.subject(),"Grade 1",b.materialType(),List.of(),"TEACHER_ONLY","LIBRARY",List.of(),"");
                var asset=content.store(owner,metadata,new LocalPdf(p, Files.size(p)),key);
                results.add(new Result(b.filename(),"IMPORTED","Saved to My private library",ContentAssetResponse.from(asset)));
            } catch(NoSuchFileException e) { results.add(new Result(b.filename(),"MISSING","Source PDF is missing",null)); }
            catch(Exception e) { results.add(new Result(b.filename(),"FAILED",e instanceof ResponseStatusException r ? r.getReason() : safeMessage(e),null)); }
        }
        return results;
    }
    private record LocalPdf(Path path, long size) implements MultipartFile {
        public String getName(){return "file";} public String getOriginalFilename(){return path.getFileName().toString();}
        public String getContentType(){return "application/pdf";} public boolean isEmpty(){return size==0;} public long getSize(){return size;}
        public byte[] getBytes() throws java.io.IOException{return Files.readAllBytes(path);}
        public java.io.InputStream getInputStream() throws java.io.IOException{return Files.newInputStream(path);}
        public void transferTo(java.io.File destination) throws java.io.IOException{Files.copy(path,destination.toPath());}
    }
}
