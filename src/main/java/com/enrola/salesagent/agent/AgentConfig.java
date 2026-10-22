package com.enrola.salesagent.agent;

import java.net.http.HttpClient;
import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import com.enrola.salesagent.agent.openai.OpenAiAgentReplier;
import com.enrola.salesagent.agent.openai.OpenAiProperties;

import tools.jackson.databind.json.JsonMapper;

/**
 * Picks the agent: OpenAI when a key is configured, otherwise the stub, so the app (and the
 * tests) run without a key and never spend money by accident.
 */
@Configuration
class AgentConfig {

	private static final Logger log = LoggerFactory.getLogger(AgentConfig.class);

	@Bean
	AgentReplier agentReplier(OpenAiProperties openAi, PromptBuilder prompts, BookingTools tools, JsonMapper json) {
		if (!openAi.hasKey()) {
			log.warn("OPENAI_API_KEY is not set: using the stub agent (canned replies)");
			return new StubAgentReplier();
		}
		log.info("Agent: OpenAI {}", openAi.model());
		var requests = new JdkClientHttpRequestFactory(
				HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build());
		requests.setReadTimeout(openAi.timeout());
		return new OpenAiAgentReplier(RestClient.builder().requestFactory(requests), openAi, prompts, tools, json);
	}

}
