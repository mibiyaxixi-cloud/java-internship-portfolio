package com.example.knowledge;

import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.util.*;
import com.example.knowledge.Model.Chunk;

@Component
public class Chunker {
    private final AppProperties props;
    public Chunker(AppProperties props) { this.props = props; }
    public record Source(String filename, String content, String hash) {}
    public Source parse(MultipartFile file) {
        String name = file.getOriginalFilename();
        if (name == null || !name.matches("[\\p{L}\\p{N} _().-]{1,150}\\.(?i:md|txt)"))
            throw new ApiException(400,"FILE_TYPE","仅支持文件名无路径的 .md/.txt 文件");
        if (file.isEmpty() || file.getSize() > 256 * 1024)
            throw new ApiException(400,"FILE_SIZE","文件须非空且不超过 256 KiB");
        try {
            String content = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(file.getBytes())).toString();
            if (content.startsWith("\uFEFF")) content = content.substring(1);
            content = content.replace("\r\n", "\n").replace('\r','\n');
            if (content.isBlank() || content.codePoints().anyMatch(c -> Character.isISOControl(c) && c != '\n' && c != '\t'))
                throw new ApiException(400,"FILE_CONTENT","需要非空 UTF-8 文本，不允许二进制控制字符");
            return new Source(name,content,AppProperties.sha256(content));
        } catch (java.io.IOException e) { throw new ApiException(400,"FILE_ENCODING","文件无法读取或不是有效 UTF-8"); }
    }
    public List<Chunk> split(String docId, int version, String text) {
        var chunks = new ArrayList<Chunk>();
        String section = "正文";
        var body = new StringBuilder();
        for (String line : text.split("\n", -1)) {
            if (line.matches("^#{1,6}\\s+.+")) {
                append(chunks,docId,version,section,body.toString());
                section = line.replaceFirst("^#{1,6}\\s+", "").strip();
                if (section.length() > 400) throw new ApiException(400,"SECTION_LENGTH","章节标题不能超过 400 字符");
                body.setLength(0);
            } else body.append(line).append('\n');
        }
        append(chunks,docId,version,section,body.toString());
        if (chunks.isEmpty()) throw new ApiException(400,"EMPTY_DOCUMENT","文档没有可索引的正文");
        if (chunks.size() > 100) throw new ApiException(400,"TOO_MANY_CHUNKS","M1 每份文档最多 100 个片段，请拆成更小文档");
        return List.copyOf(chunks);
    }
    private void append(List<Chunk> out, String doc, int version, String section, String body) {
        int[] points = body.strip().codePoints().toArray();
        for (int start = 0; start < points.length;) {
            int end = Math.min(points.length, start + props.chunkSize());
            String content = new String(points,start,end-start).strip();
            if (!content.isBlank()) {
                int ordinal = out.size();
                String id = UUID.nameUUIDFromBytes((doc+":"+version+":"+props.profile()+":"+ordinal).getBytes(StandardCharsets.UTF_8)).toString();
                out.add(new Chunk(id,doc,version,ordinal,section,content));
            }
            if (end == points.length) break;
            start = end - props.chunkOverlap();
        }
    }
}
