package com.example.knowledge;

import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.dao.DuplicateKeyException;
import org.slf4j.LoggerFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import com.example.knowledge.Model.*;

@Service
public class DocumentService {
    private final KnowledgeMapper db;
    private final Chunker chunker;
    private final AiGateway ai;
    private final VectorIndex index;
    private final AppProperties props;
    private final TransactionTemplate tx;
    public DocumentService(KnowledgeMapper db,Chunker chunker,AiGateway ai,VectorIndex index,AppProperties props,PlatformTransactionManager manager) {
        this.db=db;this.chunker=chunker;this.ai=ai;this.index=index;this.props=props;
        tx=new TransactionTemplate(manager);tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);tx.setTimeout(10);
    }
    public String indexConfig() {
        try {return new ObjectMapper().writeValueAsString(Map.of("mode",props.aiMode(),"embedding",props.embeddingLabel(),"dimensions",props.dimensions(),"chunker","section-v1","chunkSize",props.chunkSize(),"overlap",props.chunkOverlap()));}
        catch(Exception e){throw new IllegalStateException(e);}
    }
    public DocView upload(MultipartFile file) {
        var source=chunker.parse(file);String id=UUID.randomUUID().toString();
        var chunks=chunker.split(id,1,source.content());
        try {tx.executeWithoutResult(s->{db.insertDoc(id,source.filename());insertVersion(id,1,source,chunks);});}
        catch(DuplicateKeyException e) {
            var existing=db.byFilename(source.filename());if(existing==null)throw e;
            if(existing.deleted())throw new ApiException(409,"DOCUMENT_DELETED","同名文档已停用；不能通过再次上传恢复，请使用新的资料名称");
            var v=db.version(existing.id(),existing.latestVersion());
            if(!v.sourceHash().equals(source.hash()) || !v.profile().equals(props.profile()))
                throw new ApiException(409,"REPLACE_REQUIRED","同名文件已有不同内容或索引配置，请选择该文档的“替换版本”");
            return view(existing.id());
        }
        return retry(id,1);
    }
    private void insertVersion(String id,int number,Chunker.Source source,List<Chunk> chunks) {
        db.insertVersion(id,number,source.hash(),source.content(),props.profile(),source.filename(),props.collection(),indexConfig());
        chunks.forEach(db::insertChunk);
    }
    public DocView replace(String id,int expectedVersion,MultipartFile file) {
        var source=chunker.parse(file);
        int version=Objects.requireNonNull(tx.execute(s->{
            var d=required(db.lock(id));if(d.deleted())throw deleted();
            var latest=db.version(id,d.latestVersion());
            if(latest.sourceHash().equals(source.hash()) && latest.profile().equals(props.profile()))return 0; // idempotent replay
            if(!Objects.equals(d.activeVersion()==null?0:d.activeVersion(),expectedVersion))throw new ApiException(409,"VERSION_CONFLICT","当前版本已变化，请刷新后核对再替换");
            if(latest.status().equals("IMPORTING") || latest.status().equals("PENDING"))throw new ApiException(409,"IMPORT_BUSY","已有待完成版本，请先完成或重试该版本");
            if(latest.status().equals("FAILED")){db.supersede(id,latest.version());db.queueCleanup(id,latest.version());}
            int next=d.latestVersion()+1;var chunks=chunker.split(id,next,source.content());
            insertVersion(id,next,source,chunks);db.latest(id,next);return next;
        }));
        return version==0?view(id):retry(id,version);
    }
    public DocView retry(String id) {return retry(id,required(db.state(id)).latestVersion());}
    public DocView retry(String id,int version) {
        String attempt=UUID.randomUUID().toString();
        boolean work=Boolean.TRUE.equals(tx.execute(s->{
            var d=required(db.lock(id));if(d.deleted())throw deleted();
            var v=db.version(id,version);if(v==null)throw missing();
            if(version!=d.latestVersion())throw new ApiException(409,"VERSION_SUPERSEDED","不能重新发布已被后续版本取代的版本");
            if(!v.profile().equals(props.profile()))throw new ApiException(409,"PROFILE_CHANGED","当前配置与该导入版本不同；恢复原配置后重试，或替换为新版本重新索引");
            if(v.status().equals("INDEXED"))return false;
            if(db.claim(id,version,attempt)!=1)throw new ApiException(409,"IMPORT_BUSY","正在导入；中断超过10分钟可手动重试");
            return true;
        }));
        if(!work)return view(id);
        try {
            var chunks=db.chunks(id,version);
            stage(id,version,attempt,"EMBEDDING");var vectors=ai.embed(chunks.stream().map(c->c.section()+"\n"+c.content()).toList());
            stage(id,version,attempt,"VECTOR_UPSERT");index.upsert(chunks,vectors);
            tx.executeWithoutResult(s->{
                var d=required(db.lock(id));
                if(d.deleted() || d.latestVersion()!=version)throw new ApiException(409,"IMPORT_STALE","文档已停用或版本已被取代，本次导入不会发布");
                if(db.indexed(id,version,attempt)!=1 || db.publish(id,version)!=1)throw new ApiException(409,"IMPORT_STALE","导入尝试已失效，不会覆盖当前版本");
                for(var old:db.versions(id))if(old.version()!=version && !old.cleanupStatus().equals("DONE"))db.queueCleanup(id,old.version());
            });
        } catch(RuntimeException e) {
            tx.executeWithoutResult(s->{
                var d=required(db.lock(id));boolean retired=d.deleted() || d.latestVersion()!=version;
                db.fail(id,version,attempt,retired?"CANCELLED":"FAILED",safeReason(e));
                // A late worker can write after an earlier cleanup. Re-queue its exact version, never the active one.
                if(retired && (!d.available() || !Objects.equals(d.activeVersion(),version)))db.queueCleanup(id,version);
            });
            LoggerFactory.getLogger(getClass()).warn("import failed document={} version={} attempt={} type={}",id,version,attempt,e.getClass().getSimpleName());
        }
        cleanup(id);return view(id);
    }
    private void stage(String id,int version,String attempt,String stage) {
        tx.executeWithoutResult(s->{var d=required(db.lock(id));
            if(d.deleted() || d.latestVersion()!=version || db.stage(id,version,attempt,stage)!=1)
                throw new ApiException(409,"IMPORT_STALE","导入已失效或文档已停用");});
    }
    public DocView delete(String id,int expectedVersion) {
        tx.executeWithoutResult(s->{var d=required(db.lock(id));
            if(!d.deleted() && !Objects.equals(d.activeVersion()==null?0:d.activeVersion(),expectedVersion))throw new ApiException(409,"VERSION_CONFLICT","文档版本已变化，请刷新后重新确认删除");
            db.deactivate(id); // Commit the tombstone BEFORE any vector deletion.
            for(var v:db.versions(id))db.queueCleanup(id,v.version());
        });
        cleanup(id);return view(id);
    }
    public DocView cleanup(String id) {
        required(db.state(id));
        for(var candidate:db.cleanupCandidates(id)) {
            String attempt=UUID.randomUUID().toString();int version=candidate.version();
            boolean claimed=Boolean.TRUE.equals(tx.execute(s->{var d=required(db.lock(id));
                if(d.available() && Objects.equals(d.activeVersion(),version))return false;
                if(!d.deleted() && d.latestVersion()==version)return false;
                return db.claimCleanup(id,version,attempt)==1;
            }));
            if(!claimed)continue;
            try {
                String collection=candidate.collectionName();
                if(collection==null && candidate.profile().equals(props.profile()))collection=props.collection();
                if(collection==null)throw new ApiException(409,"CLEANUP_CONFIG_UNKNOWN","旧版本缺少集合信息，请恢复对应Embedding配置后重试清理");
                index.delete(collection,db.chunks(id,version).stream().map(Chunk::id).toList());
                db.finishCleanup(id,version,attempt,"DONE",null);
            } catch(RuntimeException e) {
                db.finishCleanup(id,version,attempt,"FAILED",safeReason(e));
                LoggerFactory.getLogger(getClass()).warn("cleanup failed document={} version={} type={}",id,version,e.getClass().getSimpleName());
            }
        }
        return view(id);
    }
    public DocView view(String id) {var v=db.documentView(id);if(v==null)throw missing();return v;}
    public List<DocView> list(boolean admin) {return db.documents(admin,0,100);}
    public Page<DocView> page(boolean admin,int page,int size){Page.validate(page,size);return Page.from(db.documents(admin,page*size,size+1),page,size);}
    public List<VersionView> versions(String id) {
        var d=required(db.state(id));return db.versions(id).stream().map(v->new VersionView(v.version(),v.sourceFilename(),v.status(),v.stage(),v.failure(),v.profile(),v.cleanupStatus(),v.cleanupFailure(),d.available()&&!d.deleted()&&Objects.equals(d.activeVersion(),v.version()))).toList();
    }
    public Evidence evidence(String id) {return db.evidence(id);}
    public List<Evidence> retrieve(String question) {
        var active=db.activeVersions(props.profile());
        if(active.isEmpty()) {
            if(db.availableCount()>0)throw new ApiException(409,"INDEX_PROFILE_CHANGED","当前模型/切块配置没有对应的已发布索引，请由管理员替换版本重新索引");
            return List.of();
        }
        var vectors=ai.embed(List.of(question));if(vectors.size()!=1)throw new ApiException(502,"VECTOR_COUNT","查询Embedding数量异常");
        var hits=index.search(vectors.getFirst(),props.topK()*4,active);
        var selected=hits.stream().filter(h->Double.isFinite(h.score())&&h.score()>=props.minScore()).map(h->evidence(h.id()))
            .filter(Objects::nonNull).filter(Evidence::valid).filter(e->db.version(e.documentId(),e.version()).profile().equals(props.profile()))
            .distinct().limit(props.topK()).toList();
        // Assemble one current snapshot under shared document locks; no network call holds DB locks.
        return tx.execute(s->{lockEvidence(selected);return selected.stream().map(e->evidence(e.id())).filter(Objects::nonNull).filter(Evidence::valid).toList();});
    }
    public void lockEvidence(List<Evidence> evidence) {evidence.stream().map(Evidence::documentId).distinct().sorted().forEach(db::share);}
    public void assertCurrent(List<Evidence> evidence) {
        for(var expected:evidence){var current=db.evidence(expected.id());
            if(current==null || !current.valid() || current.version()!=expected.version() || !db.version(current.documentId(),current.version()).profile().equals(props.profile()))
                throw new ApiException(409,"EVIDENCE_CHANGED","生成期间资料已更新或停用，本次回答已丢弃，请重新提问");}
    }
    public Map<String,Object> source(String id,int version) {
        return tx.execute(s->{var d=required(db.share(id));var v=db.version(id,version);if(v==null)throw missing();
        if(d.deleted() || !d.available() || !Objects.equals(d.activeVersion(),version) || !v.status().equals("INDEXED"))throw new ApiException(410,"SOURCE_EXPIRED","此引用版本已失效，不能作为当前规则使用");
        return Map.of("documentId",id,"filename",v.sourceFilename(),"version",version,"content",v.content());});
    }
    private State required(State state){if(state==null)throw missing();return state;}
    private ApiException missing(){return new ApiException(404,"DOCUMENT_NOT_FOUND","文档或版本不存在");}
    private ApiException deleted(){return new ApiException(410,"DOCUMENT_DELETED","文档已停用，不能导入或重新发布");}
    private String safeReason(RuntimeException e){return e instanceof ApiException?e.getMessage():"操作失败，请依据文档ID检查服务日志后手动重试";}
}
