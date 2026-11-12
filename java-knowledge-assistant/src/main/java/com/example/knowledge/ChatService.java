package com.example.knowledge;

import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.dao.DataAccessException;
import org.slf4j.LoggerFactory;
import java.util.*;
import com.example.knowledge.Model.*;

@Service
public class ChatService {
    private final KnowledgeMapper db;private final DocumentService documents;private final AiGateway ai;
    private final CitationValidator validator;private final AppProperties props;private final TransactionTemplate tx;
    public ChatService(KnowledgeMapper db,DocumentService documents,AiGateway ai,CitationValidator validator,AppProperties props,PlatformTransactionManager tm) {
        this.db=db;this.documents=documents;this.ai=ai;this.validator=validator;this.props=props;
        tx=new TransactionTemplate(tm);tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);tx.setTimeout(10);
    }
    public ChatView ask(String username,String question) {
        long start=System.nanoTime();String id=UUID.randomUUID().toString();
        try {
            var evidence=documents.retrieve(question);
            var checked=evidence.isEmpty()?new Checked(CitationValidator.INSUFFICIENT,"INSUFFICIENT",List.of()):validator.check(ai.answer(question,evidence),evidence);
            tx.executeWithoutResult(s->{
                // Same document rows are locked by publish/delete. Validate ALL context, including uncited evidence.
                documents.lockEvidence(evidence);documents.assertCurrent(evidence);
                save(id,username,question,checked,start);
            });
        } catch(DataAccessException e){throw e;}
        catch(RuntimeException e) {
            String reason=e instanceof ApiException a?a.code+"："+a.getMessage():"PROCESSING_FAILED：本次问答未完成，请稍后重试";
            LoggerFactory.getLogger(getClass()).warn("chat failed chat={} type={}",id,e.getClass().getSimpleName());
            tx.executeWithoutResult(s->save(id,username,question,new Checked(reason,"FAILED",List.of()),start));
        }
        return get(id,username);
    }
    private void save(String id,String username,String question,Checked checked,long start) {
        db.addChat(new Chat(id,username,question,checked.answer(),checked.status(),props.aiMode(),props.chatLabel(),props.embeddingLabel(),props.profile(),props.topK(),props.minScore(),(System.nanoTime()-start)/1_000_000,null));
        for(int i=0;i<checked.citationIds().size();i++){String chunkId=checked.citationIds().get(i);db.addCitation(id,chunkId,i,checked.quotes().get(chunkId));}
    }
    public List<ChatView> history(String username){return db.history(username,0,50).stream().map(this::view).toList();}
    public Page<ChatView> page(String username,int page,int size){Page.validate(page,size);return Page.from(db.history(username,page*size,size+1).stream().map(this::view).toList(),page,size);}
    public ChatView get(String id,String username){var chat=db.chat(id,username);if(chat==null)throw new ApiException(404,"CHAT_NOT_FOUND","记录不存在或不属于当前用户");return view(chat);}
    private ChatView view(Chat c){return new ChatView(c,citations(c.id()));}
    private List<Evidence> citations(String id) {
        return db.citationIds(id).stream().map(chunkId->{var e=documents.evidence(chunkId);
            if(e==null)return new Evidence(chunkId,"",0,"来源不可用","","",false);
            return new Evidence(e.id(),e.documentId(),e.version(),e.filename(),e.section(),e.valid()?e.content():"引用已失效，不能作为当前规则使用",e.valid(),e.valid()?db.quote(id,chunkId):null);
        }).toList();
    }
}
