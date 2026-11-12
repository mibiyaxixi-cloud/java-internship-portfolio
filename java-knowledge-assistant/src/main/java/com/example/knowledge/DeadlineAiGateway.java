package com.example.knowledge;

import org.springframework.stereotype.Component;
import org.springframework.context.annotation.Primary;
import org.springframework.beans.factory.annotation.Qualifier;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.*;
import java.util.List;
import com.example.knowledge.Model.*;

/** Bounded workers prevent a provider ignoring interruption from exhausting request threads. */
@Component
@Primary
public class DeadlineAiGateway implements AiGateway {
    private final AiGateway delegate;private final int seconds;private final ThreadPoolExecutor workers;
    public DeadlineAiGateway(@Qualifier("providerGateway") AiGateway delegate,GuardProperties props){
        this.delegate=delegate;seconds=props.aiDeadlineSeconds();
        workers=new ThreadPoolExecutor(4,4,0,TimeUnit.SECONDS,new SynchronousQueue<>(),r->{var t=new Thread(r,"ai-call");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
    }
    public List<float[]> embed(List<String> texts){return call(()->delegate.embed(texts));}
    public Draft answer(String question,List<Evidence> evidence){return call(()->delegate.answer(question,evidence));}
    private <T>T call(Callable<T> job){
        Future<T> future;
        try{future=workers.submit(job);}catch(RejectedExecutionException e){throw new ApiException(429,"AI_BUSY","模型调用槽已满，请稍后手动重试");}
        try{return future.get(seconds,TimeUnit.SECONDS);}
        catch(TimeoutException e){future.cancel(true);throw new ApiException(504,"AI_TIMEOUT","模型调用超时，本次结果已丢弃；请稍后重试");}
        catch(InterruptedException e){future.cancel(true);Thread.currentThread().interrupt();throw new ApiException(503,"REQUEST_INTERRUPTED","请求已中断，请重新操作");}
        catch(ExecutionException e){if(e.getCause() instanceof RuntimeException cause)throw cause;throw new ApiException(503,"AI_UNAVAILABLE","模型调用失败，请稍后重试");}
    }
    @PreDestroy public void close(){workers.shutdownNow();}
}
