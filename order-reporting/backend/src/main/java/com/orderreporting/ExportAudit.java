package com.orderreporting;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import com.fasterxml.jackson.databind.ObjectMapper;

@Service
public class ExportAudit {
    private final ExportMapper db;
    private final ObjectMapper json;
    public ExportAudit(ExportMapper db,ObjectMapper json) { this.db=db; this.json=json; }
    @Transactional(propagation=Propagation.REQUIRES_NEW,timeout=10)
    public void generated(long actor,String kind,Object filters,long rows) {
        try { db.record(actor,kind,json.writeValueAsString(filters),rows); }
        catch(com.fasterxml.jackson.core.JsonProcessingException e) { throw new IllegalStateException(e); }
    }
}
