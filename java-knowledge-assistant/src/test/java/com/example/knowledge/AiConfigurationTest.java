package com.example.knowledge;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class AiConfigurationTest {
    @Test void realModeFailsClosedWhenCredentialsAreMissing() {
        assertThrows(IllegalArgumentException.class,()->new AppProperties("real","","","","","","",1536,"","",30,600,80,4,.2,"","",""));
    }
    @Test void mockProfileAndVectorAreDeterministicAndExplicit() {
        var props=TestProps.props(); assertTrue(props.collection().startsWith("knowledge_mock_"));
        assertEquals("mock-extract-v2",props.chatLabel());
        var mock=new MockAiGateway();assertArrayEquals(mock.embed(java.util.List.of("报销")).getFirst(),mock.embed(java.util.List.of("报销")).getFirst());
    }
}
