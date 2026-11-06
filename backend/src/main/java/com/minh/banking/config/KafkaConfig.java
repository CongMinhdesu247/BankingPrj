package com.minh.banking.config;

import com.minh.banking.kafka.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.*;
import org.apache.kafka.common.serialization.*;
import org.slf4j.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.*;
import org.springframework.kafka.listener.*;
import org.springframework.util.backoff.FixedBackOff;

@Configuration @EnableConfigurationProperties({KafkaProperties.class, org.springframework.boot.kafka.autoconfigure.KafkaProperties.class})
public class KafkaConfig {
    @Configuration @EnableKafka
    @ConditionalOnProperty(name="banking.kafka.enabled", havingValue="true", matchIfMissing=true)
    static class Enabled {
        private static final Logger LOG = LoggerFactory.getLogger(Enabled.class);
        @Bean ProducerFactory<String, String> bankingProducerFactory(org.springframework.boot.kafka.autoconfigure.KafkaProperties connection) {
            Map<String, Object> config = new HashMap<>(connection.buildProducerProperties());
            config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
            config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
            config.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
            config.put(ProducerConfig.ACKS_CONFIG, "all");
            config.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 5);
            config.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 500);
            config.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 1000);
            config.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 3000);
            config.put(ProducerConfig.LINGER_MS_CONFIG, 0);
            config.put(ProducerConfig.MAX_REQUEST_SIZE_CONFIG, 65536);
            return new DefaultKafkaProducerFactory<>(config);
        }
        @Bean KafkaTemplate<String, String> bankingKafkaTemplate(ProducerFactory<String, String> factory) {
            var template = new KafkaTemplate<String, String>(factory);
            // Publish callbacks and the DLT handler log metadata; the default listener logs payloads.
            template.setProducerListener(null);
            return template;
        }
        @Bean KafkaAdmin bankingKafkaAdmin(org.springframework.boot.kafka.autoconfigure.KafkaProperties connection) {
            Map<String, Object> config = new HashMap<>(connection.buildAdminProperties());
            config.put("request.timeout.ms", 2000);
            config.put("default.api.timeout.ms", 3000);
            var admin = new KafkaAdmin(config);
            admin.setFatalIfBrokerNotAvailable(false);
            admin.setOperationTimeout(3);
            return admin;
        }
        @Bean KafkaAdmin.NewTopics bankingTopics(KafkaProperties properties) {
            var topics = new ArrayList<NewTopic>();
            for (var type:EventType.values()) {
                String topic = type.topic(properties.topicPrefix());
                topics.add(new NewTopic(topic, properties.partitions(), (short)1).configs(Map.of("retention.ms", "604800000", "max.message.bytes", "65536")));
                topics.add(new NewTopic(topic+".notification.dlt", properties.partitions(), (short)1).configs(Map.of("retention.ms", "86400000")));
                if (type == EventType.TRANSFER_COMPLETED) topics.add(new NewTopic(topic+".fraud.dlt", properties.partitions(), (short)1).configs(Map.of("retention.ms", "86400000")));
            }
            return new KafkaAdmin.NewTopics(topics.toArray(NewTopic[]::new));
        }
        @Bean ConcurrentKafkaListenerContainerFactory<String, String> fraudKafkaListenerFactory(
            org.springframework.boot.kafka.autoconfigure.KafkaProperties connection, KafkaTemplate<String, String> template, KafkaProperties properties) {
            return factory(connection, template, properties, "fraud");
        }
        @Bean ConcurrentKafkaListenerContainerFactory<String, String> notificationKafkaListenerFactory(
            org.springframework.boot.kafka.autoconfigure.KafkaProperties connection, KafkaTemplate<String, String> template, KafkaProperties properties) {
            return factory(connection, template, properties, "notification");
        }
        private ConcurrentKafkaListenerContainerFactory<String, String> factory(org.springframework.boot.kafka.autoconfigure.KafkaProperties connection, KafkaTemplate<String, String> template, KafkaProperties properties, String consumer) {
            Map<String, Object> config = new HashMap<>(connection.buildConsumerProperties());
            config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
            config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
            config.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
            config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
            // A batch can contain ten DB transactions (15s each), plus bounded retries/DLT delivery.
            config.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 10);
            config.put(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG, 300000);
            var factory = new ConcurrentKafkaListenerContainerFactory<String, String>();
            factory.setConsumerFactory(new DefaultKafkaConsumerFactory<>(config));
            factory.setConcurrency(1);
            factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.RECORD);
            factory.getContainerProperties().setPollTimeout(250);
            factory.getContainerProperties().setShutdownTimeout(5000);
            factory.getContainerProperties().setSyncCommits(true);
            factory.getContainerProperties().setMissingTopicsFatal(false);
            var errors = new DefaultErrorHandler((record, failure) -> {
                    var dead=new ProducerRecord<String, String>(record.topic()+"."+consumer+".dlt", record.partition(), (String)record.key(), (String)record.value());
                    dead.headers().add("banking-original-topic", record.topic().getBytes(StandardCharsets.UTF_8));
                    dead.headers().add("banking-original-partition", Integer.toString(record.partition()).getBytes(StandardCharsets.UTF_8));
                    dead.headers().add("banking-original-offset", Long.toString(record.offset()).getBytes(StandardCharsets.UTF_8));
                    dead.headers().add("banking-consumer", consumer.getBytes(StandardCharsets.UTF_8));
                    dead.headers().add("banking-failure-type", failure.getClass().getSimpleName().getBytes(StandardCharsets.UTF_8));
                    try { template.send(dead).get(5, java.util.concurrent.TimeUnit.SECONDS); }
                    catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException("DLT publish interrupted", interrupted); }
                    catch (Exception unavailable) { throw new IllegalStateException("DLT publish failed; offset must remain uncommitted", unavailable); }
                }, new FixedBackOff(properties.retryBackoffMs(), properties.retryAttempts()));
            errors.setRetryListeners((record, failure, attempt) -> LOG.warn("Kafka consumer retry: consumer={}, topic={}, partition={}, offset={}, attempt={}, failureType={}",
                consumer, record.topic(), record.partition(), record.offset(), attempt, failure.getClass().getSimpleName()));
            factory.setCommonErrorHandler(errors);
            return factory;
        }
    }
}
