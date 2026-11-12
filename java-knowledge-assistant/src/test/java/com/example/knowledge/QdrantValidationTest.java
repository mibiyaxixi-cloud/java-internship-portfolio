package com.example.knowledge;
import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;
class QdrantValidationTest {
    final QdrantIndex index=new QdrantIndex(TestProps.props(),new ObjectMapper(),TestProps.guard());
    @Test void wrongDimensionsAndZeroVectorAreRejectedBeforeNetwork() {
        assertEquals("VECTOR_DIMENSIONS",assertThrows(ApiException.class,()->index.validateVector(new float[12])).code);
        assertEquals("VECTOR_INVALID",assertThrows(ApiException.class,()->index.validateVector(new float[256])).code);
    }
    @Test void nonFiniteEmbeddingIsRejectedBeforeNetwork() {
        float[] vector=new float[256];vector[0]=Float.NaN;
        assertEquals("VECTOR_INVALID",assertThrows(ApiException.class,()->index.validateVector(vector)).code);
        vector[0]=Float.POSITIVE_INFINITY;assertThrows(ApiException.class,()->index.validateVector(vector));
    }
}
