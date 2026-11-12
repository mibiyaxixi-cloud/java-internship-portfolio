package com.example.knowledge;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

class ChunkerTest {
    final Chunker chunker=new Chunker(TestProps.props());
    @Test void deterministicChunksPreserveSectionsAndUnicode() {
        var source="# 模拟文档\n免责声明\n## 章节甲\n"+"中文😀".repeat(400)+"\n## 章节乙\n小节内容";
        var first=chunker.split("doc",1,source); var second=chunker.split("doc",1,source);
        assertEquals(first,second); assertTrue(first.size()>3);
        assertEquals(first.size(),first.stream().map(c->c.id()).distinct().count());
        for(var c:first) { assertTrue(c.content().codePointCount(0,c.content().length())<=600); assertFalse(c.content().contains("�")); }
        assertEquals("章节乙",first.getLast().section());
        assertNotEquals(first.getFirst().id(),chunker.split("doc",2,source).getFirst().id());
    }
    @Test void extensionEncodingBinaryAndSizeAreChecked() {
        assertThrows(ApiException.class,()->chunker.parse(file("a.pdf","hello".getBytes())));
        assertThrows(ApiException.class,()->chunker.parse(file("../a.md","hello".getBytes())));
        assertThrows(ApiException.class,()->chunker.parse(file("a.md",new byte[]{(byte)0xc3,0x28})));
        assertThrows(ApiException.class,()->chunker.parse(file("a.txt",new byte[]{0,1,2})));
        assertThrows(ApiException.class,()->chunker.parse(file("a.txt",new byte[256*1024+1])));
        assertThrows(ApiException.class,()->chunker.split("doc",1,"# 只有标题"));
    }
    @Test void bomAndLineEndingsAreNormalized() {
        var parsed=chunker.parse(file("模拟.TXT","\uFEFF正文\r\n下一行".getBytes(StandardCharsets.UTF_8)));
        assertEquals("正文\n下一行",parsed.content());
    }
    private MockMultipartFile file(String name,byte[] bytes) { return new MockMultipartFile("file",name,"application/octet-stream",bytes); }
}
