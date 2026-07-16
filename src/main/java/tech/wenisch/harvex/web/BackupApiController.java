package tech.wenisch.harvex.web;

import java.nio.file.*;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import tech.wenisch.harvex.backup.PortableBackupService;

@RestController
@RequestMapping("/api/v1/backups")
public class BackupApiController {
  private final PortableBackupService backups;

  public BackupApiController(PortableBackupService backups) {
    this.backups = backups;
  }

  @PostMapping
  public ResponseEntity<FileSystemResource> create(
      @RequestParam(defaultValue = "true") boolean includeArtifacts) throws Exception {
    Path file = Files.createTempFile("harvex-backup-", ".zip");
    backups.create(file, includeArtifacts);
    return ResponseEntity.ok()
        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=harvex-backup.zip")
        .contentType(MediaType.APPLICATION_OCTET_STREAM)
        .body(new FileSystemResource(file));
  }

  @PostMapping(value = "/validate", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public PortableBackupService.Manifest validate(@RequestPart MultipartFile file) throws Exception {
    Path temp = Files.createTempFile("harvex-validate-", ".zip");
    file.transferTo(temp);
    try {
      return backups.validate(temp);
    } finally {
      Files.deleteIfExists(temp);
    }
  }

  @PostMapping(value = "/restore", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public PortableBackupService.RestoreResult restore(@RequestPart MultipartFile file)
      throws Exception {
    Path temp = Files.createTempFile("harvex-restore-upload-", ".zip");
    file.transferTo(temp);
    try {
      return backups.restore(temp);
    } finally {
      Files.deleteIfExists(temp);
    }
  }
}
