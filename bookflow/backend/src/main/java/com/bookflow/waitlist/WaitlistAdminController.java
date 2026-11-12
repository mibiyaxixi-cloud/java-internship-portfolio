package com.bookflow.waitlist;
import java.util.List;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/api/admin/waitlist")
public class WaitlistAdminController {
    private final WaitlistMapper queue;
    public WaitlistAdminController(WaitlistMapper queue) { this.queue=queue; }
    @GetMapping public List<WaitlistView> all() { return queue.all(); }
    @GetMapping("/tasks") public List<WaitlistTask> tasks() { return queue.tasks(); }
}
