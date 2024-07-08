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

import kafka.server.KafkaConfig;
import kafka.server.KafkaServer;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.network.ListenerName;
import org.apache.kafka.common.security.auth.SecurityProtocol;
import org.apache.kafka.common.utils.Time;
import org.apache.kafka.metadata.BrokerState;
import org.apache.zookeeper.server.NIOServerCnxnFactory;
import org.apache.zookeeper.server.ZooKeeperServer;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.platform.commons.util.AnnotationUtils;
import scala.Option;

import java.io.IOException;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;
import static java.util.stream.Collectors.toList;

class Impl implements BeforeAllCallback, AfterAllCallback {
    private ZooKeeperServer zookeeper;
    private KafkaServer server;
    private Path baseDir;
    private NIOServerCnxnFactory factory;

    @Override
    public void beforeAll(final ExtensionContext context) throws Exception {
        System.setProperty("zookeeper.forceSync", "no");
        baseDir = Paths.get("target/temp-kafka/" + UUID.randomUUID().toString());
        final var snapshot = baseDir.resolve("snapshot");
        Files.createDirectories(snapshot);
        final var logs = baseDir.resolve("logs");
        Files.createDirectories(logs);
        this.zookeeper = new ZooKeeperServer(snapshot.toFile(), logs.toFile(), 1000);
        factory = new NIOServerCnxnFactory();
        final InetSocketAddress zkAddress;
        try (final ServerSocket socket = new ServerSocket(0)) {
            zkAddress = new InetSocketAddress("127.0.0.1", socket.getLocalPort());
        }
        factory.configure(zkAddress, 0, -1);
        factory.startup(this.zookeeper);

        final var brokerConf = new Properties();
        brokerConf.setProperty(KafkaConfig.BrokerIdProp(), "0");
        brokerConf.setProperty(KafkaConfig.ZkConnectProp(), "127.0.0.1:" + this.factory.getLocalPort());
        brokerConf.setProperty(KafkaConfig.ZkConnectionTimeoutMsProp(), "10000");
        brokerConf.setProperty(KafkaConfig.ControlledShutdownEnableProp(), String.valueOf(false));
        brokerConf.setProperty(KafkaConfig.DeleteTopicEnableProp(), String.valueOf(false));
        brokerConf.setProperty(KafkaConfig.LogDeleteDelayMsProp(), "1000");
        brokerConf.setProperty(KafkaConfig.ControlledShutdownRetryBackoffMsProp(), "100");
        brokerConf.setProperty(KafkaConfig.LogDirProp(), logs.toString());
        brokerConf.setProperty(KafkaConfig.LogCleanerDedupeBufferSizeProp(), "2097152");
        brokerConf.setProperty(KafkaConfig.LogMessageTimestampDifferenceMaxMsProp(), String.valueOf(Long.MAX_VALUE));
        brokerConf.setProperty(KafkaConfig.OffsetsTopicPartitionsProp(), "5");
        brokerConf.setProperty(KafkaConfig.GroupInitialRebalanceDelayMsProp(), "0");
        brokerConf.setProperty(KafkaConfig.ReplicaSocketTimeoutMsProp(), "1000");
        brokerConf.setProperty(KafkaConfig.ControllerSocketTimeoutMsProp(), "1000");
        brokerConf.setProperty(KafkaConfig.OffsetsTopicReplicationFactorProp(), "1");
        brokerConf.setProperty(KafkaConfig.ReplicaHighWatermarkCheckpointIntervalMsProp(), String.valueOf(Long.MAX_VALUE));

        server = new KafkaServer(new KafkaConfig(brokerConf), Time.SYSTEM, Option.apply("test-kafka-"), false);
        server.startup();
        final var port = server.socketServer().boundPort(ListenerName.forSecurityProtocol(SecurityProtocol.PLAINTEXT));

        try (final var admin = AdminClient.create(Map.of("bootstrap.servers", "localhost:" + port))) {
            admin.createTopics(Stream.of(AnnotationUtils.findAnnotation(context.getElement().orElseThrow(), EmbeddedKafka.class)
                    .orElseThrow().topics()).map(it -> new NewTopic(it, 1, (short) 1)).collect(toList()))
                    .all().get(1, TimeUnit.MINUTES);
        }

        // for tests to read it
        System.setProperty("test.kafka.port", String.valueOf(port));
    }

    @Override
    public void afterAll(final ExtensionContext context) {
        try {
            if (server != null && server.brokerState() != BrokerState.NOT_RUNNING) {
                server.shutdown();
                server.awaitShutdown();
            }
        } catch (final Exception e2) {
            // no-op
        }
        if (factory != null) {
            try {
                factory.stop();
                factory.join();
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (zookeeper != null) {
            try {
                this.zookeeper.shutdown();
            } catch (final Exception e) {
                // no-op
            }
        }
        if (Files.exists(baseDir)) {
            try {
                Files.walkFileTree(baseDir, new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult visitFile(final Path file, final BasicFileAttributes attrs) throws IOException {
                        Files.delete(file);
                        return super.visitFile(file, attrs);
                    }

                    @Override
                    public FileVisitResult postVisitDirectory(final Path dir, final IOException exc) throws IOException {
                        Files.delete(dir);
                        return super.postVisitDirectory(dir, exc);
                    }
                });
            } catch (final IOException e) {
                throw new IllegalStateException(e);
            }
        }
    }
}

@Target(TYPE)
@Retention(RUNTIME)
@ExtendWith(Impl.class)
public @interface EmbeddedKafka {
    String[] topics();
}
