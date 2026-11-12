package com.example.knowledge;

import org.apache.ibatis.annotations.*;
import java.util.List;
import com.example.knowledge.Model.*;

@Mapper
public interface KnowledgeMapper {
    String STATE="id,filename,active_version,available,latest_version,deleted";
    String VERSION="document_id,version,source_hash,content,status,stage,failure,profile,attempt,source_filename,collection_name,index_config,cleanup_status,cleanup_failure";
    String VIEW="d.id,d.filename,d.active_version,d.available,v.version,v.status,v.stage,v.failure,v.profile,d.deleted,v.source_filename,"+
        "(SELECT COUNT(*) FROM document_versions cv WHERE cv.document_id=d.id AND cv.cleanup_status NOT IN ('NONE','DONE')) AS cleanup_pending,"+
        "(SELECT MAX(cv.cleanup_failure) FROM document_versions cv WHERE cv.document_id=d.id) AS cleanup_failure";
    @Select("SELECT * FROM users WHERE username=#{username}") Account account(String username);
    @Insert("INSERT INTO users VALUES(#{username},#{hash},#{role})") void addAccount(String username,String hash,String role);
    @Select("SELECT id,filename,active_version,available FROM documents WHERE id=#{id}") Doc doc(String id);
    @Select("SELECT "+STATE+" FROM documents WHERE id=#{id}") State state(String id);
    @Select("SELECT "+STATE+" FROM documents WHERE id=#{id} FOR UPDATE") State lock(String id);
    @Select("SELECT "+STATE+" FROM documents WHERE id=#{id} FOR SHARE") State share(String id);
    @Select("SELECT "+STATE+" FROM documents WHERE filename=#{filename}") State byFilename(String filename);
    @Insert("INSERT INTO documents(id,filename) VALUES(#{id},#{filename})") void insertDoc(String id,String filename);
    @Insert("INSERT INTO document_versions(document_id,version,source_hash,content,status,stage,profile,source_filename,collection_name,index_config) VALUES(#{id},#{version},#{hash},#{content},'PENDING','PARSED',#{profile},#{filename},#{collection},#{config})")
    void insertVersion(String id,int version,String hash,String content,String profile,String filename,String collection,String config);
    @Update("UPDATE document_versions SET collection_name=#{collection},index_config=#{config} WHERE profile=#{profile} AND collection_name IS NULL")
    void backfillProfile(String profile,String collection,String config);
    @Select("SELECT "+VERSION+" FROM document_versions WHERE document_id=#{id} AND version=#{version}") Version version(String id,int version);
    @Select("SELECT "+VERSION+" FROM document_versions WHERE document_id=#{id} ORDER BY version DESC") List<Version> versions(String id);
    @Select("SELECT "+VIEW+" FROM documents d JOIN document_versions v ON v.document_id=d.id AND v.version=CASE WHEN #{admin} THEN d.latest_version ELSE d.active_version END WHERE (#{admin} OR (d.available=TRUE AND d.deleted=FALSE AND v.status='INDEXED')) ORDER BY d.created_at DESC,d.id DESC LIMIT #{limit} OFFSET #{offset}")
    List<DocView> documents(boolean admin,int offset,int limit);
    @Select("SELECT "+VIEW+" FROM documents d JOIN document_versions v ON v.document_id=d.id AND v.version=d.latest_version WHERE d.id=#{id}") DocView documentView(String id);
    @Update("UPDATE documents SET latest_version=#{version} WHERE id=#{id}") void latest(String id,int version);
    @Update("UPDATE document_versions SET status='IMPORTING',stage='CHUNKING',failure=NULL,attempt=#{attempt},updated_at=CURRENT_TIMESTAMP(6) WHERE document_id=#{id} AND version=#{version} AND (status IN ('PENDING','FAILED') OR (status='IMPORTING' AND updated_at < TIMESTAMPADD(MINUTE,-10,CURRENT_TIMESTAMP(6))))")
    int claim(String id,int version,String attempt);
    @Update("UPDATE document_versions SET stage=#{stage},updated_at=CURRENT_TIMESTAMP(6) WHERE document_id=#{id} AND version=#{version} AND attempt=#{attempt} AND status='IMPORTING'") int stage(String id,int version,String attempt,String stage);
    @Update("UPDATE document_versions SET status=#{status},failure=#{failure},updated_at=CURRENT_TIMESTAMP(6) WHERE document_id=#{id} AND version=#{version} AND attempt=#{attempt} AND status='IMPORTING'")
    int fail(String id,int version,String attempt,String status,String failure);
    @Update("UPDATE document_versions SET status='SUPERSEDED' WHERE document_id=#{id} AND version=#{version} AND status IN ('PENDING','FAILED')") void supersede(String id,int version);
    @Insert("INSERT INTO chunks(id,document_id,version,ordinal,section,content) VALUES(#{id},#{documentId},#{version},#{ordinal},#{section},#{content}) ON DUPLICATE KEY UPDATE id=id") void insertChunk(Chunk chunk);
    @Select("SELECT * FROM chunks WHERE document_id=#{id} AND version=#{version} ORDER BY ordinal") List<Chunk> chunks(String id,int version);
    @Select("SELECT * FROM chunks WHERE id=#{id}") Chunk chunk(String id);
    @Update("UPDATE document_versions SET status='INDEXED',stage='PUBLISHED',failure=NULL,updated_at=CURRENT_TIMESTAMP(6) WHERE document_id=#{id} AND version=#{version} AND attempt=#{attempt} AND status='IMPORTING'") int indexed(String id,int version,String attempt);
    @Update("UPDATE documents SET active_version=#{version},available=TRUE WHERE id=#{id} AND deleted=FALSE AND latest_version=#{version}") int publish(String id,int version);
    @Update("UPDATE documents SET deleted=TRUE,available=FALSE WHERE id=#{id}") void deactivate(String id);
    @Update("UPDATE document_versions SET cleanup_status='PENDING',cleanup_failure=NULL,cleanup_attempt=NULL,cleanup_updated_at=NULL WHERE document_id=#{id} AND version=#{version}") void queueCleanup(String id,int version);
    @Select("SELECT "+VERSION+" FROM document_versions WHERE document_id=#{id} AND cleanup_status NOT IN ('NONE','DONE') ORDER BY version LIMIT 10") List<Version> cleanupCandidates(String id);
    @Update("UPDATE document_versions SET cleanup_status='RUNNING',cleanup_attempt=#{attempt},cleanup_updated_at=CURRENT_TIMESTAMP(6) WHERE document_id=#{id} AND version=#{version} AND (status!='IMPORTING' OR updated_at < TIMESTAMPADD(MINUTE,-10,CURRENT_TIMESTAMP(6))) AND (cleanup_status IN ('PENDING','FAILED') OR (cleanup_status='RUNNING' AND cleanup_updated_at < TIMESTAMPADD(MINUTE,-10,CURRENT_TIMESTAMP(6))))")
    int claimCleanup(String id,int version,String attempt);
    @Update("UPDATE document_versions SET cleanup_status=#{status},cleanup_failure=#{failure},cleanup_updated_at=CURRENT_TIMESTAMP(6) WHERE document_id=#{id} AND version=#{version} AND cleanup_attempt=#{attempt}")
    int finishCleanup(String id,int version,String attempt,String status,String failure);
    @Select("SELECT d.id AS document_id,d.active_version AS version FROM documents d JOIN document_versions v ON v.document_id=d.id AND v.version=d.active_version WHERE d.available=TRUE AND d.deleted=FALSE AND v.status='INDEXED' AND v.profile=#{profile}") List<ActiveVersion> activeVersions(String profile);
    @Select("SELECT COUNT(*) FROM documents WHERE available=TRUE AND deleted=FALSE") int availableCount();
    @Select("SELECT c.id,c.document_id,c.version,v.source_filename AS filename,c.section,c.content,(d.available=TRUE AND d.deleted=FALSE AND d.active_version=c.version AND v.status='INDEXED') AS valid,NULL AS quote FROM chunks c JOIN documents d ON d.id=c.document_id JOIN document_versions v ON v.document_id=c.document_id AND v.version=c.version WHERE c.id=#{id}") Evidence evidence(String id);
    @Insert("INSERT INTO chats(id,username,question,answer,status,mode,model,embedding,profile,top_k,min_score,latency_ms) VALUES(#{id},#{username},#{question},#{answer},#{status},#{mode},#{model},#{embedding},#{profile},#{topK},#{minScore},#{latencyMs})") void addChat(Chat chat);
    @Insert("INSERT INTO chat_citations(chat_id,chunk_id,ordinal,quote) VALUES(#{chatId},#{chunkId},#{ordinal},#{quote})") void addCitation(String chatId,String chunkId,int ordinal,String quote);
    @Select("SELECT * FROM chats WHERE username=#{username} ORDER BY created_at DESC,id DESC LIMIT #{limit} OFFSET #{offset}") List<Chat> history(String username,int offset,int limit);
    @Select("SELECT * FROM chats WHERE id=#{id} AND username=#{username}") Chat chat(String id,String username);
    @Select("SELECT chunk_id FROM chat_citations WHERE chat_id=#{id} ORDER BY ordinal") List<String> citationIds(String id);
    @Select("SELECT quote FROM chat_citations WHERE chat_id=#{chatId} AND chunk_id=#{chunkId}") String quote(String chatId,String chunkId);
}
