package com.example.knowledge;

import java.util.List;
import com.example.knowledge.Model.*;

public interface AiGateway {
    List<float[]> embed(List<String> texts);
    Draft answer(String question, List<Evidence> evidence);
}
