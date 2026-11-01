package com.minh.banking.phase10;

import com.minh.banking.fraud.*;
import com.minh.banking.kafka.*;
import com.minh.banking.notification.*;
import com.minh.banking.transaction.dto.request.TransferRequest;
import com.minh.banking.transaction.service.*;
import com.minh.banking.transaction.support.TransferIntegrationTestSupport;
import com.minh.banking.user.enums.RoleCode;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;
import org.apache.kafka.clients.admin.*;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.*;
import org.springframework.test.util.AopTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static com.minh.banking.transaction.support.BankingTestFixtures.FixtureWriter.*;

@TestPropertySource(properties={"banking.kafka.enabled=true","banking.kafka.retry-backoff-ms=50","banking.fraud.recovery-enabled=false"})
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class KafkaWorkflowIT extends TransferIntegrationTestSupport {
    @Autowired KafkaListenerEndpointRegistry registry;
    @Autowired KafkaTemplate<String,String> kafka;
    @Autowired KafkaProperties properties;
    @Autowired EventCodec codec;
    @Autowired TransactionFraudQueryService facts;
    @Autowired TransferService transfers;
    @Autowired FraudRuleService rules;
    @Autowired FraudRecoveryJob recovery;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired KafkaDomainEventPublisher publisher;
    @MockitoSpyBean FraudEventConsumer fraudConsumer;
    @MockitoSpyBean FraudEventListener fraudListener;
    @MockitoSpyBean NotificationService notifications;
    @MockitoBean Clock clock;
    private final AtomicReference<Instant> now=new AtomicReference<>(Instant.parse("2026-10-28T05:00:00Z"));
    private UUID officer;
    private final Map<TopicPartition,Long> startingOffsets=new HashMap<>();

    @Override @BeforeEach protected void resetFixture() {
        guard(); registry.stop();
        when(clock.instant()).thenAnswer(call->now.get()); now.set(Instant.parse("2026-10-28T05:00:00Z"));
        super.resetFixture(); jdbc.execute("TRUNCATE processed_events"); officer=testUsers.ensure(RoleCode.RISK_OFFICER,null);
        startingOffsets.clear();
        try (var admin=AdminClient.create(Map.of("bootstrap.servers",System.getenv("KAFKA_BOOTSTRAP_SERVERS")))) {
            var query=new HashMap<TopicPartition,OffsetSpec>();
            for (var type:EventType.values()) for (String suffix:List.of("",".notification.dlt",type==EventType.TRANSFER_COMPLETED?".fraud.dlt":".notification.dlt"))
                for (int i=0;i<properties.partitions();i++) query.put(new TopicPartition(topic(type)+suffix,i),OffsetSpec.latest());
            admin.listOffsets(query).all().get(5,TimeUnit.SECONDS).forEach((key,value)->startingOffsets.put(key,value.offset()));
        } catch (Exception failure) { throw new IllegalStateException(failure); }
        registry.start(); await(()->registry.getListenerContainers().stream().allMatch(c->c.getAssignedPartitions()!=null && !c.getAssignedPartitions().isEmpty()));
    }
    @AfterEach void drain() throws Exception {
        broker("start"); registry.start(); kafka.flush();
        await(this::caughtUp); registry.stop();
    }
    @Test void transferConsumerFraudAlertAndNotificationUseRealBroker() throws Exception {
        highRule(); UUID transaction=id(transfer("kafka-flow","1000"));
        await(()->count("fraud_alerts")==1 && count("notifications")==2);
        assertThat(count("processed_events")).isEqualTo(3); // completed x2 groups + detected notification.
        assertThat(count("transfer_attempts")).isEqualTo(1);
        var received=readTopic(topic(EventType.TRANSFER_COMPLETED),1);
        assertThat(received).hasSize(1); assertThat(received.getFirst().key()).isEqualTo(SOURCE.toString());
        assertThat(received.getFirst().value()).doesNotContain("password","balance","amount","username");
        assertThat(json.readTree(readTopic(topic(EventType.FRAUD_DETECTED),1).getFirst().value()).get("correlationId").asText()).isEqualTo("transfer-it");
        assertThat(jdbc.queryForObject("SELECT transaction_id FROM fraud_alerts",UUID.class)).isEqualTo(transaction);
        assertLedgerConsistent(1);
    }
    @Test void duplicateEventAndDifferentEventIdsForSameTransferHaveOneBusinessEffect() throws Exception {
        highRule(); UUID transaction=id(transfer("duplicate","1000")); await(()->count("notifications")==2);
        var original=completed(transaction);
        for (int i=0;i<4;i++) sendEvent(original);
        var alternate=new EventEnvelope(UUID.randomUUID(),original.eventType(),1,original.occurredAt(),original.correlationId(),original.aggregateId(),original.payload());
        sendEvent(alternate); await(()->receipt(FraudEventConsumer.NAME,alternate.eventId())==1 && receipt(NotificationEventConsumer.NAME,alternate.eventId())==1);
        assertThat(count("fraud_analysis_runs")).isEqualTo(1); assertThat(count("fraud_alerts")).isEqualTo(1);
        assertThat(count("notifications")).isEqualTo(2); assertLedgerConsistent(1);
    }
    @Test void rollbackNeverPublishesCompletedAndNeverWritesSuccessAttempt() throws Exception {
        testUsers.asCustomer(SOURCE,()->new TransactionTemplate(transactionManager).executeWithoutResult(status->{
            transfers.transfer("rollback-event",new TransferRequest(SOURCE,DESTINATION,"1","VND",null)); status.setRollbackOnly();
        }));
        assertThat(count("transfer_attempts")).isZero(); assertThat(count("processed_events")).isZero();
        assertThat(readTopic(topic(EventType.TRANSFER_COMPLETED),0)).isEmpty(); assertLedgerConsistent(0);
    }
    @Test void completedEventCannotBeObservedBeforeCommit() throws Exception {
        var entered=new CountDownLatch(1); var commit=new CountDownLatch(1);
        try (var pool=Executors.newSingleThreadExecutor()) {
            var work=pool.submit(()->testUsers.asCustomer(SOURCE,()->new TransactionTemplate(transactionManager).executeWithoutResult(status->{
                transfers.transfer("hold-commit",new TransferRequest(SOURCE,DESTINATION,"1","VND",null)); entered.countDown();
                try { if (!commit.await(8,TimeUnit.SECONDS)) throw new IllegalStateException("Commit barrier timed out"); }
                catch (InterruptedException failure) { throw new IllegalStateException(failure); }
            })));
            assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();
            try { assertThat(readTopic(topic(EventType.TRANSFER_COMPLETED),0)).isEmpty(); assertThat(count("processed_events")).isZero(); }
            finally { commit.countDown(); }
            work.get(10,TimeUnit.SECONDS); await(()->count("fraud_analysis_runs")==1);
        }
        assertLedgerConsistent(1);
    }
    @Test void failedAttemptHasNoLedgerAndFailedEventNeverRunsFraud() throws Exception {
        assertError(transfer("insufficient","99999999"),409,"INSUFFICIENT_FUNDS");
        await(()->count("notifications")==1);
        assertThat(count("fraud_analysis_runs")).isZero(); assertThat(count("fraud_alerts")).isZero();
        var attempt=jdbc.queryForMap("SELECT * FROM transfer_attempts");
        assertThat(attempt.get("transaction_id")).isNull(); assertThat(attempt.get("outcome")).isEqualTo("BUSINESS_FAILURE");
        assertThat(attempt.get("reason_code")).isEqualTo("INSUFFICIENT_FUNDS");
        assertThat(readTopic(topic(EventType.TRANSFER_FAILED),1)).hasSize(1); assertLedgerConsistent(0);
    }
    @Test void authenticationJsonAndOwnershipFailuresDoNotPublishBusinessFailureEvents() throws Exception {
        assertError(send(null,"POST","/transfers",payload(SOURCE,DESTINATION,"1"),"no-auth"),401,"UNAUTHENTICATED");
        assertThat(send(testUsers.customer(SOURCE),"POST","/transfers","{}","bad-json").statusCode()).isEqualTo(400);
        assertError(send(testUsers.customer(TREASURY),"POST","/transfers",payload(SOURCE,DESTINATION,"1"),"foreign"),404,"ACCOUNT_NOT_FOUND");
        assertThat(count("transfer_attempts")).isZero(); assertThat(readTopic(topic(EventType.TRANSFER_FAILED),0)).isEmpty();
    }
    @Test void consumerFailureBeforeDatabaseCommitRollsBackReceiptAndAnalysisThenRetries() throws Exception {
        highRule(); var once=new AtomicBoolean();
        var target=AopTestUtils.<FraudEventConsumer>getUltimateTargetObject(fraudConsumer);
        doAnswer(call->{ call.callRealMethod(); if (once.compareAndSet(false,true)) throw new IllegalStateException("Before DB commit"); return null; }).when(target).process(any(EventEnvelope.class));
        id(transfer("db-rollback-retry","1000")); await(()->count("notifications")==2);
        assertThat(once).isTrue(); assertThat(count("fraud_analysis_runs")).isEqualTo(1); assertThat(count("fraud_alerts")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM processed_events WHERE consumer_name=?",Integer.class,FraudEventConsumer.NAME)).isEqualTo(1);
    }
    @Test void failureAfterDatabaseCommitBeforeOffsetCommitRedeliversSafely() throws Exception {
        highRule(); var once=new AtomicBoolean();
        doAnswer(call->{ call.callRealMethod(); if (once.compareAndSet(false,true)) throw new IllegalStateException("After DB commit before Kafka offset"); return null; })
            .when(fraudListener).receive(any(ConsumerRecord.class));
        id(transfer("ack-gap","1000")); await(()->count("notifications")==2 && caughtUp());
        assertThat(once).isTrue(); assertThat(count("fraud_alerts")).isEqualTo(1); assertThat(count("processed_events")).isEqualTo(3);
    }
    @Test void notificationFailureRollsBackReceiptAndNotificationTogether() throws Exception {
        var once=new AtomicBoolean(); var target=AopTestUtils.<NotificationService>getUltimateTargetObject(notifications);
        doAnswer(call->{ call.callRealMethod(); if (once.compareAndSet(false,true)) throw new IllegalStateException("Notification DB failure"); return null; })
            .when(target).create(any(UUID.class),any(UUID.class),anyString(),anyMap());
        id(transfer("notification-retry","1")); await(()->count("notifications")==1 && caughtUp());
        assertThat(once).isTrue(); assertThat(jdbc.queryForObject("SELECT count(*) FROM processed_events WHERE consumer_name=?",Integer.class,NotificationEventConsumer.NAME)).isEqualTo(1);
    }
    @Test void stoppedConsumersResumeFromBrokerAndStoredOffsetsWithoutDuplicates() throws Exception {
        registry.stop(); UUID transaction=id(transfer("queued-while-stopped","1"));
        assertThat(count("processed_events")).isZero(); registry.start(); await(()->count("notifications")==1 && caughtUp());
        registry.stop(); registry.start(); sendEvent(completed(transaction)); await(this::caughtUp);
        assertThat(count("notifications")).isEqualTo(1); assertThat(count("fraud_analysis_runs")).isEqualTo(1);
    }
    @Test void malformedAndUnsupportedSchemaReachDltAfterBoundedRetriesAndDoNotBlockNextRecord() throws Exception {
        String completed=topic(EventType.TRANSFER_COMPLETED);
        kafka.send(completed,SOURCE.toString(),"{invalid-json").get(5,TimeUnit.SECONDS);
        var unsupported=codec.create(EventType.TRANSFER_COMPLETED,UUID.randomUUID(),now.get(),"bad-schema",new TransferCompletedEvent(UUID.randomUUID(),SOURCE));
        var version=new EventEnvelope(unsupported.eventId(),unsupported.eventType(),2,unsupported.occurredAt(),unsupported.correlationId(),unsupported.aggregateId(),unsupported.payload());
        kafka.send(completed,SOURCE.toString(),codec.encode(version)).get(5,TimeUnit.SECONDS);
        await(this::caughtUp);
        var dead=readTopic(completed+".fraud.dlt",2); assertThat(dead).hasSize(2);
        assertThat(dead).allSatisfy(record->{ assertThat(record.headers().lastHeader("banking-original-topic")).isNotNull(); assertThat(record.headers().lastHeader("banking-consumer")).isNotNull(); });
        assertThat(readTopic(completed+".notification.dlt",2)).hasSize(2);
        assertThat(count("processed_events")).isZero(); id(transfer("after-poison","1")); await(()->count("notifications")==1);
    }
    @Test void reusedEventIdWithDifferentEnvelopeIsRejectedWithoutChangingExistingEffect() throws Exception {
        UUID transaction=id(transfer("fingerprint","1")); await(()->count("notifications")==1 && caughtUp());
        var original=completed(transaction); var tampered=new EventEnvelope(original.eventId(),original.eventType(),1,original.occurredAt(),"different-correlation",original.aggregateId(),original.payload());
        sendEvent(tampered); await(this::caughtUp);
        assertThat(readTopic(topic(EventType.TRANSFER_COMPLETED)+".fraud.dlt",1)).hasSize(1);
        assertThat(count("notifications")).isEqualTo(1); assertThat(count("fraud_analysis_runs")).isEqualTo(1);
    }
    @Test void operatorDltReplayPreservesEventIdentityAndCompletesExactlyOneBusinessEffect() throws Exception {
        highRule(); var target=AopTestUtils.<FraudEventConsumer>getUltimateTargetObject(fraudConsumer);
        doThrow(new IllegalStateException("Consumer paused for DLT playbook test")).when(target).process(any(EventEnvelope.class));
        UUID transaction=id(transfer("operator-replay","1000")); await(this::caughtUp);
        var dead=readTopic(topic(EventType.TRANSFER_COMPLETED)+".fraud.dlt",1).getFirst();
        assertThat(count("fraud_analysis_runs")).isZero(); doCallRealMethod().when(target).process(any(EventEnvelope.class));
        String replay=System.getenv("TEST_KAFKA_SCRIPT").replace("KafkaRuntime.sh","Replay-KafkaDlt.sh");
        var command=new ProcessBuilder("wsl.exe","-d",System.getenv("TEST_KAFKA_DISTRIBUTION"),"--","bash",replay,
            System.getenv("TEST_KAFKA_PORT"),topic(EventType.TRANSFER_COMPLETED),"fraud",Integer.toString(dead.partition()),Long.toString(dead.offset())).redirectErrorStream(true).start();
        if (!command.waitFor(20,TimeUnit.SECONDS)) { command.destroyForcibly(); throw new IllegalStateException("DLT replay timed out"); }
        assertThat(command.exitValue()).as(new String(command.getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8)).isZero();
        await(()->count("notifications")==2 && caughtUp());
        assertThat(receipt(FraudEventConsumer.NAME,completed(transaction).eventId())).isEqualTo(1);
        assertThat(count("fraud_alerts")).isEqualTo(1); assertLedgerConsistent(1);
    }
    @Test void delayedCompletedEventUsesDatabaseEventTimeNotDeliveryTime() throws Exception {
        registry.stop();
        for (int i=0;i<5;i++) { now.set(Instant.parse("2026-10-28T04:00:00Z").plusSeconds(i)); transfer("old-"+i,"1"); }
        UUID target=jdbc.queryForObject("SELECT id FROM transactions WHERE type='INTERNAL_TRANSFER' ORDER BY created_at DESC,id DESC LIMIT 1",UUID.class);
        now.set(Instant.parse("2026-10-28T06:00:00Z")); transfer("future-fact","1");
        registry.start(); await(()->count("fraud_analysis_runs")==6);
        var evidence=jdbc.queryForObject("SELECT m.observed_value::text FROM fraud_alert_rule_matches m JOIN fraud_alerts a ON a.id=m.alert_id WHERE a.transaction_id=? AND m.rule_code='VELOCITY'",String.class,target);
        assertThat(json.readTree(evidence).get("count").asInt()).isEqualTo(5); assertLedgerConsistent(6);
    }
    @Test void brokerOutageKeepsDurableEventsAndDeliversAfterRecovery() throws Exception {
        registry.stop(); long failures=publisher.stats().failed(); broker("stop");
        var response=transfer("broker-offline","1000"); assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        await(()->publisher.stats().failed()>failures); assertThat(count("fraud_analysis_runs")).isZero(); assertLedgerConsistent(1);
        assertError(transfer("broker-offline-failed","99999999"),409,"INSUFFICIENT_FUNDS");
        assertThat(recovery.recover()).isEqualTo(1); assertLedgerConsistent(1);
        assertThat(count("notifications")).isZero(); broker("start"); registry.start();
        now.set(now.get().plusSeconds(10));
        await(()->count("notifications")==2);
        await(()->jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE status<>'PUBLISHED'",Integer.class)==0);
        var replay=transfer("broker-offline","1000"); assertThat(replay.headers().firstValue("Idempotency-Replayed")).contains("true");
        assertThat(count("fraud_analysis_runs")).isEqualTo(1); assertLedgerConsistent(1);
    }
    @Test void notificationsArePrivatePaginatedAndReadIsIdempotent() throws Exception {
        id(transfer("private-inbox","1")); await(()->count("notifications")==1);
        String token=testUsers.customer(SOURCE);
        var list=send(token,"GET","/me/notifications?size=1&unreadOnly=true",null,null);
        assertThat(list.statusCode()).isEqualTo(200); var body=json.readTree(list.body()); UUID notification=UUID.fromString(body.get("content").get(0).get("id").asText());
        assertThat(body.get("totalElements").asInt()).isEqualTo(1);
        assertError(send(testUsers.customer(TREASURY),"PATCH","/me/notifications/"+notification+"/read",null,null),404,"NOTIFICATION_NOT_FOUND");
        assertThat(send(token,"PATCH","/me/notifications/"+notification+"/read",null,null).statusCode()).isEqualTo(204);
        var readAt=jdbc.queryForObject("SELECT read_at FROM notifications WHERE id=?",java.sql.Timestamp.class,notification);
        now.set(now.get().plusSeconds(10)); assertThat(send(token,"PATCH","/me/notifications/"+notification+"/read",null,null).statusCode()).isEqualTo(204);
        assertThat(jdbc.queryForObject("SELECT read_at FROM notifications WHERE id=?",java.sql.Timestamp.class,notification)).isEqualTo(readAt);
        assertThat(json.readTree(send(token,"GET","/me/notifications?unreadOnly=true",null,null).body()).get("totalElements").asInt()).isZero();
        assertError(send(null,"GET","/me/notifications",null,null),401,"UNAUTHENTICATED");
        var spec=http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/v3/api-docs")).GET().build(),HttpResponse.BodyHandlers.ofString());
        assertThat(json.readTree(spec.body()).at("/paths/~1api~1v1~1me~1notifications~1{id}~1read/patch/responses").has("204")).isTrue();
    }

    private void highRule() throws Exception {
        var rule=rules.snapshot().stream().filter(r->r.code()==FraudRule.Code.HIGH_AMOUNT).findFirst().orElseThrow();
        var response=send(testUsers.bearer(RoleCode.ADMIN,null),"PATCH","/fraud-rules/"+rule.id(),json.writeValueAsString(new FraudRuleService.Update(rule.version(),new FraudRule.Threshold("1000",null,null),40,true)),null);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
    }
    private String topic(EventType type) { return type.topic(properties.topicPrefix()); }
    private int count(String table) { return jdbc.queryForObject("SELECT count(*) FROM "+table,Integer.class); }
    private int receipt(String name,UUID event) { return jdbc.queryForObject("SELECT count(*) FROM processed_events WHERE consumer_name=? AND event_id=?",Integer.class,name,event); }
    private UUID id(HttpResponse<String> response) { assertThat(response.statusCode()).as(response.body()).isEqualTo(201); return UUID.fromString(json.readTree(response.body()).get("transaction").get("id").asText()); }
    private EventEnvelope completed(UUID transaction) { var fact=facts.find(transaction).orElseThrow(); return codec.create(EventType.TRANSFER_COMPLETED,transaction,fact.at(),"transfer-it",new TransferCompletedEvent(transaction,SOURCE)); }
    private void sendEvent(EventEnvelope event) throws Exception { kafka.send(topic(event.eventType()),SOURCE.toString(),codec.encode(event)).get(5,TimeUnit.SECONDS); }
    private HttpResponse<String> transfer(String key,String amount) throws Exception { return send(testUsers.customer(SOURCE),"POST","/transfers",payload(SOURCE,DESTINATION,amount),key); }
    private HttpResponse<String> send(String token,String method,String path,String body,String key) throws Exception {
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1"+path)).timeout(Duration.ofSeconds(15)).header("Content-Type","application/json").header("X-Request-Id","transfer-it");
        if (token!=null) request.header("Authorization",token); if (key!=null) request.header("Idempotency-Key",key);
        return http.send(request.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
    }
    private List<ConsumerRecord<String,String>> readTopic(String topic,int expected) {
        try (var consumer=new KafkaConsumer<String,String>(Map.of(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,System.getenv("KAFKA_BOOTSTRAP_SERVERS"),
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,StringDeserializer.class,ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,StringDeserializer.class,
                ConsumerConfig.GROUP_ID_CONFIG,"test-probe-"+UUID.randomUUID(),ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG,false))) {
            var partitions=new ArrayList<TopicPartition>(); for (int i=0;i<properties.partitions();i++) partitions.add(new TopicPartition(topic,i));
            consumer.assign(partitions); for (var partition:partitions) consumer.seek(partition,startingOffsets.getOrDefault(partition,0L));
            var result=new ArrayList<ConsumerRecord<String,String>>();
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(expected==0?1:8);
            do { consumer.poll(Duration.ofMillis(100)).forEach(result::add); } while (result.size()<Math.max(1,expected) && System.nanoTime()<deadline);
            return result;
        }
    }
    private boolean caughtUp() {
        try (var admin=AdminClient.create(Map.of("bootstrap.servers",System.getenv("KAFKA_BOOTSTRAP_SERVERS"),"default.api.timeout.ms",1500,"request.timeout.ms",1000))) {
            for (String kind:List.of("fraud","notification")) {
                var offsets=admin.listConsumerGroupOffsets(properties.topicPrefix()+"."+kind+".v1").partitionsToOffsetAndMetadata().get(2,TimeUnit.SECONDS);
                var desired=new HashMap<TopicPartition,OffsetSpec>();
                for (var type:EventType.values()) if (kind.equals("notification") || type==EventType.TRANSFER_COMPLETED)
                    for (int i=0;i<properties.partitions();i++) desired.put(new TopicPartition(topic(type),i),OffsetSpec.latest());
                var ends=admin.listOffsets(desired).all().get(2,TimeUnit.SECONDS);
                for (var entry:ends.entrySet()) if (entry.getValue().offset()>0 && (offsets.get(entry.getKey())==null || offsets.get(entry.getKey()).offset()<entry.getValue().offset())) return false;
            }
            return true;
        } catch (Exception failure) { return false; }
    }
    private void await(BooleanSupplier ready) {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime()<deadline) { if (ready.getAsBoolean()) return; try { Thread.sleep(50); } catch (InterruptedException failure) { throw new IllegalStateException(failure); } }
        assertThat(ready.getAsBoolean()).as("Kafka/DB condition reached within timeout").isTrue();
    }
    private void guard() {
        if (!properties.topicPrefix().matches("banking.test.[a-f0-9]{32}") || !System.getenv("TEST_KAFKA_DIRECTORY").matches("/tmp/banking-kafka-[a-f0-9]{32}")) throw new IllegalStateException("Dedicated test broker required");
    }
    private void broker(String action) throws Exception {
        guard(); var process=new ProcessBuilder("wsl.exe","-d",System.getenv("TEST_KAFKA_DISTRIBUTION"),"--","bash",System.getenv("TEST_KAFKA_SCRIPT"),action,
            System.getenv("TEST_KAFKA_DIRECTORY"),System.getenv("TEST_KAFKA_PORT"),System.getenv("TEST_KAFKA_CONTROLLER_PORT")).redirectErrorStream(true).start();
        if (!process.waitFor(15,TimeUnit.SECONDS)) { process.destroyForcibly(); throw new IllegalStateException("Kafka lifecycle timeout"); }
        if (process.exitValue()!=0) throw new IllegalStateException(new String(process.getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));
        if (action.equals("start")) await(()->{ try (var client=AdminClient.create(Map.of("bootstrap.servers",System.getenv("KAFKA_BOOTSTRAP_SERVERS"),"default.api.timeout.ms",1500,"request.timeout.ms",1000))) { return !client.describeCluster().nodes().get(2,TimeUnit.SECONDS).isEmpty(); } catch (Exception ignored) { return false; } });
    }
}
