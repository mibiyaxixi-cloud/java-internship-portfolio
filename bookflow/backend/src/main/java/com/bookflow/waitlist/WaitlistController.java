package com.bookflow.waitlist;
import com.bookflow.auth.BookFlowUser;
import com.bookflow.common.BusinessException;
import com.bookflow.idempotency.IdempotencyService;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/api/waitlist")
public class WaitlistController {
    private final WaitlistService service;
    private final IdempotencyService requests;
    public WaitlistController(WaitlistService service, IdempotencyService requests) { this.service=service; this.requests=requests; }
    @GetMapping("/mine") public List<WaitlistView> mine(@AuthenticationPrincipal BookFlowUser user) { return service.mine(user.id()); }
    @PostMapping public ResponseEntity<String> join(@AuthenticationPrincipal BookFlowUser user,
            @RequestHeader(value="Idempotency-Key",required=false) String key, @RequestBody JoinRequest input) {
        if(input.slotId()==null || input.slotId()<=0) throw BusinessException.invalid("请选择有效时段");
        return requests.execute(user.id(),key,"JOIN_WAITLIST",input.slotId(),201,()->service.join(user.id(),input.slotId())).toHttp();
    }
    @PostMapping("/{id}/confirm") public ResponseEntity<String> confirm(@AuthenticationPrincipal BookFlowUser user,
            @RequestHeader(value="Idempotency-Key",required=false) String key, @PathVariable long id) {
        return requests.execute(user.id(),key,"CONFIRM_OFFER",id,200,()->service.confirm(user.id(),id)).toHttp();
    }
    @PostMapping("/{id}/withdraw") public WaitlistView withdraw(@AuthenticationPrincipal BookFlowUser user,@PathVariable long id) {
        return service.withdraw(user.id(),id);
    }
    public record JoinRequest(Long slotId) {}
}
