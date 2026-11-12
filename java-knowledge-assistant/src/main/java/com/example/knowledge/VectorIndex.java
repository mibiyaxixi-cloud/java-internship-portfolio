package com.example.knowledge;
import java.util.List;
import com.example.knowledge.Model.*;
public interface VectorIndex {
    void upsert(List<Chunk> chunks, List<float[]> vectors);
    List<Hit> search(float[] vector, int limit, List<ActiveVersion> active);
    void delete(String collection, List<String> pointIds);
}
