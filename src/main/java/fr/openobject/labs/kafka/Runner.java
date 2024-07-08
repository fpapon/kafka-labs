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

import com.sun.net.httpserver.HttpServer;
import fr.openobject.labs.kafka.handler.HealthHttpHandler;
import fr.openobject.labs.kafka.handler.KafkaMessageHandler;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetSocketAddress;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

public class Runner {

    private static final Logger logger = Logger.getLogger(Runner.class.getName());

    public static void main(String[] args) throws InterruptedException, IOException {

        final var metricStorage = new MetricStorage();

        final AtomicInteger httpServerPort = new AtomicInteger(0);
        Optional.ofNullable(System.getenv("LABS_HTTP_SERVER_PORT")).ifPresent(value ->
                httpServerPort.set(Integer.parseInt(value))
        );

        final var healthServer = HttpServer.create(new InetSocketAddress(
                Inet4Address.getByAddress(new byte[]{0, 0, 0, 0}), httpServerPort.get()), 0);

        healthServer.createContext("/health", new HealthHttpHandler());

        final var httpServerPool = Executors.newFixedThreadPool(4, new ThreadFactory() {
            private final AtomicInteger counter = new AtomicInteger();

            @Override
            public Thread newThread(final Runnable r) {
                return new Thread(r, "labs-relay-http-server-" + counter.incrementAndGet());
            }
        });

        healthServer.setExecutor(httpServerPool);
        healthServer.start();
        logger.info("Server started on port " + httpServerPort.get());

        final KafkaManager kafkaManager = new KafkaManager(new KafkaMessageHandler(metricStorage).onMessage());
        final var kafkaPool = kafkaManager.start();
        logger.info("Kafka consumer started");

        final MetricExporter metricExporter = new MetricExporter(metricStorage);
        final var metricExporterPool = metricExporter.start();
        logger.info("Metric exporter started");

        final var countDownLatch = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            logger.info("Kafka consumer shutdown...");
            try {
                kafkaManager.stop();
                kafkaPool.shutdownNow();
                metricExporter.stop();
                metricExporterPool.shutdownNow();
                httpServerPool.shutdownNow();
                try {
                    kafkaPool.awaitTermination(1, TimeUnit.MINUTES);
                    metricExporterPool.awaitTermination(1, TimeUnit.MINUTES);
                    httpServerPool.awaitTermination(1, TimeUnit.MINUTES);
                } catch (final InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            } finally {
                countDownLatch.countDown();
                kafkaPool.close();
                metricExporterPool.close();
                httpServerPool.close();
            }
        }, "metric-kafka-shutdown"));
        countDownLatch.await();
        logger.info("Metric kafka exit");
    }
}
