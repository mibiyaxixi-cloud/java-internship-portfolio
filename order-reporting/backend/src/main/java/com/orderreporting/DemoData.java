package com.orderreporting;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.core.env.Environment;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;

@Component @Profile("demo")
public class DemoData implements ApplicationRunner {
    private final CatalogMapper db; private final Environment env; private final PasswordEncoder encoder;
    public DemoData(CatalogMapper db,Environment env,PasswordEncoder encoder){this.db=db;this.env=env;this.encoder=encoder;}
    @Override @Transactional public void run(ApplicationArguments args){
        for(var s:new String[][]{{"CS01","五一广场店"},{"CS02","岳麓店"}})
            if(db.stores(null).stream().noneMatch(x->x.code().equals(s[0]))) db.addStore(s[0],s[1]);
        for(var p:new String[][]{{"TEA","茶饮"},{"CAKE","糕点"}})
            if(db.products().stream().noneMatch(x->x.code().equals(p[0]))) db.addProduct(p[0],p[1]);
        add("admin","ADMIN",null,"DEMO_ADMIN_PASSWORD");
        add("store1","STORE",db.stores(null).stream().filter(s->s.code().equals("CS01")).findFirst().orElseThrow().id(),"DEMO_STORE1_PASSWORD");
        add("store2","STORE",db.stores(null).stream().filter(s->s.code().equals("CS02")).findFirst().orElseThrow().id(),"DEMO_STORE2_PASSWORD");
    }
    private void add(String name,String role,Long store,String key){
        if(db.account(name)!=null)return;
        String password=env.getRequiredProperty(key);
        int size=password.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        if(size<12 || size>72)throw new IllegalStateException(key+" must contain 12-72 UTF-8 bytes");
        db.addUser(name,encoder.encode(password),role,store);
    }
}
