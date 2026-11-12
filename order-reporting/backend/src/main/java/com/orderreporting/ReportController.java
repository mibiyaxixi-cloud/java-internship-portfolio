package com.orderreporting;

import com.orderreporting.Model.Page;
import com.orderreporting.ReportModel.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.Authentication;
import java.time.LocalDate;

@RestController @RequestMapping("/api/reports")
public class ReportController {
    private final Auth auth;
    private final ReportService service;
    public ReportController(Auth auth,ReportService service) { this.auth=auth; this.service=service; }

    @GetMapping
    Overview overview(Authentication a,@RequestParam(required=false) Long storeId,@RequestParam LocalDate from,@RequestParam LocalDate to) {
        return service.overview(auth.actor(a),storeId,from,to);
    }

    @GetMapping("/lines")
    Page<EventLine> lines(Authentication a,@RequestParam(required=false) Long storeId,@RequestParam LocalDate from,
                          @RequestParam LocalDate to,@RequestParam(required=false) Long productId,
                          @RequestParam(required=false) String eventType,@RequestParam(defaultValue="1") int page,
                          @RequestParam(defaultValue="20") int size) {
        return service.lines(auth.actor(a),storeId,from,to,productId,eventType,page,size);
    }
}
