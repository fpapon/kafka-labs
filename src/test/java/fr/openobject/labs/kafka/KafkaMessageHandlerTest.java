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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import fr.openobject.labs.kafka.handler.KafkaMessageHandler;

import java.io.IOException;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

public class KafkaMessageHandlerTest {

    private final MetricStorage storage = new MetricStorage();

    @BeforeEach
    void init() {
        Stream.of("Add_K8S_Node", "Remove_K8S_Node").forEach(storage::increment);
    }

    @AfterEach
    void reset() {
        this.storage.clear();
    }

    @Test
    void consumeMessageHandler_AddNode() throws IOException {
        final String jsonTicket;
        try (final var inputStream = this.getClass().getClassLoader().getResourceAsStream("ticket_add_k8s_node.json")) {
            assertNotNull(inputStream);
            jsonTicket = new String(inputStream.readAllBytes());
        }
        assertNotNull(jsonTicket);

        Consumer<String> handler = new KafkaMessageHandler(this.storage).onMessage();
        handler.accept(jsonTicket);

        assertEquals(
                "# TYPE ticketCount gauge\n" +
                "# HELP ticketCount doc\n" +
                "ticketCount{topic=\"myTestApp\",type=\"Add_K8S_Node\"} 2\n" +
                "# TYPE ticketCount gauge\n" +
                "# HELP ticketCount doc\n" +
                "ticketCount{topic=\"myTestApp\",type=\"Remove_K8S_Node\"} 1\n" +
                "# EOF\n",
                storage.exportAll("myTestApp", true));
    }

    @Test
    void consumeMessageHandler_removeNode() throws IOException {
        final String jsonTicket;
        try (final var inputStream = this.getClass().getClassLoader().getResourceAsStream("ticket_remove_k8s_node.json")) {
            assertNotNull(inputStream);
            jsonTicket = new String(inputStream.readAllBytes());
        }
        assertNotNull(jsonTicket);

        Consumer<String> handler = new KafkaMessageHandler(this.storage).onMessage();
        handler.accept(jsonTicket);

        assertEquals(
                "# TYPE ticketCount gauge\n" +
                        "# HELP ticketCount doc\n" +
                        "ticketCount{topic=\"myTestApp\",type=\"Add_K8S_Node\"} 1\n" +
                        "# TYPE ticketCount gauge\n" +
                        "# HELP ticketCount doc\n" +
                        "ticketCount{topic=\"myTestApp\",type=\"Remove_K8S_Node\"} 2\n" +
                        "# EOF\n",
                storage.exportAll("myTestApp", true));
    }

}
