package com.minh.banking.phase8;

import com.minh.banking.auth.service.LoginAttemptService;
import com.minh.banking.customer.dto.request.UpdateCustomerRequest;
import com.minh.banking.customer.dto.response.CustomerResponse;
import com.minh.banking.customer.service.*;
import com.minh.banking.redis.*;
import com.minh.banking.security.CurrentUserJwtConverter;
import com.minh.banking.transaction.dto.request.TransferRequest;
import com.minh.banking.transaction.service.*;
import com.minh.banking.transaction.support.TransferIntegrationTestSupport;
import com.minh.banking.user.enums.RoleCode;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;
import static com.minh.banking.transaction.support.BankingTestFixtures.FixtureWriter.*;

/** No Redis mocks. Shutdown/restart is restricted to the script's disposable Redis instance. */
@TestPropertySource(properties={"banking.redis.login-max-failures=3","banking.redis.login-ttl-seconds=2",
    "banking.redis.login-rate-limit=4","banking.redis.transfer-rate-limit=3","banking.redis.rate-window-seconds=2",
    "banking.redis.cache-ttl-seconds=2","banking.redis.counter-ttl-seconds=3"})
class PhaseEightRedisIT extends TransferIntegrationTestSupport {
    @Autowired StringRedisTemplate redis;
    @Autowired RedisStateStore store;
    @Autowired RedisKeyFactory keys;
    @Autowired RedisProperties properties;
    @Autowired LoginAttemptService attempts;
    @Autowired CustomerCacheService cache;
    @Autowired CustomerService customers;
    @Autowired TransferService transfers;
    @Autowired TransactionCounterService counters;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired JwtDecoder decoder;
    @Autowired CurrentUserJwtConverter converter;

    @BeforeEach void isolatedRedis() throws Exception {
        guardRuntime();
        startRedis();
        var owned=redis.keys(keys.prefix()+"*");
        if (!owned.isEmpty()) redis.delete(owned);
    }
    @AfterEach void restoreRedis() throws Exception { startRedis(); }

    @Test void concurrentIncrementAlwaysHasTtlAndLosesNoUpdates() throws Exception {
        String key=keys.login("private.user@example.com");
        assertThat(key).doesNotContain("private.user","example.com");
        assertThat(keys.login(" PRIVATE.USER@example.com ")).isEqualTo(key);
        var start=new CountDownLatch(1);
        try (var executor=Executors.newFixedThreadPool(16)) {
            List<Future<Long>> tasks=new ArrayList<>();
            for (int i=0;i<64;i++) tasks.add(executor.submit(()->{ start.await(); return store.increment(key,2); }));
            start.countDown();
            for (var task:tasks) assertThat(task.get(5,TimeUnit.SECONDS)).isBetween(1L,64L);
        }
        assertThat(redis.opsForValue().get(key)).isEqualTo("64");
        assertThat(redis.getExpire(key,TimeUnit.MILLISECONDS)).isBetween(1L,2000L);
    }
    @Test void openApiDescribesBothThrottleOutcomesAndRetryHeader() throws Exception {
        var response=http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/v3/api-docs")).GET().build(),HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        var paths=json.readTree(response.body()).get("paths");
        for (String path:List.of("/api/v1/auth/login","/api/v1/transfers")) {
            var responses=paths.get(path).get("post").get("responses");
            for (String status:List.of("429","503")) assertThat(responses.get(status).get("headers").has("Retry-After")).isTrue();
        }
    }
    @Test void concurrentRateAdmissionIsAtomicAndRepairsPersistentKeys() throws Exception {
        String key=keys.rate("login","sensitive-ip");
        var start=new CountDownLatch(1);
        try (var executor=Executors.newFixedThreadPool(16)) {
            List<Future<Long>> tasks=new ArrayList<>();
            for (int i=0;i<40;i++) tasks.add(executor.submit(()->{ start.await(); return store.consume(key,2,7); }));
            start.countDown(); int admitted=0;
            for (var task:tasks) if (task.get(5,TimeUnit.SECONDS)==0) admitted++;
            assertThat(admitted).isEqualTo(7);
        }
        assertThat(redis.opsForValue().get(key)).isEqualTo("7");
        assertThat(redis.getExpire(key,TimeUnit.MILLISECONDS)).isPositive();
        redis.opsForValue().set(key,"7");
        assertThat(store.consume(key,2,7)).isBetween(1L,2000L);
        assertThat(redis.getExpire(key,TimeUnit.MILLISECONDS)).isPositive();
        redis.opsForValue().set(key,"1"); store.increment(key,2);
        assertThat(redis.getExpire(key,TimeUnit.MILLISECONDS)).isPositive();
    }
    @Test void everyKeyTypeExpiresAndBlockedRequestsDoNotExtendWindow() throws Exception {
        String login=keys.login("ttl-user"),rate=keys.rate("login","ttl-ip"),customer=keys.customer(owner());
        attempts.check("ttl-user"); store.consume(rate,2,1);
        cache.get(owner(),()->customers.get(owner()));
        Instant now=Instant.now(); counters.committed(new TransferCommitted(UUID.randomUUID(),SOURCE,now));
        String counter=counterKey(now);
        for (String key:List.of(login,rate,customer,counter)) assertThat(redis.getExpire(key,TimeUnit.MILLISECONDS)).isPositive();
        long before=redis.getExpire(rate,TimeUnit.MILLISECONDS);
        Thread.sleep(50); store.consume(rate,2,1);
        assertThat(redis.getExpire(rate,TimeUnit.MILLISECONDS)).isLessThan(before);
        await(()->List.of(login,rate,customer,counter).stream().noneMatch(redis::hasKey));
    }
    @Test void loginLocksAfterThresholdThenExpiresWithDurableHistory() throws Exception {
        String username=adminName();
        for (int i=0;i<3;i++) assertError(login(username,"incorrect"),401,"INVALID_CREDENTIALS");
        var locked=login(username,testUsers.password());
        assertError(locked,429,"RATE_LIMITED"); retryAfter(locked,2);
        assertThat(count("login_history")).isEqualTo(3);
        await(()->!redis.hasKey(keys.login(username)));
        assertThat(login(username,testUsers.password()).statusCode()).isEqualTo(200);
        assertThat(count("login_history")).isEqualTo(4);
        assertThat(redis.hasKey(keys.login(username))).isFalse();
    }
    @Test void concurrentUsernameAdmissionCannotBypassTheAttemptLimit() throws Exception {
        var start=new CountDownLatch(1);
        try (var executor=Executors.newFixedThreadPool(16)) {
            List<Future<Boolean>> tasks=new ArrayList<>();
            for (int i=0;i<32;i++) tasks.add(executor.submit(()->{
                start.await();
                try { attempts.check("parallel-login"); return true; }
                catch (ThrottleException limited) { assertThat(limited.getCode()).isEqualTo("RATE_LIMITED"); return false; }
            }));
            start.countDown(); int admitted=0;
            for (var task:tasks) if (task.get(5,TimeUnit.SECONDS)) admitted++;
            assertThat(admitted).isEqualTo(properties.loginMaxFailures());
            assertThat(redis.opsForValue().get(keys.login("parallel-login"))).isEqualTo("3");
        }
    }
    @Test void successfulLoginResetsFailuresBeforeThreshold() throws Exception {
        String username=adminName();
        assertError(login(username,"incorrect"),401,"INVALID_CREDENTIALS");
        assertError(login(username,"incorrect"),401,"INVALID_CREDENTIALS");
        assertThat(login(username,testUsers.password()).statusCode()).isEqualTo(200);
        assertThat(redis.hasKey(keys.login(username))).isFalse();
        assertError(login(username,"incorrect"),401,"INVALID_CREDENTIALS");
        assertThat(redis.opsForValue().get(keys.login(username))).isEqualTo("1");
    }
    @Test void unknownUserHasSameLockoutAndNormalizationPolicy() throws Exception {
        for (int i=0;i<3;i++) assertError(login(" Unknown.Person ","incorrect"),401,"INVALID_CREDENTIALS");
        assertError(login("unknown.person","incorrect"),429,"RATE_LIMITED");
        assertThat(redis.keys(keys.prefix()+"*").toString()).doesNotContain("unknown.person","127.0.0.1");
        assertThat(jdbc.queryForList("SELECT username_fingerprint FROM login_history").toString()).doesNotContain("unknown.person");
    }
    @Test void loginRateCountsInvalidBodiesIgnoresForwardedIpAndReturnsRetryAfter() throws Exception {
        for (int i=0;i<4;i++) assertThat(send(null,"POST","/auth/login","{}",null,"192.0.2."+i).statusCode()).isEqualTo(400);
        var blocked=send(null,"POST","/auth/login","{}",null,"198.51.100.1");
        assertError(blocked,429,"RATE_LIMITED"); retryAfter(blocked,2);
        assertThat(count("login_history")).isZero();
        await(()->redis.keys(keys.prefix()+"rate:login:*").isEmpty());
        assertThat(send(null,"POST","/auth/login","{}",null,null).statusCode()).isEqualTo(400);
    }
    @Test void transferRateIsPerUserAcrossTokensAndAuthenticationStillComesFirst() throws Exception {
        String token=testUsers.customer(SOURCE);
        for (int i=0;i<3;i++) assertThat(transfer(token,"rate-"+i,"1").statusCode()).isEqualTo(201);
        var blocked=transfer(testUsers.customer(SOURCE),"blocked","1");
        assertError(blocked,429,"RATE_LIMITED"); retryAfter(blocked,2);
        var other=send(testUsers.customer(TREASURY),"POST","/transfers",payload(TREASURY,SOURCE,"1"),"other-actor",null);
        assertThat(other.statusCode()).as(other.body()).isEqualTo(201);
        assertError(transfer(null,"anonymous","1"),401,"UNAUTHENTICATED");
        assertError(transfer(testUsers.staff(),"staff","1"),403,"FORBIDDEN");
        assertLedgerConsistent(4);
    }
    @Test void profileCacheHitsThenInvalidatesOnlyAfterCommittedUpdate() throws Exception {
        String token=testUsers.customer(SOURCE); long hits=cache.stats().hits(),misses=cache.stats().misses();
        assertThat(me(token).statusCode()).isEqualTo(200); assertThat(me(token).statusCode()).isEqualTo(200);
        assertThat(cache.stats().misses()).isEqualTo(misses+1); assertThat(cache.stats().hits()).isEqualTo(hits+1);
        assertThat(update("fresh@example.com").statusCode()).isEqualTo(200);
        assertThat(redis.hasKey(keys.customer(owner()))).isFalse();
        assertThat(me(token).body()).contains("fresh@example.com");
        assertThat(redis.opsForValue().get(keys.customer(owner()))).doesNotContain("balance","roles","password","enabled");
    }
    @Test void warmProfileCannotBypassRevokedUserOrOwnership() throws Exception {
        String token=testUsers.customer(SOURCE); assertThat(me(token).statusCode()).isEqualTo(200);
        UUID user=testUsers.ensure(RoleCode.CUSTOMER,owner());
        jdbc.update("UPDATE users SET enabled=false WHERE id=?",user);
        assertError(me(token),401,"UNAUTHENTICATED");
        var other=me(testUsers.customer(TREASURY));
        assertThat(other.statusCode()).as(other.body()).isEqualTo(200);
        assertThat(json.readTree(other.body()).get("customer").get("id").asText()).isNotEqualTo(owner().toString());
        assertError(send(testUsers.customer(TREASURY),"GET","/customers/"+owner(),null,null,null),403,"FORBIDDEN");
    }
    @Test void rollbackKeepsCachedProfileAndDoesNotIncrementCounter() throws Exception {
        var old=cache.get(owner(),()->customers.get(owner()));
        String cached=redis.opsForValue().get(keys.customer(owner()));
        String staff=testUsers.staff();
        asUser(staff,()->new TransactionTemplate(transactionManager).executeWithoutResult(status->{
            customers.update(owner(),new UpdateCustomerRequest("rolled-back@example.com",null)); status.setRollbackOnly();
        }));
        assertThat(redis.opsForValue().get(keys.customer(owner()))).isEqualTo(cached);
        assertThat(customers.get(owner())).isEqualTo(old);
        testUsers.asCustomer(SOURCE,()->new TransactionTemplate(transactionManager).executeWithoutResult(status->{
            transfers.transfer("rolled-back-transfer",new TransferRequest(SOURCE,DESTINATION,"1","VND",null)); status.setRollbackOnly();
        }));
        assertThat(redis.keys(keys.prefix()+"tx-count:*")).isEmpty();
        assertThat(count("idempotency_keys")).isZero(); assertLedgerConsistent(0);
    }
    @Test void inflightOldReadCannotRefillAfterInvalidation() throws Exception {
        UUID id=owner(); var loaded=new CountDownLatch(1); var release=new CountDownLatch(1);
        try (var executor=Executors.newSingleThreadExecutor()) {
            var read=executor.submit(()->cache.get(id,()->{
                CustomerResponse old=customers.get(id); loaded.countDown();
                try { if (!release.await(5,TimeUnit.SECONDS)) throw new IllegalStateException("Timed out waiting for update"); }
                catch (InterruptedException failure) { throw new IllegalStateException(failure); }
                return old;
            }));
            assertThat(loaded.await(5,TimeUnit.SECONDS)).isTrue();
            try { assertThat(update("raced@example.com").statusCode()).isEqualTo(200); }
            finally { release.countDown(); }
            assertThat(read.get(5,TimeUnit.SECONDS).email()).isNotEqualTo("raced@example.com");
            assertThat(redis.hasKey(keys.customer(id))).isFalse();
            assertThat(me(testUsers.customer(SOURCE)).body()).contains("raced@example.com");
        }
    }
    @Test void concurrentProfileMissesCoalesceWithinApplication() throws Exception {
        UUID id=owner(); var loads=new AtomicInteger();
        try (var executor=Executors.newFixedThreadPool(12)) {
            List<Future<CustomerResponse>> tasks=new ArrayList<>();
            for (int i=0;i<24;i++) tasks.add(executor.submit(()->cache.get(id,()->{ loads.incrementAndGet(); return customers.get(id); })));
            for (var task:tasks) assertThat(task.get(5,TimeUnit.SECONDS).id()).isEqualTo(id);
        }
        assertThat(loads).hasValue(1);
    }
    @Test void malformedCacheFallsBackWithoutMaskingDatabaseErrors() {
        UUID id=owner(); redis.opsForValue().set(keys.customer(id),"broken-json",Duration.ofSeconds(2));
        long errors=cache.stats().errors();
        assertThat(cache.get(id,()->customers.get(id)).id()).isEqualTo(id);
        assertThat(cache.stats().errors()).isEqualTo(errors+1);
        var loads=new AtomicInteger();
        assertThatThrownBy(()->cache.get(UUID.randomUUID(),()->{ loads.incrementAndGet(); throw new IllegalStateException("db failure"); }))
            .hasMessage("db failure");
        assertThat(loads).hasValue(1);
        assertThat(redis.keys(keys.prefix()+"customer:*").stream().map(k->redis.opsForValue().get(k)))
            .noneMatch(value->value.startsWith("loading:"));
    }
    @Test void stoppedRedisFallsBackForProfileButClosesLoginAndTransferWith503() throws Exception {
        String token=testUsers.customer(SOURCE); String username=adminName(); me(token);
        var snapshot=accountSnapshot(); int history=count("login_history"); long errors=cache.stats().errors();
        stopRedis();
        assertThat(update("outage@example.com").statusCode()).isEqualTo(200);
        assertThat(me(token).body()).contains("outage@example.com");
        var login=login(username,testUsers.password()); assertError(login,503,"THROTTLE_UNAVAILABLE"); retryAfter(login,1);
        var transfer=transfer(token,"outage-no-write","1"); assertError(transfer,503,"THROTTLE_UNAVAILABLE"); retryAfter(transfer,1);
        assertThat(cache.stats().errors()).isGreaterThan(errors);
        assertThat(accountSnapshot()).isEqualTo(snapshot); assertThat(count("login_history")).isEqualTo(history);
        assertThat(count("idempotency_keys")).isZero(); assertLedgerConsistent(0);
    }
    @Test void counterFailureAfterCommitCannotRollbackMoneyAndRestartPreservesIdempotencyAndQuota() throws Exception {
        String token=testUsers.customer(SOURCE); String username=adminName();
        assertThat(login(username,testUsers.password()).statusCode()).isEqualTo(200);
        UUID sourceOwner=owner();
        assertThat(send(testUsers.bearer(RoleCode.ADMIN,null),"PUT","/accounts/"+SOURCE+"/transfer-limit",
            "{\"perTransferAmount\":\"1000\",\"dailyAmount\":\"1000\"}",null,null).statusCode()).isEqualTo(200);
        testUsers.asCustomer(SOURCE,()->new TransactionTemplate(transactionManager).executeWithoutResult(status->{
            transfers.transfer("survives-redis-loss",new TransferRequest(SOURCE,DESTINATION,"1000","VND","Demo transfer"));
            try { stopRedis(); } catch (Exception failure) { throw new IllegalStateException(failure); }
        }));
        assertLedgerConsistent(1); assertThat(count("idempotency_keys")).isEqualTo(1);
        var committed=accountSnapshot();
        startRedis(); assertThat(redis.keys(keys.prefix()+"*")).isEmpty();
        var replay=transfer(token,"survives-redis-loss","1000");
        assertThat(replay.statusCode()).as(replay.body()).isEqualTo(201);
        assertThat(replay.headers().firstValue("Idempotency-Replayed")).contains("true");
        assertError(transfer(token,"still-enforces-daily","1"),409,"DAILY_LIMIT_EXCEEDED");
        assertThat(accountSnapshot()).isEqualTo(committed); assertLedgerConsistent(1);
        assertThat(count("login_history")).isEqualTo(1);
        assertThat(redis.keys(keys.prefix()+"tx-count:*")).isEmpty();
        assertThat(sourceOwner).isEqualTo(owner());
    }
    @Test void committedTransfersCountOnceButReplayAndRejectedTransfersDoNotCount() throws Exception {
        String token=testUsers.customer(SOURCE);
        assertThat(transfer(token,"counter-once","1").statusCode()).isEqualTo(201);
        var counterKeys=redis.keys(keys.prefix()+"tx-count:*"); assertThat(counterKeys).hasSize(1);
        String key=counterKeys.iterator().next(); assertThat(redis.opsForValue().get(key)).isEqualTo("1");
        assertThat(transfer(token,"counter-once","1").statusCode()).isEqualTo(201);
        assertError(transfer(token,"counter-failed","99999999"),409,"INSUFFICIENT_FUNDS");
        assertThat(redis.opsForValue().get(key)).isEqualTo("1");
        assertThat(redis.getExpire(key,TimeUnit.MILLISECONDS)).isPositive(); assertLedgerConsistent(1);
    }

    private UUID owner() { return jdbc.queryForObject("SELECT customer_id FROM accounts WHERE id=?",UUID.class,SOURCE); }
    private int count(String table) { return jdbc.queryForObject("SELECT count(*) FROM "+table,Integer.class); }
    private String adminName() { testUsers.ensure(RoleCode.ADMIN,null); return testUsers.username(RoleCode.ADMIN,null); }
    private String counterKey(Instant now) { return keys.counter(SOURCE,Math.floorDiv(now.getEpochSecond(),properties.counterWindowSeconds())); }
    private HttpResponse<String> login(String username,String password) throws Exception {
        return send(null,"POST","/auth/login",json.writeValueAsString(Map.of("username",username,"password",password)),null,null);
    }
    private HttpResponse<String> transfer(String token,String key,String amount) throws Exception {
        return send(token,"POST","/transfers",payload(SOURCE,DESTINATION,amount),key,null);
    }
    private HttpResponse<String> me(String token) throws Exception { return send(token,"GET","/me",null,null,null); }
    private HttpResponse<String> update(String email) throws Exception {
        return send(testUsers.staff(),"PATCH","/customers/"+owner(),json.writeValueAsString(Map.of("email",email)),null,null);
    }
    private HttpResponse<String> send(String token,String method,String path,String body,String key,String forwarded) throws Exception {
        var builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1"+path)).timeout(Duration.ofSeconds(12))
            .header("Content-Type","application/json").header("X-Request-Id","transfer-it");
        if (token!=null) builder.header("Authorization",token);
        if (key!=null) builder.header("Idempotency-Key",key);
        if (forwarded!=null) builder.header("X-Forwarded-For",forwarded).header("Forwarded","for="+forwarded);
        return http.send(builder.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
    }
    private void retryAfter(HttpResponse<String> response,int max) {
        assertThat(Long.parseLong(response.headers().firstValue("Retry-After").orElseThrow())).isBetween(1L,(long)max);
    }
    private void await(BooleanSupplier condition) throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(6);
        while (!condition.getAsBoolean() && System.nanoTime()<deadline) Thread.sleep(25);
        assertThat(condition.getAsBoolean()).isTrue();
    }
    private void asUser(String token,Runnable action) {
        var old=SecurityContextHolder.getContext(); var context=SecurityContextHolder.createEmptyContext();
        context.setAuthentication(converter.convert(decoder.decode(token.substring("Bearer ".length()))));
        SecurityContextHolder.setContext(context);
        try { action.run(); } finally { SecurityContextHolder.setContext(old); }
    }
    private void guardRuntime() {
        if (!keys.prefix().matches("banking:test:[a-f0-9]{32}:v1:") || !System.getenv("TEST_REDIS_DIRECTORY").matches("/tmp/banking-redis-[a-f0-9]{32}")
                || !System.getenv("TEST_REDIS_DISTRIBUTION").matches("[A-Za-z0-9._-]+")
                || !System.getenv("REDIS_PORT").matches("[0-9]{4,5}") || System.getenv("REDIS_PORT").equals("6379"))
            throw new IllegalStateException("Redis lifecycle tests require Test-Postgres.ps1's isolated runtime");
    }
    private String command(String command) throws Exception {
        var process=new ProcessBuilder("wsl.exe","-d",System.getenv("TEST_REDIS_DISTRIBUTION"),"--","sh","-lc",command).redirectErrorStream(true).start();
        if (!process.waitFor(10,TimeUnit.SECONDS)) { process.destroyForcibly(); throw new IllegalStateException("Redis lifecycle command timed out"); }
        String output=new String(process.getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8).strip();
        if (process.exitValue()!=0) throw new IllegalStateException("Redis lifecycle failed: "+output);
        return output;
    }
    private void stopRedis() throws Exception {
        guardRuntime(); String cli="redis-cli -h 127.0.0.1 -p "+System.getenv("REDIS_PORT");
        assertThat(command(cli+" --raw CONFIG GET dir")).endsWith(System.getenv("TEST_REDIS_DIRECTORY"));
        command(cli+" shutdown nosave");
    }
    private void startRedis() throws Exception {
        guardRuntime(); String dir=System.getenv("TEST_REDIS_DIRECTORY"),port=System.getenv("REDIS_PORT");
        command("if ! redis-cli -h 127.0.0.1 -p "+port+" ping >/dev/null 2>&1; then redis-server --bind 127.0.0.1 --protected-mode yes --port "+port+
            " --save '' --appendonly no --daemonize yes --dir "+dir+" --pidfile "+dir+"/redis.pid --logfile "+dir+"/redis.log; fi");
        await(()->{ try (var connection=redis.getConnectionFactory().getConnection()) { return "PONG".equals(connection.ping()); } catch (RuntimeException ignored) { return false; } });
    }
}
