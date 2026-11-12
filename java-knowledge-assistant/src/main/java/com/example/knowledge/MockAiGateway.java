package com.example.knowledge;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import java.util.*;
import com.example.knowledge.Model.*;

/** Deterministic plumbing test only. This is neither a language model nor a semantic embedding model. */
@Component("providerGateway")
@ConditionalOnProperty(name="app.ai-mode", havingValue="mock")
public class MockAiGateway implements AiGateway {
    public List<float[]> embed(List<String> texts) { return texts.stream().map(this::vector).toList(); }
    private float[] vector(String text) {
        float[] v = new float[256];
        int[] chars = text.toLowerCase(Locale.ROOT).replaceAll("\\s+", "").codePoints().toArray();
        for (int i=0; i<chars.length-1; i++) v[Math.floorMod(chars[i]*31 + chars[i+1],256)]++;
        double norm = 0; for (float x:v) norm+=x*x;
        if (norm == 0) v[0]=1; else for(int i=0;i<v.length;i++) v[i]/=(float)Math.sqrt(norm);
        return v;
    }
    public Draft answer(String question, List<Evidence> evidence) {
        if (evidence.isEmpty()) return new Draft(true,List.of());
        // Never claim this extraction measures answer quality. It echoes the best retrieved excerpt.
        var e = evidence.getFirst();
        String quote=e.content().substring(0,Math.min(e.content().length(),400));
        return new Draft(false,List.of(new Claim("【MOCK 原文摘录，仅验证流程】\n"+e.content(),List.of(e.id()),Map.of(e.id(),quote))));
    }
}
