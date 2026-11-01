package com.minh.banking.config;

import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import static org.assertj.core.api.Assertions.*;

class KafkaSecurityConfigTest {
    @Test void tlsAndSaslPropertiesReachProducerConsumersAndAdmin() {
        var connection=new org.springframework.boot.kafka.autoconfigure.KafkaProperties();
        connection.setBootstrapServers(List.of("127.0.0.1:19092"));
        connection.getProperties().put("security.protocol","SASL_SSL");
        connection.getProperties().put("sasl.mechanism","SCRAM-SHA-512");
        connection.getProperties().put("ssl.endpoint.identification.algorithm","https");
        connection.getProperties().put("sasl.jaas.config","synthetic-jaas-test-value");
        var configuration=new KafkaConfig.Enabled();
        var producer=configuration.bankingProducerFactory(connection);
        var template=new KafkaTemplate<String,String>(producer);
        var properties=new com.minh.banking.kafka.KafkaProperties(true,"banking.test.security",3,50,2);
        var maps=List.of(producer.getConfigurationProperties(),
            configuration.bankingKafkaAdmin(connection).getConfigurationProperties(),
            configuration.fraudKafkaListenerFactory(connection,template,properties).getConsumerFactory().getConfigurationProperties(),
            configuration.notificationKafkaListenerFactory(connection,template,properties).getConsumerFactory().getConfigurationProperties());
        for (var map:maps) {
            assertThat(map).containsEntry("security.protocol","SASL_SSL")
                .containsEntry("sasl.mechanism","SCRAM-SHA-512")
                .containsEntry("ssl.endpoint.identification.algorithm","https")
                .containsEntry("sasl.jaas.config","synthetic-jaas-test-value");
        }
        assertThat(producer.getConfigurationProperties()).containsEntry("enable.idempotence",true).containsEntry("acks","all");
    }
}
