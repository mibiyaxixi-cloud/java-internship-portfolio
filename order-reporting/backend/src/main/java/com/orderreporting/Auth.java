package com.orderreporting;

import com.orderreporting.Model.*;
import org.springframework.stereotype.Service;
import org.springframework.security.core.Authentication;

@Service
public class Auth {
    private final CatalogMapper mapper;
    public Auth(CatalogMapper mapper) { this.mapper=mapper; }
    public Actor actor(Authentication auth) {
        Account a = auth == null ? null : mapper.account(auth.getName());
        if(a==null) throw new Problem(401,"UNAUTHENTICATED","请先登录");
        return new Actor(a.id(),a.username(),a.role(),a.storeId());
    }
    public Long scope(Actor a, Long requested) {
        if(!a.admin() && requested != null && !requested.equals(a.storeId()))
            throw new Problem(403,"FORBIDDEN","不能访问其他门店");
        return a.admin() ? requested : a.storeId();
    }
}
