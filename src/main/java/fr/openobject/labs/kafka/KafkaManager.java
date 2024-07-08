/*
 * Copyright (c) 2024-Present - Francois Papon - Openobject.fr - https://openobject.fr
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package fr.openobject.labs.kafka;

import org.apache.kafka.clients.CommonClientConfigs;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.config.SaslConfigs;
import org.apache.kafka.common.errors.WakeupException;
import org.apache.kafka.common.serialization.StringDeserializer;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;

public class KafkaManager {

    private static final Logger logger = Logger.getLogger(KafkaManager.class.getName());

    private final Consumer<String> messageConsumer;
    private final KafkaConsumer<String, String> consumer;
    private final Properties properties;
    private String topic;
    private Duration pollingInterval;
    private final AtomicBoolean stopped = new AtomicBoolean(false);
    private ExecutorService taskExecutor;
    private Map<TopicPartition, Task> activeTasks = new ConcurrentHashMap<>();

    public KafkaManager(final Consumer<String> messageConsumer) {
        try (final InputStream is = this.getClass().getClassLoader().getResourceAsStream("kafka.properties")) {
            properties = new Properties();
            properties.load(is);
        } catch (IOException exception) {
            throw new IllegalStateException("kafka.properties not found!", exception);
        }

        Optional.ofNullable(System.getenv(KafkaEnv.LABS_KAFKA_GROUP_ID.name())).ifPresent(value ->
                properties.replace(ConsumerConfig.GROUP_ID_CONFIG, value)
        );
        if (!properties.containsKey(ConsumerConfig.GROUP_ID_CONFIG)) {
            throw new IllegalArgumentException("No " + ConsumerConfig.GROUP_ID_CONFIG + " set in " + KafkaEnv.LABS_KAFKA_GROUP_ID.name());
        }
        Optional.ofNullable(System.getenv(KafkaEnv.LABS_KAFKA_BOOTSTRAP_SERVERS.name())).ifPresent(value ->
                properties.replace(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, value)
        );
        if (!properties.containsKey(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG)) {
            throw new IllegalArgumentException("No " + ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG + " set in " + KafkaEnv.LABS_KAFKA_GROUP_ID.name());
        }

        Optional.ofNullable(System.getenv(KafkaEnv.LABS_KAFKA_TOPIC.name())).ifPresent(value ->
              this.topic = value
        );
        if (Objects.isNull(this.topic)) {
            throw new IllegalArgumentException("No topic set in " + KafkaEnv.LABS_KAFKA_TOPIC.name());
        }

        properties.putIfAbsent(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        properties.putIfAbsent(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        properties.putIfAbsent(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "true");
        properties.putIfAbsent(ConsumerConfig.AUTO_COMMIT_INTERVAL_MS_CONFIG, "5000");


        Optional.ofNullable(System.getenv(KafkaEnv.LABS_KAFKA_SECURITY_PROTOCOL_CONFIG.name())).ifPresent(value ->
                properties.putIfAbsent(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG, "SASL_PLAINTEXT")
        );
        Optional.ofNullable(System.getenv(KafkaEnv.LABS_KAFKA_SASL_JAAS_CONFIG_MODULE.name())).ifPresent(value ->
                properties.putIfAbsent(SaslConfigs.SASL_JAAS_CONFIG, value + " required" +
                        " username=\"" + System.getenv(KafkaEnv.LABS_KAFKA_SASL_JAAS_CONFIG_USERNAME.name()) + "\"" +
                        " password=\"" + System.getenv(KafkaEnv.LABS_KAFKA_SASL_JAAS_CONFIG_PASSWORD.name()) + "\";")
        );
        Optional.ofNullable(System.getenv(KafkaEnv.LABS_KAFKA_SASL_MECHANISM.name())).ifPresent(value ->
                properties.putIfAbsent(SaslConfigs.SASL_MECHANISM, "SCRAM-SHA-512")
        );

        Optional.ofNullable(System.getenv(KafkaEnv.LABS_KAFKA_POLLING_INTERVAL.name())).ifPresent(value ->
                this.pollingInterval = Duration.ofMillis(Integer.parseInt(value))
        );
        this.consumer = new KafkaConsumer<>(properties);
        this.messageConsumer = Optional.ofNullable(messageConsumer).orElse(onMessage());
        this.taskExecutor = Executors.newFixedThreadPool(1, new ThreadFactory() {
            private final AtomicInteger counter = new AtomicInteger();

            @Override
            public Thread newThread(final Runnable r) {
                return new Thread(r, "kafka-consumer-task-" + counter.incrementAndGet());
            }
        });
    }

    public Consumer<String> onMessage() {
        return record -> logger.severe("No consumer message handler configured, message action skipped!");
    }

    public ExecutorService start() {
        final var consumerExecutor = Executors.newSingleThreadExecutor(r -> new Thread(r, "kafka-consumer"));

        consumerExecutor.submit(() -> {
            this.consumer.subscribe(Stream.of(topic).toList());
            ConsumerRecords<String, String> consumerRecords = consumer.poll(pollingInterval);
            if (consumerRecords.count() > 0) {
                if (logger.isLoggable(Level.FINE)) {
                    logger.fine("Kafka messages poll :: count " + consumerRecords.count());
                }
                handleFetchedRecords(consumerRecords);
                this.consumer.commitAsync();
            }
        });
        return consumerExecutor;
    }

    private void handleFetchedRecords(ConsumerRecords<String, String> records) {
        if (records.count() > 0) {
            records.partitions().forEach(partition -> {
                List<ConsumerRecord<String, String>> partitionRecords = records.records(partition);
                taskExecutor.submit(() -> partitionRecords.forEach(record -> messageConsumer.accept(record.value())));
            });
        }
    }


    public void stop() {
        logger.info("Kafka manager stopping...");
        consumer.close(Duration.of(10, ChronoUnit.SECONDS));
    }

    private enum KafkaEnv {
        LABS_KAFKA_BOOTSTRAP_SERVERS,
        LABS_KAFKA_GROUP_ID,
        LABS_KAFKA_TOPIC,
        LABS_KAFKA_POLLING_INTERVAL,
        LABS_KAFKA_SECURITY_PROTOCOL_CONFIG,
        LABS_KAFKA_SASL_JAAS_CONFIG_MODULE,
        LABS_KAFKA_SASL_JAAS_CONFIG_USERNAME,
        LABS_KAFKA_SASL_JAAS_CONFIG_PASSWORD,
        LABS_KAFKA_SASL_MECHANISM
    }

    public class Task implements Runnable {
        private final List<ConsumerRecord<String, String>> consumerRecords;

        public Task(List<ConsumerRecord<String, String>> consumerRecords) {
            this.consumerRecords = consumerRecords;
        }
        public void run() {
            consumerRecords.forEach(record -> messageConsumer.accept(record.value()));
        }
    }
}
