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

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class MetricStorageTest {

    private final MetricStorage storage = new MetricStorage();

    @BeforeEach
    void init() {
        Stream.of("Add_K8S_Node", "Remove_K8S_Node")
                .forEach(storage::increment);
    }

    @AfterEach
    void reset() {
        this.storage.clear();
    }

    @Test
    void incrementExisting() {
        assertEquals(1, storage.getMetric("Add_K8S_Node"));
        storage.increment("Add_K8S_Node");
        assertEquals(2, storage.getMetric("Add_K8S_Node"));
    }

    @Test
    void incrementUnexisting() {
        assertEquals(-1L, storage.getMetric("Unexisting"));
        storage.increment("Unexisting");
        assertEquals(1, storage.getMetric("Unexisting"));
    }

    @Test
    void resetExisting() {
        assertEquals(1, storage.getMetric("Add_K8S_Node"));
        storage.increment("Add_K8S_Node");
        storage.reset("Add_K8S_Node");
        assertEquals(0, storage.getMetric("Add_K8S_Node"));
    }

    @Test
    void resetUnexisting() {
        assertEquals(-1L, storage.getMetric("Unexisting"));
        storage.reset("Unexisting");
        assertEquals(-1L, storage.getMetric("Unexisting"));
    }

    @Test
    void exportAll() {
        Stream.of("Add_K8S_Node", "Remove_K8S_Node")
                .forEach(storage::increment);
        assertEquals(
                "# TYPE ticketCount gauge\n" +
                        "# HELP ticketCount doc\n" +
                        "ticketCount{topic=\"myTestApp\",type=\"Add_K8S_Node\"} 2\n" +
                        "# TYPE ticketCount gauge\n" +
                        "# HELP ticketCount doc\n" +
                        "ticketCount{topic=\"myTestApp\",type=\"Remove_K8S_Node\"} 2\n" +
                        "# EOF\n",
                storage.exportAll("myTestApp", true));
    }

    @Test
    void exportAllReset() {
        Stream.of("Add_K8S_Node", "Remove_K8S_Node")
                .forEach(storage::reset);
        assertEquals(
                "# TYPE ticketCount gauge\n" +
                        "# HELP ticketCount doc\n" +
                        "ticketCount{topic=\"myTestApp\",type=\"Add_K8S_Node\"} 0\n" +
                        "# TYPE ticketCount gauge\n" +
                        "# HELP ticketCount doc\n" +
                        "ticketCount{topic=\"myTestApp\",type=\"Remove_K8S_Node\"} 0\n" +
                        "# EOF\n",
                storage.exportAll("myTestApp", true));
    }

    @Test
    void exportAllClear() {
        storage.clear();
        assertEquals("# EOF\n",
                storage.exportAll("myTestApp", true));
    }

}
