package com.orderreporting;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.csrf.CsrfToken;
import java.util.Map;
import java.util.List;
import com.orderreporting.Model.*;

@RestController @RequestMapping("/api")
public class AuthController {
    private final Auth auth; private final CatalogMapper catalog;
    public AuthController(Auth auth,CatalogMapper catalog) { this.auth=auth;this.catalog=catalog; }
    @GetMapping("/auth/csrf") Map<String,String> csrf(CsrfToken token) { return Map.of("headerName",token.getHeaderName(),"token",token.getToken()); }
    @GetMapping("/auth/me") Actor me(Authentication a) { return auth.actor(a); }
    @GetMapping("/stores") List<Catalog> stores(Authentication a) { return catalog.stores(auth.scope(auth.actor(a),null)); }
    @GetMapping("/products") List<Catalog> products() { return catalog.products(); }
}
