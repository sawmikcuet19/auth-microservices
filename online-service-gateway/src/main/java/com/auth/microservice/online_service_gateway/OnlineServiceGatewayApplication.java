package com.auth.microservice.online_service_gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

@SpringBootApplication
@EnableDiscoveryClient
public class OnlineServiceGatewayApplication {

	public static void main(String[] args) {
		SpringApplication.run(OnlineServiceGatewayApplication.class, args);
	}

}
