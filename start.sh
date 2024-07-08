#!/bin/bash
export LABS_HTTP_SERVER_PORT=8080
export LABS_KAFKA_BOOTSTRAP_SERVERS=localhost:9092
export LABS_KAFKA_GROUP_ID=metrics.kafka
export LABS_KAFKA_TOPIC=topic.ticket
export LABS_KAFKA_POLLING_INTERVAL=5000
export LABS_EXPORTER_DELAY=30000
export LABS_EXPORTER_API_URI=http://localhost:8080/metrics

java -javaagent:./target/opentelemetry/opentelemetry.jar -Dotel.traces.exporter=none -Dotel.logs.exporter=none -Dotel.metrics.exporter=prometheus -Dotel.exporter.prometheus.port=12345 -jar ./target/metrics-kafka-*-jar-with-dependencies.jar
