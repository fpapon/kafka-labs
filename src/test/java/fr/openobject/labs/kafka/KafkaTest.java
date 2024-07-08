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

import fr.openobject.labs.kafka.model.Ticket;
import jakarta.json.bind.Jsonb;
import jakarta.json.bind.JsonbBuilder;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.TestInstance.Lifecycle.PER_CLASS;

@EmbeddedKafka(topics = {
        "test.topic.ticket"
})
@TestInstance(PER_CLASS)
class KafkaTest {

    private static final Logger logger = Logger.getLogger(KafkaTest.class.getName());

    @AfterEach
    void reset() {
    }

    @Test
    void consumeSimple() throws Exception {
        final String jsonProductOrder;
        try (final var inputStream = this.getClass().getClassLoader().getResourceAsStream("ticket_add_k8s_node.json")) {
            assertNotNull(inputStream);
            jsonProductOrder = new String(inputStream.readAllBytes());
        }
        assertNotNull(jsonProductOrder);

        final var latch = new CountDownLatch(1);
        final var message = new AtomicReference<String>();

        final KafkaManager kafkaManager = new KafkaManager(record -> {
            logger.info("onMessage :: " + record);
            message.set(record);
            latch.countDown();
        });
        final var pool = kafkaManager.start();

        // send a message
        try (final var producer = new KafkaProducer<String, String>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:" + System.getProperty("test.kafka.port"),
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName(),
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName()))) {
            producer.send(new ProducerRecord<>("test.topic.ticket", "1", jsonProductOrder)).get();
            producer.flush();
        }

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertNotNull(message.get());

        try (final Jsonb jsonb = JsonbBuilder.create()) {
            final var ticket = jsonb.fromJson(message.get(), Ticket.class);
            assertEquals("UHlBXTUvRouW_2g2ze8ugQ", ticket.id());
        }

        kafkaManager.stop();
        pool.shutdownNow();
        try {
            pool.awaitTermination(10, TimeUnit.SECONDS);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            pool.close();
        }
    }
}
