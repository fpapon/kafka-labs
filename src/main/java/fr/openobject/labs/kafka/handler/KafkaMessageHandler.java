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
package fr.openobject.labs.kafka.handler;

import fr.openobject.labs.kafka.KafkaManager;
import fr.openobject.labs.kafka.MetricStorage;
import fr.openobject.labs.kafka.model.Ticket;
import jakarta.json.bind.Jsonb;
import jakarta.json.bind.JsonbBuilder;

import java.util.function.Consumer;
import java.util.logging.Logger;

public class KafkaMessageHandler {

    private static final Logger logger = Logger.getLogger(KafkaManager.class.getName());

    private final MetricStorage metricStorage;

    public KafkaMessageHandler(final MetricStorage metricStorage) {
        this.metricStorage = metricStorage;
    }

    public Consumer<String> onMessage() {
        return record -> {
            try (final Jsonb jsonb = JsonbBuilder.create()) {
                final var ticket = jsonb.fromJson(record, Ticket.class);
                this.metricStorage.increment(ticket.type());
            } catch (Exception exception) {
                logger.severe("Error while processing message :: " + record + " :: " + exception.getMessage());
            }
        };
    }
}
