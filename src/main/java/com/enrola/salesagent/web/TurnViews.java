package com.enrola.salesagent.web;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.enrola.salesagent.conversation.Conversation;
import com.enrola.salesagent.conversation.Message;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Turns the record kept on each lead message (the model's output, tool calls, usage, or a
 * moderation result) into something the conversation page can show next to the message.
 */
@Component
class TurnViews {

	record TurnView(String stage, List<String> flags, String note, String reply, List<ToolCallView> toolCalls,
			String usage, List<String> moderation, boolean retriedForLength) {
	}

	record ToolCallView(String name, String arguments, String result) {
	}

	private final JsonMapper json;

	TurnViews(JsonMapper json) {
		this.json = json;
	}

	/** Keyed by the lead message's id; lead messages without a record (e.g. STOP) are absent. */
	Map<Long, TurnView> of(Conversation conversation) {
		Map<Long, TurnView> views = new LinkedHashMap<>();
		for (Message message : conversation.getMessages()) {
			if (message.getAgentOutput() != null) {
				try {
					views.put(message.getId(), view(json.readTree(message.getAgentOutput())));
				}
				catch (JacksonException e) {
					// A record we can't read shouldn't break the page.
				}
			}
		}
		return views;
	}

	private TurnView view(JsonNode record) {
		if (record.has("moderation")) {
			List<String> flagged = strings(record.path("moderation").path("flagged"));
			return new TurnView(null, List.of(), "Ended by moderation before the model was asked", null, List.of(),
					null, flagged, false);
		}
		// Older records hold the model's output directly.
		JsonNode output = record.has("output") ? record.path("output") : record;
		List<String> flags = new ArrayList<>();
		for (String flag : List.of("objection", "unsubscribe", "abusive")) {
			if (output.path(flag).asBoolean(false)) {
				flags.add(flag);
			}
		}
		List<ToolCallView> tools = new ArrayList<>();
		record.path("toolCalls").forEach(call -> tools.add(new ToolCallView(call.path("name").asString(),
				call.path("arguments").asString(), call.path("result").asString())));
		return new TurnView(output.path("stage").asString(null), flags, output.path("note").asString(null),
				output.path("reply").isNull() ? null : output.path("reply").asString(null), tools,
				usage(record.path("usage")), List.of(), record.path("retriedForLength").asBoolean(false));
	}

	/** "gpt-6.1-sol · 2 rounds · 2,806 in (2,654 cached) / 81 out · 2.3 s" */
	private static String usage(JsonNode usage) {
		if (usage.isMissingNode() || usage.isNull()) {
			return null;
		}
		int rounds = usage.path("rounds").asInt();
		return String.format(Locale.UK, "%s · %d round%s · %,d in (%,d cached) / %,d out · %.1f s",
				usage.path("model").asString(), rounds, rounds == 1 ? "" : "s", usage.path("inputTokens").asLong(),
				usage.path("cachedTokens").asLong(), usage.path("outputTokens").asLong(),
				usage.path("latencyMillis").asLong() / 1000.0);
	}

	private static List<String> strings(JsonNode array) {
		List<String> values = new ArrayList<>();
		array.forEach(node -> values.add(node.asString()));
		return values;
	}

}
