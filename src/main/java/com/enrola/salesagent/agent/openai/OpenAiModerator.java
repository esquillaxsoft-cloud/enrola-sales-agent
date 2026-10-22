package com.enrola.salesagent.agent.openai;

import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.enrola.salesagent.agent.Moderator;

import tools.jackson.databind.JsonNode;

/**
 * OpenAI's moderation endpoint, which is free to use. Only the categories that mean abuse aimed at
 * us end a conversation (see {@link Moderator}).
 */
public class OpenAiModerator implements Moderator {

	private static final Logger log = LoggerFactory.getLogger(OpenAiModerator.class);

	static final List<String> ABUSE = List.of("harassment", "harassment/threatening", "hate", "hate/threatening");

	private final RestClient http;
	private final String model;

	public OpenAiModerator(RestClient.Builder http, OpenAiProperties properties) {
		this.http = http.baseUrl(properties.baseUrl())
				.defaultHeader("Authorization", "Bearer " + properties.apiKey())
				.build();
		this.model = properties.moderationModel();
	}

	@Override
	public List<String> abusiveCategories(String message) {
		try {
			JsonNode response = http.post()
					.uri("/moderations")
					.contentType(MediaType.APPLICATION_JSON)
					.body(Map.of("model", model, "input", message))
					.retrieve()
					.body(JsonNode.class);
			JsonNode categories = response == null ? null : response.path("results").path(0).path("categories");
			if (categories == null || categories.isMissingNode()) {
				log.warn("Moderation response had no categories; carrying on without it");
				return List.of();
			}
			return ABUSE.stream().filter(category -> categories.path(category).asBoolean(false)).toList();
		}
		catch (RestClientException e) {
			// Fail open: the model's abusive flag still catches abuse.
			log.warn("Moderation unavailable, carrying on without it: {}", e.getMessage());
			return List.of();
		}
	}

}
