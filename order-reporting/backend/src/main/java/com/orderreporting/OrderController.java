package com.orderreporting;
import com.orderreporting.Model.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.Authentication;
import java.time.LocalDate;

@RestController @RequestMapping("/api/orders")
public class OrderController {
    private final Auth auth;private final OrderService service;
    public OrderController(Auth auth,OrderService service){this.auth=auth;this.service=service;}
    @GetMapping Page<Order> list(Authentication a,@RequestParam(required=false) Long storeId,@RequestParam(required=false) LocalDate from,@RequestParam(required=false) LocalDate to,@RequestParam(required=false) String status,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size){return service.list(auth.actor(a),storeId,from,to,status,page,size);}
    @GetMapping("/{id}") Detail detail(Authentication a,@PathVariable long id){return service.detail(auth.actor(a),id);}
}
