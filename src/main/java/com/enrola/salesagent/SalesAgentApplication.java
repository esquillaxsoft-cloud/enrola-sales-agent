package com.enrola.salesagent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class SalesAgentApplication {

	public static void main(String[] args) {
		SpringApplication.run(SalesAgentApplication.class, args);
	}

}
