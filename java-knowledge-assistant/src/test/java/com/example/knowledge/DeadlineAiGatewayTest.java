package com.example.knowledge;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class DeadlineAiGatewayTest {
    @Test void providerDeadlineProducesExplicitFailureAndInterruptsWorker() throws Exception {
        var delegate=mock(AiGateway.class);var interrupted=new CountDownLatch(1);
        when(delegate.embed(anyList())).thenAnswer(inv->{try{new CountDownLatch(1).await();return List.of();}catch(InterruptedException e){interrupted.countDown();throw new RuntimeException(e);}});
        var gateway=new DeadlineAiGateway(delegate,new GuardProperties(10,20,30,4,2,10,1));
        try{assertEquals("AI_TIMEOUT",assertThrows(ApiException.class,()->gateway.embed(List.of("test"))).code);assertTrue(interrupted.await(2,TimeUnit.SECONDS));}finally{gateway.close();}
    }
    @Test void exhaustedWorkersRejectInsteadOfGrowingUnbounded() throws Exception {
        var delegate=mock(AiGateway.class);var entered=new CountDownLatch(4);var release=new CountDownLatch(1);
        when(delegate.embed(anyList())).thenAnswer(inv->{entered.countDown();release.await(5,TimeUnit.SECONDS);return List.of(new float[]{1});});
        var gateway=new DeadlineAiGateway(delegate,new GuardProperties(10,20,30,4,2,10,10));
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()){
            var tasks=new ArrayList<Future<?>>();for(int i=0;i<4;i++)tasks.add(pool.submit(()->gateway.embed(List.of("test"))));
            try{assertTrue(entered.await(3,TimeUnit.SECONDS));assertEquals("AI_BUSY",assertThrows(ApiException.class,()->gateway.embed(List.of("test"))).code);}
            finally{release.countDown();}
            for(var task:tasks)task.get(3,TimeUnit.SECONDS);
        }finally{release.countDown();gateway.close();}
    }
}
