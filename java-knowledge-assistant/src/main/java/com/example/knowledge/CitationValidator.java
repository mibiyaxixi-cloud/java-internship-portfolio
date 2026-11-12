package com.example.knowledge;

import org.springframework.stereotype.Component;
import java.util.*;
import com.example.knowledge.Model.*;

@Component
public class CitationValidator {
    public static final String INSUFFICIENT="现有资料不足，无法依据知识库回答。请补充相关文件或说明具体问题。";
    public Checked check(Draft draft,List<Evidence> evidence) {
        if(evidence.isEmpty())return new Checked(INSUFFICIENT,"INSUFFICIENT",List.of());
        if(draft==null || draft.claims()==null)throw invalid();
        if(draft.insufficient()){if(!draft.claims().isEmpty())throw invalid();return new Checked(INSUFFICIENT,"INSUFFICIENT",List.of());}
        if(draft.claims().isEmpty()||draft.claims().size()>6)throw invalid();
        var allowed=new HashMap<String,Evidence>();evidence.stream().filter(Evidence::valid).forEach(e->allowed.put(e.id(),e));
        var used=new LinkedHashSet<String>();var quotes=new LinkedHashMap<String,String>();var text=new StringBuilder();
        for(var claim:draft.claims()) {
            if(claim==null||claim.text()==null||claim.text().isBlank()||claim.text().length()>4000||claim.citationIds()==null||claim.citationIds().isEmpty()||claim.citationIds().size()>10)throw invalid();
            for(var id:claim.citationIds())if(!allowed.containsKey(id))throw invalid();
            if(claim.quotes()==null||!claim.quotes().keySet().equals(new HashSet<>(claim.citationIds())))throw badQuote();
            for(var id:claim.citationIds()){
                String quote=claim.quotes().get(id);
                if(quote==null||quote.strip().codePointCount(0,quote.strip().length())<2||quote.length()>1000||!allowed.get(id).content().contains(quote.strip()))throw badQuote();
                // Preserve all distinct quoted spans for audit, not only the final claim's span.
                String existing=quotes.get(id),normalized=quote.strip();
                quotes.put(id,existing==null?normalized:existing.equals(normalized)?existing:existing+"\n[…]\n"+normalized);
            }
            used.addAll(claim.citationIds());if(!text.isEmpty())text.append("\n\n");text.append(claim.text());
            for(var id:new LinkedHashSet<>(claim.citationIds()))text.append(" [").append(new ArrayList<>(used).indexOf(id)+1).append("]");
        }
        return new Checked(text.toString(),"ANSWERED",List.copyOf(used),Map.copyOf(quotes));
    }
    private ApiException invalid(){return new ApiException(502,"INVALID_CITATION","模型引用不属于本次有效证据，或回答缺少引用；本次内容已拒绝，请重试");}
    private ApiException badQuote(){return new ApiException(502,"INVALID_QUOTE","模型没有提供对应片段中的原文摘句；本次内容已拒绝，请重试");}
}
