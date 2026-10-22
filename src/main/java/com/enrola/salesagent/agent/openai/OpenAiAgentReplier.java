package com.enrola.salesagent.agent.openai;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.enrola.salesagent.agent.AgentOutput;
import com.enrola.salesagent.agent.AgentReplier;
import com.enrola.salesagent.agent.AgentTurn;
import com.enrola.salesagent.agent.AgentTurn.ToolCall;
import com.enrola.salesagent.agent.AgentTurn.Usage;
import com.enrola.salesagent.agent.AgentUnavailableException;
import com.enrola.salesagent.agent.BookingTools;
import com.enrola.salesagent.agent.PromptBuilder;
import com.enrola.salesagent.conversation.Conversation;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * One turn against OpenAI's Responses API: the conversation's system prompt as
 * {@code instructions}, the SMS transcript as {@code input}, the booking tools, and a strict JSON
 * schema so the final reply always parses into {@link AgentOutput}.
 *
 * <p>If the model calls tools, they're run and their results sent back, round after round, until it
 * answers (at most {@link #MAX_TOOL_ROUNDS}).
 *
 * <p>{@code store: false}: OpenAI keeps nothing. These are conversations about people's health,
 * and our database already holds the only copy we need (DESIGN.md §5). So each round sends back
 * the model's own output items, with its reasoning encrypted, instead of a response id.
 */
public class OpenAiAgentReplier implements AgentReplier {

	private static final Logger log = LoggerFactory.getLogger(OpenAiAgentReplier.class);

	/** Find times, book: two rounds is normal. More than this means the model is going in circles. */
	static final int MAX_TOOL_ROUNDS = 5;

	private final RestClient http;
	private final OpenAiProperties properties;
	private final PromptBuilder prompts;
	private final BookingTools tools;
	private final JsonMapper json;
	private final JsonNode outputSchema;

	public OpenAiAgentReplier(RestClient.Builder http, OpenAiProperties properties, PromptBuilder prompts,
			BookingTools tools, JsonMapper json) {
		this.http = http.baseUrl(properties.baseUrl())
				.defaultHeader("Authorization", "Bearer " + properties.apiKey())
				.build();
		this.properties = properties;
		this.prompts = prompts;
		this.tools = tools;
		this.json = json;
		this.outputSchema = loadSchema(json);
	}

	@Override
	public AgentTurn reply(Conversation conversation) {
		return reply(conversation, null);
	}

	@Override
	public AgentTurn reply(Conversation conversation, String correction) {
		long started = System.nanoTime();
		List<Object> input = new ArrayList<>(prompts.input(conversation));
		if (correction != null) {
			// A note from our code, not from the lead, after the transcript.
			input.add(Map.of("role", "developer", "content", correction));
		}
		List<ToolCall> toolCalls = new ArrayList<>();
		long inputTokens = 0;
		long cachedTokens = 0;
		long outputTokens = 0;
		for (int round = 1; round <= MAX_TOOL_ROUNDS; round++) {
			JsonNode response = send(request(conversation, input));
			logUsage(conversation, round, response);
			JsonNode usage = response.path("usage");
			inputTokens += usage.path("input_tokens").asLong();
			cachedTokens += usage.path("input_tokens_details").path("cached_tokens").asLong();
			outputTokens += usage.path("output_tokens").asLong();

			List<JsonNode> calls = new ArrayList<>();
			response.path("output").forEach(item -> {
				if ("function_call".equals(item.path("type").asString())) {
					calls.add(item);
				}
			});
			if (calls.isEmpty()) {
				Usage total = new Usage(response.path("model").asString(properties.model()), round, inputTokens,
						cachedTokens, outputTokens, (System.nanoTime() - started) / 1_000_000);
				return new AgentTurn(parse(response), List.copyOf(toolCalls), total);
			}
			// Send back what the model produced (its reasoning and the calls), then each call's result.
			response.path("output").forEach(input::add);
			for (JsonNode call : calls) {
				String name = call.path("name").asString();
				String arguments = call.path("arguments").asString();
				String result = tools.execute(name, arguments, conversation.getId());
				toolCalls.add(new ToolCall(name, arguments, result));
				input.add(Map.of("type", "function_call_output", "call_id", call.path("call_id").asString(),
						"output", result));
			}
		}
		throw new AgentUnavailableException("Model made more than " + MAX_TOOL_ROUNDS + " rounds of tool calls");
	}

	private JsonNode send(Map<String, Object> request) {
		try {
			JsonNode response = http.post()
					.uri("/responses")
					.contentType(MediaType.APPLICATION_JSON)
					.body(request)
					.retrieve()
					.body(JsonNode.class);
			if (response == null) {
				throw new AgentUnavailableException("Empty response from OpenAI");
			}
			return response;
		}
		catch (RestClientException e) {
			throw new AgentUnavailableException("OpenAI request failed: " + e.getMessage(), e);
		}
	}

	Map<String, Object> request(Conversation conversation, List<Object> input) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("model", properties.model());
		body.put("instructions", prompts.instructions(conversation));
		body.put("input", input);
		body.put("tools", tools.definitions());
		body.put("text", Map.of("format", Map.of(
				"type", "json_schema",
				"name", "agent_turn",
				"schema", outputSchema,
				"strict", true)));
		body.put("store", false);
		// With store=false, reasoning carried between tool rounds has to travel with the request.
		body.put("include", List.of("reasoning.encrypted_content"));
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

	/** Cost visibility: tokens per call, including how much of the prompt was served from cache. */
	private void logUsage(Conversation conversation, int round, JsonNode response) {
		JsonNode usage = response.path("usage");
		log.info("OpenAI turn: conversation={} round={} model={} input={} (cached {}) output={}",
				conversation.getId(), round, response.path("model").asString(),
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
