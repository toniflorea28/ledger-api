package com.toniflorea.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.web.client.RestClient;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LedgerApplicationIT {

	@LocalServerPort
	int port;

	@Autowired
	JdbcClient jdbcClient;

	@Autowired
	KafkaAdmin kafkaAdmin;

	@Test
	void databaseIsReachableAndIsolationIsReadCommitted() {
		Integer one = jdbcClient.sql("SELECT 1").query(Integer.class).single();
		assertThat(one).isEqualTo(1);

		String isolation = jdbcClient.sql("SELECT @@transaction_isolation").query(String.class).single();
		assertThat(isolation).isEqualTo("READ-COMMITTED");
	}

	@Test
	void brokerIsReachable() {
		assertThat(kafkaAdmin.clusterId()).isNotBlank();
	}

	@Test
	void healthIsUp() {
		String body = RestClient.create("http://localhost:" + port)
				.get().uri("/actuator/health")
				.retrieve()
				.body(String.class);
		assertThat(body).contains("\"status\":\"UP\"");
	}

}
