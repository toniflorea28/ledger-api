package com.toniflorea.ledger;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

	public static final DockerImageName MYSQL_IMAGE = DockerImageName.parse("mysql:8.4");
	public static final DockerImageName KAFKA_IMAGE = DockerImageName.parse("apache/kafka-native:4.3.2");

	@Bean
	@ServiceConnection
	MySQLContainer mysqlContainer() {
		return new MySQLContainer(MYSQL_IMAGE)
				.withDatabaseName("ledger")
				.withUsername("ledger")
				.withPassword("ledger")
				.withCommand("--default-time-zone=+00:00", "--transaction-isolation=READ-COMMITTED");
	}

	@Bean
	@ServiceConnection
	KafkaContainer kafkaContainer() {
		return new KafkaContainer(KAFKA_IMAGE);
	}

}
