package tech.wenisch.contextcrate.web;

import java.nio.file.*;
import java.util.UUID;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import tech.wenisch.contextcrate.backup.CratePortableService;

@RestController
@RequestMapping("/api/v1")
public class BackupApiController {
  private final CratePortableService portable;
  public BackupApiController(CratePortableService portable){this.portable=portable;}

  @PostMapping("/crates/{crateId}/exports")
  public ResponseEntity<FileSystemResource> export(@PathVariable UUID crateId,
      @RequestParam(defaultValue="true")boolean includeArtifacts)throws Exception{
    Path file=Files.createTempFile("contextcrate-export-",".zip");
    portable.exportTo(crateId,file,includeArtifacts);
    return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION,
        "attachment; filename=contextcrate-"+crateId+".zip")
        .contentType(MediaType.APPLICATION_OCTET_STREAM).body(new FileSystemResource(file));
  }

  @PostMapping(value="/crate-imports/validate",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
  public CratePortableService.Manifest validate(@RequestPart MultipartFile file)throws Exception{
    Path temp=Files.createTempFile("contextcrate-validate-",".zip");file.transferTo(temp);
    try{return portable.validate(temp);}finally{Files.deleteIfExists(temp);}
  }

  @PostMapping(value="/crate-imports",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
  public CratePortableService.ImportResult importCrate(@RequestPart MultipartFile file)throws Exception{
    Path temp=Files.createTempFile("contextcrate-import-",".zip");file.transferTo(temp);
    try{return portable.importFrom(temp);}finally{Files.deleteIfExists(temp);}
  }
}
