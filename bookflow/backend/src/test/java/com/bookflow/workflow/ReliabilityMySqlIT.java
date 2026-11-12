package com.bookflow.workflow;

import com.bookflow.admin.AdminService;
import com.bookflow.common.BusinessException;
import com.bookflow.idempotency.IdempotencyService;
import com.bookflow.idempotency.IdempotentResponse;
import com.bookflow.reservation.ReservationService;
import com.bookflow.reservation.ReservationView;
import com.bookflow.waitlist.*;
import java.sql.Statement;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;

// 不使用测试外层回滚事务：各工作线程必须使用真实、独立、会提交的 MySQL 连接。
// 每个测试生成独立资源，finally/AfterEach 只清理这些 _test 库夹具。
@SpringBootTest(properties="bookflow.waitlist.offer-seconds=60")
@ActiveProfiles("it")
class ReliabilityMySqlIT {
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        String url=System.getenv("TEST_DB_URL");
        if(url==null || !url.matches("jdbc:mysql://[^/]+/[A-Za-z0-9_]+_test(?:[?].*)?"))
            throw new IllegalStateException("Only a dedicated _test database is allowed");
        registry.add("spring.datasource.url",()->url);
    }
    static class MutableClock extends Clock {
        private final AtomicReference<Instant> instant=new AtomicReference<>();
        void set(String local) { instant.set(LocalDateTime.parse(local).atZone(getZone()).toInstant()); }
        @Override public ZoneId getZone() { return ZoneId.of("Asia/Shanghai"); }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(instant(),zone); }
        @Override public Instant instant() { return instant.get(); }
    }
    @TestConfiguration static class TimeConfig {
        @Bean @Primary MutableClock reliabilityClock() { return new MutableClock(); }
    }
    @Autowired JdbcTemplate jdbc;
    @Autowired ReservationService reservations;
    @Autowired WaitlistService waitlist;
    @Autowired WaitlistMapper queue;
    @Autowired IdempotencyService requests;
    @Autowired AdminService admin;
    @Autowired MutableClock clock;
    @Autowired PlatformTransactionManager txManager;
    final List<Long> users=new ArrayList<>();
    long device,slot,otherSlot;

    @BeforeEach void prepare() {
        clock.set("2030-01-01T10:00:00");
        String tag=UUID.randomUUID().toString().substring(0,8);
        device=insert("INSERT INTO device(code,name,category,location) VALUES(?, '并发测试设备','测试','测试库')","R-"+tag);
        slot=insert("INSERT INTO equipment_slot(device_id,start_at,end_at) VALUES(?, '2030-01-01 12:00:00','2030-01-01 13:00:00')",device);
        otherSlot=insert("INSERT INTO equipment_slot(device_id,start_at,end_at) VALUES(?, '2030-01-01 14:00:00','2030-01-01 15:00:00')",device);
        for(int i=0;i<10;i++) users.add(insert("INSERT INTO app_user(username,password_hash,display_name,role) VALUES(?, '{service-test-only}', '并发测试用户','USER')","r-"+tag+"-"+i));
    }
    @AfterEach void cleanup() {
        for(long id:users) jdbc.update("DELETE FROM idempotent_request WHERE user_id=?",id);
        jdbc.update("DELETE FROM waitlist_entry WHERE slot_id IN (?,?)",slot,otherSlot);
        jdbc.update("DELETE FROM waitlist_task WHERE slot_id IN (?,?)",slot,otherSlot);
        jdbc.update("DELETE FROM reservation WHERE slot_id IN (?,?)",slot,otherSlot);
        jdbc.update("DELETE FROM equipment_slot WHERE id IN (?,?)",slot,otherSlot);
        jdbc.update("DELETE FROM device WHERE id=?",device);
        for(long id:users) jdbc.update("DELETE FROM app_user WHERE id=?",id);
        users.clear();
    }
    long insert(String sql,Object... values) {
        GeneratedKeyHolder key=new GeneratedKeyHolder();
        jdbc.update(connection->{var statement=connection.prepareStatement(sql,Statement.RETURN_GENERATED_KEYS);
            for(int i=0;i<values.length;i++) statement.setObject(i+1,values[i]); return statement;},key);
        return Objects.requireNonNull(key.getKey()).longValue();
    }
    String key() { return UUID.randomUUID().toString(); }
    IdempotentResponse book(long user,String key,long target) {
        return requests.execute(user,key,"RESERVE",target,201,()->reservations.reserve(user,target));
    }
    long bookFirst() { return reservations.reserve(users.get(0),slot).reservation().id(); }
    WaitlistView join(int index) { return waitlist.join(users.get(index),slot); }
    String reservationStatus(long id) { return jdbc.queryForObject("SELECT status FROM reservation WHERE id=?",String.class,id); }
    int activeCount() { return jdbc.queryForObject("SELECT COUNT(*) FROM reservation WHERE active_slot_id=?",Integer.class,slot); }
    int recordCount() { return jdbc.queryForObject("SELECT COUNT(*) FROM reservation WHERE slot_id=?",Integer.class,slot); }
    List<Object> race(List<Supplier<?>> actions) throws Exception {
        CountDownLatch ready=new CountDownLatch(actions.size()),go=new CountDownLatch(1);
        try(ExecutorService pool=Executors.newFixedThreadPool(actions.size())) {
            List<Future<Object>> futures=new ArrayList<>();
            for(Supplier<?> action:actions) futures.add(pool.submit(()->{
                ready.countDown(); if(!go.await(10,TimeUnit.SECONDS)) throw new IllegalStateException("Start barrier timeout");
                try { return action.get(); } catch(BusinessException expected) { return expected; }
            }));
            assertThat(ready.await(10,TimeUnit.SECONDS)).isTrue(); go.countDown();
            List<Object> result=new ArrayList<>();
            for(Future<Object> f:futures) result.add(f.get(20,TimeUnit.SECONDS));
            return result;
        }
    }
    TransactionTemplate transaction() {
        TransactionTemplate tx=new TransactionTemplate(txManager);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED); return tx;
    }

    @Test void tenDifferentUsersCompeteForOneSlot() throws Exception {
        List<Supplier<?>> actions=users.stream().<Supplier<?>>map(user->()->book(user,key(),slot)).toList();
        List<Object> outcomes=race(actions);
        assertThat(outcomes.stream().filter(IdempotentResponse.class::isInstance)).hasSize(1);
        assertThat(outcomes.stream().filter(BusinessException.class::isInstance)).hasSize(9);
        assertThat(activeCount()).isEqualTo(1); assertThat(recordCount()).isEqualTo(1);
    }
    @Test void concurrentIdenticalRequestsPersistAndReplayOneResult() throws Exception {
        String token=key(); long user=users.get(0);
        List<Supplier<?>> actions=new ArrayList<>(); for(int i=0;i<8;i++) actions.add(()->book(user,token,slot));
        List<Object> result=race(actions);
        assertThat(result).allMatch(IdempotentResponse.class::isInstance);
        assertThat(result.stream().map(x->((IdempotentResponse)x).json()).distinct()).hasSize(1);
        assertThat(result.stream().filter(x->!((IdempotentResponse)x).replayed())).hasSize(1);
        assertThat(recordCount()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM idempotent_request WHERE user_id=?",Integer.class,user)).isEqualTo(1);
    }
    @Test void cancelledThenNewIntentUsesNewKeyWhileOldKeyReplaysSnapshot() {
        long user=users.get(0); String token=key(); IdempotentResponse first=book(user,token,slot);
        long id=reservations.mine(user).getFirst().reservation().id(); reservations.cancel(user,id);
        IdempotentResponse replay=book(user,token,slot);
        assertThat(replay.replayed()).isTrue(); assertThat(replay.json()).isEqualTo(first.json());
        assertThat(activeCount()).isZero(); assertThat(recordCount()).isEqualTo(1);
        book(user,key(),slot); assertThat(activeCount()).isEqualTo(1); assertThat(recordCount()).isEqualTo(2);
    }
    @Test void keyCannotBeReusedForDifferentResourceOrOperationAndFailedWritesRollback() {
        long user=users.get(0); String token=key(); book(user,token,slot);
        assertThatThrownBy(()->book(user,token,otherSlot)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->requests.execute(user,token,"JOIN_WAITLIST",slot,201,()->null)).isInstanceOf(BusinessException.class);
        String failed=key(); assertThatThrownBy(()->book(users.get(1),failed,slot)).isInstanceOf(BusinessException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM idempotent_request WHERE user_id=? AND request_key=?",Integer.class,users.get(1),failed)).isZero();
        assertThatThrownBy(()->book(user,null,otherSlot)).isInstanceOf(BusinessException.class);
    }
    @Test void injectedFailureRollsBackBusinessAndIdempotencyTogether() {
        long user=users.get(0); String token=key();
        assertThatThrownBy(()->requests.execute(user,token,"RESERVE",slot,201,()->{
            reservations.reserve(user,slot); throw new IllegalStateException("simulated response persistence failure");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(recordCount()).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM idempotent_request WHERE user_id=?",Integer.class,user)).isZero();
        book(user,token,slot); assertThat(recordCount()).isEqualTo(1);
    }
    @Test void cancellationRacesWithAnotherBookingWithoutLosingHistory() throws Exception {
        long first=bookFirst();
        List<Object> results=race(List.of(()->reservations.cancel(users.get(0),first),()->reservations.reserve(users.get(1),slot)));
        assertThat(reservationStatus(first)).isEqualTo("CANCELLED");
        long succeeded=results.stream().filter(ReservationView.class::isInstance).count();
        assertThat(succeeded).isBetween(1L,2L); assertThat(activeCount()).isBetween(0,1);
        if(activeCount()==0) reservations.reserve(users.get(1),slot);
        assertThat(activeCount()).isEqualTo(1); assertThat(recordCount()).isEqualTo(2);
    }
    @Test void fifoOfferBlocksDirectBookingAndConfirmationKeepsOneOccupant() {
        long first=bookFirst(); WaitlistView b=join(1),c=join(2);
        assertThat(b.position()).isEqualTo(1); assertThat(c.position()).isEqualTo(2);
        reservations.cancel(users.get(0),first);
        WaitlistEntry offered=queue.find(b.id());
        assertThat(offered.status()).isEqualTo("OFFERED"); assertThat(queue.find(c.id()).status()).isEqualTo("WAITING");
        assertThat(offered.expiresAt()).isEqualTo(LocalDateTime.parse("2030-01-01T10:01:00"));
        assertThat(activeCount()).isEqualTo(1);
        assertThatThrownBy(()->reservations.reserve(users.get(3),slot)).isInstanceOf(BusinessException.class);
        ReservationView confirmed=waitlist.confirm(users.get(1),b.id());
        assertThat(confirmed.reservation().id()).isEqualTo(offered.reservationId());
        assertThat(confirmed.reservation().status()).isEqualTo("CONFIRMED");
        assertThat(queue.task(slot).nextRunAt()).isEqualTo(LocalDateTime.parse("2030-01-01T12:00:00"));
        assertThat(activeCount()).isEqualTo(1);
    }
    @Test void duplicateJoinAndOwnReservationCannotCreateAnotherQueueEntry() {
        bookFirst();
        assertThatThrownBy(()->join(0)).isInstanceOf(BusinessException.class);
        long user=users.get(1); String token=key();
        IdempotentResponse a=requests.execute(user,token,"JOIN_WAITLIST",slot,201,()->waitlist.join(user,slot));
        IdempotentResponse b=requests.execute(user,token,"JOIN_WAITLIST",slot,201,()->waitlist.join(user,slot));
        assertThat(b.replayed()).isTrue(); assertThat(b.json()).isEqualTo(a.json());
        assertThatThrownBy(()->join(1)).isInstanceOf(BusinessException.class); assertThat(queue.liveCount(slot)).isEqualTo(1);
    }
    @Test void confirmationVersusTwoTimeoutWorkersAtDeadlineCannotDoubleAllocate() throws Exception {
        long first=bookFirst(); WaitlistView b=join(1),c=join(2); reservations.cancel(users.get(0),first);
        long hold=queue.find(b.id()).reservationId(); clock.set("2030-01-01T10:01:00");
        List<Object> outcomes=race(List.of(()->waitlist.confirm(users.get(1),b.id()),
            ()->{waitlist.processDue(slot); return "worker1";},()->{waitlist.processDue(slot); return "worker2";}));
        assertThat(outcomes.stream().filter(BusinessException.class::isInstance)).hasSize(1);
        assertThat(reservationStatus(hold)).isEqualTo("EXPIRED");
        assertThat(queue.find(b.id()).status()).isEqualTo("EXPIRED"); assertThat(queue.find(c.id()).status()).isEqualTo("OFFERED");
        assertThat(activeCount()).isEqualTo(1); assertThat(recordCount()).isEqualTo(3);
        waitlist.processDue(slot); assertThat(recordCount()).isEqualTo(3);
    }
    @Test void successfulConfirmationSurvivesLaterTimeoutAndConcurrentConfirmRetries() throws Exception {
        long first=bookFirst(); WaitlistView b=join(1); reservations.cancel(users.get(0),first);
        String token=key(); long user=users.get(1);
        Supplier<?> confirm=()->requests.execute(user,token,"CONFIRM_OFFER",b.id(),200,()->waitlist.confirm(user,b.id()));
        List<Object> result=race(List.of(confirm,confirm,()->{waitlist.processDue(slot);return "worker";}));
        assertThat(result.stream().filter(IdempotentResponse.class::isInstance)).hasSize(2);
        clock.set("2030-01-01T10:02:00"); waitlist.processDue(slot);
        assertThat(queue.find(b.id()).status()).isEqualTo("CONFIRMED"); assertThat(activeCount()).isEqualTo(1);
        assertThat(recordCount()).isEqualTo(2); assertThat(queue.task(slot).status()).isEqualTo("DONE");
    }
    @Test void withdrawOfferHandsOffAndOwnershipIsEnforced() {
        long first=bookFirst(); WaitlistView b=join(1),c=join(2); reservations.cancel(users.get(0),first);
        assertThatThrownBy(()->waitlist.confirm(users.get(3),b.id())).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->waitlist.withdraw(users.get(3),b.id())).isInstanceOf(BusinessException.class);
        waitlist.withdraw(users.get(1),b.id()); waitlist.withdraw(users.get(1),b.id());
        assertThat(queue.find(b.id()).status()).isEqualTo("CANCELLED"); assertThat(queue.find(c.id()).status()).isEqualTo("OFFERED");
        assertThat(activeCount()).isEqualTo(1);
    }
    @Test void deadlineIsCappedBySlotStartAndRemainingQueueExpires() {
        long first=bookFirst(); WaitlistView b=join(1),c=join(2);
        clock.set("2030-01-01T11:59:45"); reservations.cancel(users.get(0),first);
        assertThat(queue.find(b.id()).expiresAt()).isEqualTo(LocalDateTime.parse("2030-01-01T12:00:00"));
        clock.set("2030-01-01T12:00:00"); waitlist.processDue(slot);
        assertThat(queue.find(b.id()).status()).isEqualTo("EXPIRED"); assertThat(queue.find(c.id()).status()).isEqualTo("EXPIRED");
        assertThat(activeCount()).isZero(); assertThat(queue.task(slot).status()).isEqualTo("DONE");
    }
    @Test void disabledUserIsSkippedAndQueueWithoutCancellationExpiresAtStart() {
        long first=bookFirst(); WaitlistView b=join(1),c=join(2);
        jdbc.update("UPDATE app_user SET enabled=FALSE WHERE id=?",users.get(1)); reservations.cancel(users.get(0),first);
        assertThat(queue.find(b.id()).reason()).isEqualTo("ACCOUNT_DISABLED"); assertThat(queue.find(c.id()).status()).isEqualTo("OFFERED");
        waitlist.confirm(users.get(2),c.id()); WaitlistView d=join(3);
        clock.set("2030-01-01T12:00:00"); waitlist.processDue(slot);
        assertThat(queue.find(d.id()).status()).isEqualTo("EXPIRED"); assertThat(activeCount()).isEqualTo(1);
    }
    @Test void failedHandoffRollsBackCancellationHoldAndTaskThenRetrySucceeds() {
        long first=bookFirst(); WaitlistView b=join(1);
        assertThatThrownBy(()->transaction().execute(status->{
            reservations.cancel(users.get(0),first); throw new IllegalStateException("simulated process failure before commit");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(reservationStatus(first)).isEqualTo("CONFIRMED"); assertThat(queue.find(b.id()).status()).isEqualTo("WAITING");
        assertThat(activeCount()).isEqualTo(1); assertThat(recordCount()).isEqualTo(1);
        assertThat(queue.task(slot).nextRunAt()).isEqualTo(LocalDateTime.parse("2030-01-01T12:00:00"));
        reservations.cancel(users.get(0),first); assertThat(queue.find(b.id()).status()).isEqualTo("OFFERED");
    }
    @Test void databaseUniqueCapacityRejectsConfirmedRowWhileOfferIsHeld() {
        long first=bookFirst(); join(1); reservations.cancel(users.get(0),first);
        assertThatThrownBy(()->jdbc.update("INSERT INTO reservation(user_id,slot_id) VALUES(?,?)",users.get(2),slot))
                .isInstanceOf(DuplicateKeyException.class);
        assertThat(activeCount()).isEqualTo(1);
    }
    @Test @WithMockUser(roles="ADMIN") void waitlistHistoryPreventsDeletingSlotEvenAfterWithdrawal() {
        bookFirst(); WaitlistView b=join(1); waitlist.withdraw(users.get(1),b.id());
        assertThatThrownBy(()->admin.deleteSlot(slot)).isInstanceOf(BusinessException.class);
        assertThat(queue.find(b.id()).status()).isEqualTo("CANCELLED");
    }
    @Test void cancellationAndNewBookingCannotBypassAnExistingWaiter() throws Exception {
        long first=bookFirst(); WaitlistView b=join(1);
        List<Object> result=race(List.of(()->reservations.cancel(users.get(0),first),()->reservations.reserve(users.get(2),slot)));
        assertThat(result.stream().filter(BusinessException.class::isInstance)).hasSize(1);
        assertThat(queue.find(b.id()).status()).isEqualTo("OFFERED");
        assertThat(activeCount()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT user_id FROM reservation WHERE active_slot_id=?",Long.class,slot)).isEqualTo(users.get(1));
    }
    @Test void timeoutHandoffFailureRollsBackAndDurableRetryCompletes() {
        long first=bookFirst(); WaitlistView b=join(1),c=join(2); reservations.cancel(users.get(0),first);
        clock.set("2030-01-01T10:01:00");
        assertThatThrownBy(()->transaction().execute(status->{waitlist.processDue(slot); throw new IllegalStateException("simulated timeout transaction crash");}))
                .isInstanceOf(IllegalStateException.class);
        assertThat(queue.find(b.id()).status()).isEqualTo("OFFERED"); assertThat(queue.find(c.id()).status()).isEqualTo("WAITING");
        assertThat(recordCount()).isEqualTo(2); assertThat(queue.task(slot).nextRunAt()).isEqualTo(LocalDateTime.now(clock));
        queue.failed(slot,LocalDateTime.now(clock),LocalDateTime.now(clock).plusSeconds(10),"SimulatedFailure");
        assertThat(queue.task(slot).failureCount()).isEqualTo(1);
        waitlist.processDue(slot); assertThat(queue.find(b.id()).status()).isEqualTo("OFFERED");
        clock.set("2030-01-01T10:01:10"); waitlist.processDue(slot);
        assertThat(queue.find(b.id()).status()).isEqualTo("EXPIRED"); assertThat(queue.find(c.id()).status()).isEqualTo("OFFERED");
        assertThat(queue.task(slot).failureCount()).isZero(); assertThat(activeCount()).isEqualTo(1);
    }
    @Test void concurrentNewJoinRequestsForOneUserKeepOnlyOneLiveEntry() throws Exception {
        bookFirst(); List<Object> result=race(List.of(()->join(1),()->join(1),()->join(1)));
        assertThat(result.stream().filter(WaitlistView.class::isInstance)).hasSize(1);
        assertThat(result.stream().filter(BusinessException.class::isInstance)).hasSize(2);
        assertThat(queue.liveCount(slot)).isEqualTo(1);
    }

}
