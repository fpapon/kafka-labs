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

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

public class MetricStorage {
    private final Map<String, LongAdder> metrics = new ConcurrentHashMap<>();

    public void increment(final String id) {
        metrics.putIfAbsent(id, new LongAdder());
        metrics.computeIfPresent(id, (key, value) -> {
            value.increment();
            return value;
        });
    }

    public Map<String, LongAdder> getMetrics() {
        return metrics;
    }

    public long getMetric(final String id) {
        return metrics.get(id) != null ? metrics.get(id).longValue() : -1L;
    }

    public void reset(final String id) {
        metrics.computeIfPresent(id, (key, value) -> {
            value.reset();
            return value;
        });
    }

    public void resetAll() {
        metrics.values().forEach(LongAdder::reset);
    }

    public void clear() {
        metrics.clear();
    }

    public String exportAll(final String topic, boolean withEof) {
        final StringBuilder exportMetrics = new StringBuilder();

        synchronized (this.metrics) {
            if (!this.metrics.isEmpty()) {
                this.metrics.forEach((key, value) -> {
                            exportMetrics.append("# TYPE ticketCount gauge\n");
                            exportMetrics.append("# HELP ticketCount doc\n");
                            exportMetrics.append("ticketCount{topic=\"" + topic + "\",type=\"" + key + "\"} " + value + "\n");
                        }
                );
                resetAll();
            }
            if (withEof) exportMetrics.append("# EOF\n");
        }
        return exportMetrics.toString();
    }
}
