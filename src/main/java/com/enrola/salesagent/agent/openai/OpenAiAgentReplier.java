package com.enrola.salesagent.agent.openai;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.enrola.salesagent.agent.AgentOutput;
import com.enrola.salesagent.agent.AgentReplier;
import com.enrola.salesagent.agent.AgentUnavailableException;
import com.enrola.salesagent.agent.PromptBuilder;
import com.enrola.salesagent.conversation.Conversation;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * One call to OpenAI's Responses API per turn: the conversation's system prompt as
 * {@code instructions}, the SMS transcript as {@code input}, and a strict JSON schema so the reply
 * always parses into {@link AgentOutput}.
 *
 * <p>{@code store: false}: OpenAI keeps nothing. These are conversations about people's health,
 * and our database already holds the only copy we need (README §5).
 */
public class OpenAiAgentReplier implements AgentReplier {

	private static final Logger log = LoggerFactory.getLogger(OpenAiAgentReplier.class);

	private final RestClient http;
	private final OpenAiProperties properties;
	private final PromptBuilder prompts;
	private final JsonMapper json;
	private final JsonNode outputSchema;

	public OpenAiAgentReplier(RestClient.Builder http, OpenAiProperties properties, PromptBuilder prompts,
			JsonMapper json) {
		this.http = http.baseUrl(properties.baseUrl())
				.defaultHeader("Authorization", "Bearer " + properties.apiKey())
				.build();
		this.properties = properties;
		this.prompts = prompts;
		this.json = json;
		this.outputSchema = loadSchema(json);
	}

	@Override
	public AgentOutput reply(Conversation conversation) {
		JsonNode response;
		try {
			response = http.post()
					.uri("/responses")
					.contentType(MediaType.APPLICATION_JSON)
					.body(request(conversation))
					.retrieve()
					.body(JsonNode.class);
		}
		catch (RestClientException e) {
			throw new AgentUnavailableException("OpenAI request failed: " + e.getMessage(), e);
		}
		logUsage(conversation, response);
		return parse(response);
	}

	Map<String, Object> request(Conversation conversation) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("model", properties.model());
		body.put("instructions", prompts.instructions(conversation));
		body.put("input", prompts.input(conversation));
		body.put("text", Map.of("format", Map.of(
				"type", "json_schema",
				"name", "agent_turn",
				"schema", outputSchema,
				"strict", true)));
		body.put("store", false);
		body.put("max_output_tokens", properties.maxOutputTokens());
		return body;
	}

	AgentOutput parse(JsonNode response) {
		if (response == null) {
			throw new AgentUnavailableException("Empty response from OpenAI");
		}
		String status = response.path("status").asString("");
		if (!"completed".equals(status)) {
			throw new AgentUnavailableException("OpenAI response " + status + ": "
					+ response.path("incomplete_details").path("reason").asString(response.path("error").toString()));
		}
		for (JsonNode item : response.path("output")) {
			if (!"message".equals(item.path("type").asString())) {
				continue;
			}
			for (JsonNode content : item.path("content")) {
				String type = content.path("type").asString();
				if ("refusal".equals(type)) {
					throw new AgentUnavailableException("Model refused: " + content.path("refusal").asString());
				}
				if ("output_text".equals(type)) {
					try {
						return json.readValue(content.path("text").asString(), AgentOutput.class);
					}
					catch (JacksonException e) {
						throw new AgentUnavailableException("Model output didn't match the schema", e);
					}
				}
			}
		}
		throw new AgentUnavailableException("No message in OpenAI response");
	}

	/** Cost visibility: tokens per turn, including how much of the prompt was served from cache. */
	private void logUsage(Conversation conversation, JsonNode response) {
		if (response == null) {
			return;
		}
		JsonNode usage = response.path("usage");
		log.info("OpenAI turn: conversation={} model={} input={} (cached {}) output={}",
				conversation.getId(), response.path("model").asString(),
				usage.path("input_tokens").asInt(), usage.path("input_tokens_details").path("cached_tokens").asInt(),
				usage.path("output_tokens").asInt());
	}

	private static JsonNode loadSchema(JsonMapper json) {
		try (InputStream in = OpenAiAgentReplier.class.getResourceAsStream("/agent/agent-output.schema.json")) {
			return json.readTree(in);
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

}
