package com.bookflow.idempotency;
import com.bookflow.common.BusinessException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
@Service
public class IdempotencyService {
    private final IdempotencyMapper mapper;
    private final ObjectMapper json;
    public IdempotencyService(IdempotencyMapper mapper, ObjectMapper json) { this.mapper = mapper; this.json = json; }

    // 最外层事务：请求行→设备→时段→预约/候补。工作失败时连请求登记一起回滚。
    // 同一键只绑定一种操作和一个资源；成功响应原样保存，重试不重新执行业务。
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public IdempotentResponse execute(long userId, String key, String operation, long resourceId,
                                      int status, Supplier<?> work) {
        if (key == null || !key.matches("[A-Za-z0-9_-]{16,80}"))
            throw BusinessException.invalid("请提供 16～80 位字母、数字、横线或下划线的 Idempotency-Key");
        mapper.claim(userId, key, operation, resourceId);
        StoredRequest stored = mapper.lock(userId, key);
        if (!stored.operation().equals(operation) || stored.resourceId() != resourceId)
            throw BusinessException.conflict("IDEMPOTENCY_KEY_REUSED", "此请求编号已用于其他操作，请发起新的请求");
        if (stored.responseStatus() != null)
            return new IdempotentResponse(stored.responseStatus(), stored.responseJson(), true);
        Object result = work.get();
        try {
            String payload = json.writeValueAsString(result);
            mapper.complete(userId, key, status, payload);
            return new IdempotentResponse(status, payload, false);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not store idempotent response", exception);
        }
    }
}
