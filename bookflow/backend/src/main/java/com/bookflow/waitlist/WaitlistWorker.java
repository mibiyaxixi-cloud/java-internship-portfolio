package com.bookflow.waitlist;
import java.time.Clock;
import java.time.LocalDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
@Component
@ConditionalOnProperty(name="bookflow.scheduling.enabled", havingValue="true", matchIfMissing=true)
public class WaitlistWorker {
    private static final Logger log=LoggerFactory.getLogger(WaitlistWorker.class);
    private final WaitlistMapper queue;
    private final WaitlistService service;
    private final Clock clock;
    public WaitlistWorker(WaitlistMapper queue, WaitlistService service, Clock clock) { this.queue=queue; this.service=service; this.clock=clock; }
    // 扫描只是唤醒器；任务事实在 MySQL。停机错过的期限会在恢复后首次扫描被发现。
    @Scheduled(fixedDelayString="${bookflow.waitlist.scan-delay-ms:5000}")
    public void scan() {
        try {
            for(long slotId:queue.due(LocalDateTime.now(clock))) {
                try { service.processDue(slotId); }
                catch(RuntimeException failure) {
                    LocalDateTime now=LocalDateTime.now(clock);
                    log.warn("Waitlist task {} failed: {}",slotId,failure.getClass().getSimpleName());
                    // 失败业务事务已回滚；仅登记失败和重试时间，不假装任务完成。
                    queue.failed(slotId,now,now.plusSeconds(10),failure.getClass().getSimpleName());
                }
            }
        } catch(RuntimeException failure) {
            log.warn("Waitlist scan unavailable: {}",failure.getClass().getSimpleName());
        }
    }
}
