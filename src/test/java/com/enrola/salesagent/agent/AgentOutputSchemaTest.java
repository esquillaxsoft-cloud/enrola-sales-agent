package com.enrola.salesagent.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The schema sent to OpenAI and the record we parse into must agree, or the model will be told
 * one thing and parsed as another.
 */
class AgentOutputSchemaTest {

	JsonNode schema = load();

	@Test
	void everyRecordFieldIsRequiredBySchemaAndViceVersa() {
		Set<String> recordFields = Arrays.stream(AgentOutput.class.getRecordComponents())
				.map(c -> c.getName())
				.collect(Collectors.toSet());
		List<String> required = new ArrayList<>();
		schema.get("required").forEach(n -> required.add(n.asString()));

		assertThat(required).containsExactlyInAnyOrderElementsOf(recordFields);
		assertThat(schema.get("properties").propertyNames()).containsExactlyInAnyOrderElementsOf(recordFields);
	}

	@Test
	void isStrictModeCompatible() {
		assertThat(schema.get("additionalProperties").asBoolean()).isFalse();
	}

	@Test
	void stageEnumMatchesTheJavaEnum() {
		List<String> values = new ArrayList<>();
		schema.at("/properties/stage/enum").forEach(n -> values.add(n.asString()));

		assertThat(values).containsExactlyElementsOf(
				Arrays.stream(AgentOutput.Stage.values()).map(Enum::name).toList());
	}

	@Test
	void replyMayBeNullSoTheAgentCanStaySilent() {
		List<String> types = new ArrayList<>();
		schema.at("/properties/reply/type").forEach(n -> types.add(n.asString()));

		assertThat(types).containsExactlyInAnyOrder("string", "null");
	}

	@Test
	void aModelResponseParsesIntoTheRecord() {
		AgentOutput output = JsonMapper.builder().build().readValue("""
				{"reply":null,"stage":"AFTER_BOOKING","objection":false,"unsubscribe":false,"abusive":false,
				 "note":"lead said thanks after booking"}""", AgentOutput.class);

		assertThat(output.reply()).isNull();
		assertThat(output.stage()).isEqualTo(AgentOutput.Stage.AFTER_BOOKING);
	}

	private static JsonNode load() {
		try (InputStream in = AgentOutputSchemaTest.class.getResourceAsStream("/agent/agent-output.schema.json")) {
			return JsonMapper.builder().build().readTree(in);
		}
		catch (IOException e) {
			throw new IllegalStateException(e);
		}
	}

}
