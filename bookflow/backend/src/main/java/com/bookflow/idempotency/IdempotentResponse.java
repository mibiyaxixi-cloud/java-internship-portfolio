package com.bookflow.idempotency;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
public record IdempotentResponse(int status, String json, boolean replayed) {
    public ResponseEntity<String> toHttp() {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Replayed", Boolean.toString(replayed)).body(json);
    }
}
