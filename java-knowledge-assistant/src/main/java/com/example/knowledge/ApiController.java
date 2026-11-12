package com.example.knowledge;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.util.*;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;
import com.example.knowledge.Model.*;

@RestController
@RequestMapping("/api")
public class ApiController {
    private final DocumentService documents;
    private final ChatService chats;
    private final AppProperties props;
    private final Semaphore questionSlots,importSlots;
    public ApiController(DocumentService documents,ChatService chats,AppProperties props,GuardProperties guard) {
        this.documents=documents;this.chats=chats;this.props=props;
        questionSlots=new Semaphore(guard.maxConcurrentQuestions());importSlots=new Semaphore(guard.maxConcurrentImports());
    }
    @GetMapping("/csrf") public Map<String,String> csrf(CsrfToken token) {return Map.of("token",token.getToken(),"headerName",token.getHeaderName());}
    @GetMapping("/me") public Map<String,Object> me(Authentication auth) {
        return Map.of("username",auth.getName(),"admin",admin(auth),"mode",props.aiMode(),"model",props.chatLabel(),"embedding",props.embeddingLabel(),"collection",props.collection(),"topK",props.topK(),"notice",props.mock()?"MOCK：仅验证流程，未进行真实模型效果测试":"真实模型模式：回答仍需对照引用核实；配置模式不代表已通过效果评测");
    }
    @GetMapping("/documents") public Page<DocView> documents(Authentication auth,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="12") int size) {return documents.page(admin(auth),page,size);}
    @PostMapping("/admin/documents") public DocView upload(@RequestParam("file") MultipartFile file) {return importing(()->documents.upload(file));}
    @PostMapping("/admin/documents/{id}/versions") public DocView replace(@PathVariable String id,@RequestParam int expectedVersion,@RequestParam("file") MultipartFile file) {
        return importing(()->documents.replace(uuid(id),version(expectedVersion,true),file));
    }
    @PostMapping("/admin/documents/{id}/retry") public DocView retry(@PathVariable String id) {return importing(()->documents.retry(uuid(id)));}
    @PostMapping("/admin/documents/{id}/versions/{version}/retry") public DocView retryVersion(@PathVariable String id,@PathVariable int version) {return importing(()->documents.retry(uuid(id),version(version,false)));}
    @DeleteMapping("/admin/documents/{id}") public DocView delete(@PathVariable String id,@RequestParam int expectedVersion) {return importing(()->documents.delete(uuid(id),version(expectedVersion,true)));}
    @PostMapping("/admin/documents/{id}/cleanup") public DocView cleanup(@PathVariable String id) {return importing(()->documents.cleanup(uuid(id)));}
    @GetMapping("/admin/documents/{id}/versions") public List<VersionView> versions(@PathVariable String id) {return documents.versions(uuid(id));}
    @GetMapping("/documents/{id}/versions/{version}") public Map<String,Object> source(@PathVariable String id,@PathVariable int version) {return documents.source(uuid(id),version(version,false));}
    @GetMapping("/chunks/{id}") public Evidence chunk(@PathVariable String id) {
        var e=documents.evidence(uuid(id));if(e==null)throw new ApiException(404,"CHUNK_NOT_FOUND","片段不存在");
        if(!e.valid())throw new ApiException(410,"SOURCE_EXPIRED","引用已失效，不能作为当前规则使用");return e;
    }
    public record Question(@NotBlank @Size(max=1000) String question) {}
    @PostMapping("/chats") public ChatView ask(Authentication auth,@Valid @RequestBody Question question) {
        return limited(questionSlots,"当前问答繁忙，请稍后手动重试",()->chats.ask(auth.getName(),question.question().strip()));
    }
    @GetMapping("/chats") public Page<ChatView> history(Authentication auth,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size) {return chats.page(auth.getName(),page,size);}
    @GetMapping("/chats/{id}") public ChatView chat(Authentication auth,@PathVariable String id) {return chats.get(uuid(id),auth.getName());}
    private DocView importing(Supplier<DocView> action) {return limited(importSlots,"当前资料操作繁忙，请刷新状态后手动重试",action);}
    private <T>T limited(Semaphore slots,String message,Supplier<T> action) {
        if(!slots.tryAcquire())throw new ApiException(429,"BUSY",message);
        try{return action.get();}finally{slots.release();}
    }
    private int version(int version,boolean zero) {if(version<(zero?0:1))throw new ApiException(400,"INVALID_VERSION","版本号无效");return version;}
    private boolean admin(Authentication auth) {return auth.getAuthorities().stream().anyMatch(a->a.getAuthority().equals("ROLE_ADMIN"));}
    private String uuid(String id) {
        try{var result=UUID.fromString(id).toString();if(!result.equals(id))throw new IllegalArgumentException();return result;}
        catch(IllegalArgumentException e){throw new ApiException(400,"INVALID_ID","ID 格式不正确");}
    }
}
