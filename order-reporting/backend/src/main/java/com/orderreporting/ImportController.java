package com.orderreporting;
import com.orderreporting.Model.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.security.core.Authentication;
import org.springframework.http.*;
import java.util.*;
import java.io.IOException;

@RestController @RequestMapping("/api/imports")
public class ImportController {
    private final Auth auth;private final ImportService service;private final BatchMapper db;
    public ImportController(Auth auth,ImportService service,BatchMapper db){this.auth=auth;this.service=service;this.db=db;}
    @GetMapping("/template") ResponseEntity<String> template(){return ResponseEntity.ok().contentType(new MediaType("text","csv",java.nio.charset.StandardCharsets.UTF_8)).header("Content-Disposition","attachment; filename=orders-template.csv").body(CsvValidator.HEADER+"\r\n");}
    @PostMapping("/precheck") BatchView precheck(@RequestParam("file") MultipartFile file,Authentication a) throws IOException {
        Actor actor=auth.actor(a);
        if(file.getSize()>CsvValidator.MAX_BYTES)throw new Problem(413,"FILE_TOO_LARGE","文件不得超过 1 MiB");
        return service.precheck(file.getBytes(),actor);
    }
    @PostMapping("/{id}/confirm") BatchView confirm(@PathVariable String id,Authentication a){return service.confirm(id,auth.actor(a));}
    @GetMapping BatchList recent(Authentication a){return new BatchList(service.recent(auth.actor(a)));}
    public record BatchList(List<BatchView> items){}
    @GetMapping("/{id}") BatchView get(@PathVariable String id,Authentication a){return service.view(service.owned(id,auth.actor(a)));}
    @GetMapping("/{id}/audit") List<Map<String,Object>> audit(@PathVariable String id,Authentication a){service.owned(id,auth.actor(a));return db.auditFor(id);}
}
