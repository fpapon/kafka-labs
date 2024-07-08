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

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinWorkerThread;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import static java.net.http.HttpResponse.BodyHandlers.ofString;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Objects.requireNonNull;
import static java.util.logging.Level.SEVERE;
import static java.util.stream.Collectors.toMap;

public class MetricExporter {

    private static final Logger logger = Logger.getLogger(MetricExporter.class.getName());

    private HttpClient httpClient;
    private Executor httpClientExecutor;
    private Duration pushingInterval;
    private Duration connectionTimeout;
    private URI uri;
    private String topic = "unset-topic";
    private final MetricStorage metricStorage;

    public MetricExporter(final MetricStorage metricStorage) {
        Optional.ofNullable(System.getenv("LABS_KAFKA_TOPIC")).ifPresent(value ->
                this.topic = value
        );
        Optional.ofNullable(System.getenv(MetricExporterEnv.LABS_EXPORTER_DELAY.name())).ifPresent(value ->
                this.pushingInterval = Duration.ofMillis(Integer.parseInt(requireNonNull(value, "No exporter delay defined, please set LABS_EXPORTER_DELAY")))
        );
        this.connectionTimeout = Duration.ofMillis(Integer.parseInt(
                Optional.ofNullable(System.getenv(MetricExporterEnv.LABS_EXPORTER_API_TIMEOUT.name())).orElse("5000")));

        Optional.ofNullable(System.getenv(MetricExporterEnv.LABS_EXPORTER_API_URI.name())).ifPresent(value ->
                this.uri = URI.create(requireNonNull(value, "No URI defined, please set LABS_EXPORTER_API_URI"))
        );
        this.metricStorage = metricStorage;
        this.httpClient = HttpClient.newBuilder().connectTimeout(connectionTimeout).build();
        this.httpClientExecutor = newPool(2);
    }

    public ExecutorService start() {
        final var pool = Executors.newScheduledThreadPool(2, new ThreadFactory() {
            private final AtomicInteger counter = new AtomicInteger();

            @Override
            public Thread newThread(final Runnable r) {
                return new Thread(r, "metric-exporter-" + counter.incrementAndGet());
            }
        });
        pool.scheduleAtFixedRate(() -> {
            final var metrics = this.metricStorage.exportAll(topic, false);
            if (Objects.nonNull(metrics) && !metrics.isEmpty()) {
                post(metrics).whenComplete((s, throwable) -> {
                    if (Objects.nonNull(throwable)) {
                        logger.severe("Error when pushing to metric relay :: " + throwable.getMessage());
                    } else {
                        logger.info("Sending to metric relay ok");
                        this.metricStorage.resetAll();
                    }
                });
            } else {
                logger.info("No metrics to send");
            }
        }, pushingInterval.toMillis(), pushingInterval.toMillis(), TimeUnit.MILLISECONDS);
        return pool;
    }

    public void stop() {
        logger.info("Metric exporter stopping...");
        this.httpClient = null;
    }

    private ForkJoinPool newPool(final int parallelism) {
        return new ForkJoinPool(
                parallelism,
                new ForkJoinPool.ForkJoinWorkerThreadFactory() {
                    private final AtomicInteger counter = new AtomicInteger(1);

                    @Override
                    public ForkJoinWorkerThread newThread(final ForkJoinPool pool) {
                        return new ForkJoinWorkerThread(pool) {
                            {
                                setName("metric-exporter-http-client-" + counter.incrementAndGet());
                            }
                        };
                    }
                },
                (t, ex) -> {
                    logger.log(SEVERE, ex, ex::getMessage);
                    throw new IllegalStateException(ex);
                }, true);
    }

    private CompletionStage<String> post(final String body) {
        final var request = HttpRequest.newBuilder()
                .POST(HttpRequest.BodyPublishers.ofString(
                        body,
                        UTF_8))
                .uri(uri)
                .header("accept", "text/plain")
                .header("content-type", "text/plain");

        return httpClient.sendAsync(
                        request.build(),
                        ofString())
                .thenApplyAsync(res -> {
                    if (res.statusCode() != 201) {
                        throw new IllegalStateException("Invalid response: " + res);
                    }
                    return res.body();
                }, httpClientExecutor);
    }

    private enum MetricExporterEnv {
        LABS_EXPORTER_DELAY,
        LABS_EXPORTER_API_URI,
        LABS_EXPORTER_API_TIMEOUT
    }
}
