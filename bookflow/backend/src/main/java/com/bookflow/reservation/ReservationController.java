package com.bookflow.reservation;

import com.bookflow.auth.BookFlowUser;
import com.bookflow.idempotency.IdempotencyService;
import org.springframework.http.ResponseEntity;
import com.bookflow.common.BusinessException;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/reservations")
public class ReservationController {
    private final ReservationService service;

    private final IdempotencyService requests;
    public ReservationController(ReservationService service, IdempotencyService requests) { this.service = service; this.requests = requests; }

    @GetMapping("/mine")
    public List<ReservationView> mine(@AuthenticationPrincipal BookFlowUser user) {
        return service.mine(user.id());
    }

    @PostMapping
    public ResponseEntity<String> reserve(@AuthenticationPrincipal BookFlowUser user,
            @RequestHeader(value="Idempotency-Key", required=false) String key, @RequestBody ReserveRequest input) {
        if (input.slotId() == null || input.slotId() <= 0) throw BusinessException.invalid("请选择有效时段");
        return requests.execute(user.id(), key, "RESERVE", input.slotId(), 201, () -> service.reserve(user.id(), input.slotId())).toHttp();
    }

    @PostMapping("/{id}/cancel")
    public ReservationView cancel(@AuthenticationPrincipal BookFlowUser user, @PathVariable long id) {
        if (id <= 0) throw BusinessException.invalid("预约 ID 必须为正整数");
        return service.cancel(user.id(), id);
    }

    public record ReserveRequest(Long slotId) {}
}
