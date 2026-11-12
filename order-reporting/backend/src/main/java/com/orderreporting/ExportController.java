package com.orderreporting;

import org.springframework.web.bind.annotation.*;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import java.time.LocalDate;
import java.util.*;

@RestController @RequestMapping("/api")
public class ExportController {
    private final Auth auth;
    private final ExportService service;
    private final ExportMapper db;
    public ExportController(Auth auth,ExportService service,ExportMapper db) { this.auth=auth; this.service=service; this.db=db; }
    private ResponseEntity<byte[]> csv(byte[] bytes,String name) {
        return ResponseEntity.ok().contentType(new MediaType("text","csv",java.nio.charset.StandardCharsets.UTF_8))
            .header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename="+name)
            .cacheControl(CacheControl.noStore()).body(bytes);
    }
    @GetMapping("/orders/export")
    ResponseEntity<byte[]> orders(Authentication a,@RequestParam(required=false) Long storeId,
        @RequestParam(required=false) LocalDate from,@RequestParam(required=false) LocalDate to,@RequestParam(required=false) String status) {
        return csv(service.orders(auth.actor(a),storeId,from,to,status),"order-ledger.csv");
    }
    @GetMapping("/reports/lines/export")
    ResponseEntity<byte[]> lines(Authentication a,@RequestParam(required=false) Long storeId,@RequestParam LocalDate from,
        @RequestParam LocalDate to,@RequestParam(required=false) Long productId,@RequestParam(required=false) String eventType) {
        return csv(service.lines(auth.actor(a),storeId,from,to,productId,eventType),"report-event-lines.csv");
    }
    @GetMapping("/exports")
    List<Map<String,Object>> recent(Authentication a) { return db.recent(auth.actor(a).id()); }
}
