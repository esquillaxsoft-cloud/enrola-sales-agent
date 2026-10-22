package com.enrola.salesagent.agent.openai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Locale;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.enrola.salesagent.agent.AgentOutput;
import com.enrola.salesagent.agent.AgentUnavailableException;
import com.enrola.salesagent.agent.PromptBuilderTestAccess;
import com.enrola.salesagent.conversation.Conversation;
import com.enrola.salesagent.conversation.Message;
import com.enrola.salesagent.lead.AuState;
import com.enrola.salesagent.lead.Lead;

import tools.jackson.databind.json.JsonMapper;

/** The HTTP contract with OpenAI, against a mock server: no network, no tokens. */
class OpenAiAgentReplierTest {

	static final Locale EN_AU = Locale.forLanguageTag("en-AU");
	static final Instant NOW = Instant.parse("2026-07-26T23:00:00Z");

	MockRestServiceServer server;
	OpenAiAgentReplier replier;
	Conversation conversation;

	@BeforeEach
	void setUp() {
		RestClient.Builder http = RestClient.builder();
		server = MockRestServiceServer.bindTo(http).build();
		var properties = new OpenAiProperties("sk-test", "gpt-6.1-sol", null, Duration.ofSeconds(5), 600);
		replier = new OpenAiAgentReplier(http, properties,
				PromptBuilderTestAccess.promptBuilder("comparato/v1", Clock.fixed(NOW, ZoneOffset.UTC)),
				JsonMapper.builder().build());

		Lead john = new Lead("lead-john", "John", "+61457099876", null, AuState.WA, "HBF", null, NOW);
		conversation = new Conversation(john, "comparato/v1", EN_AU, NOW);
		conversation.append(Message.fromTemplate("opener-known-provider", "Hi John, it's Anna from Comparato.", NOW));
		conversation.append(Message.fromLead("All of the above", NOW));
	}

	@Test
	void sendsTheConversationWithAStrictSchemaAndAsksOpenAiToStoreNothing() {
		server.expect(requestTo("https://api.openai.com/v1/responses"))
				.andExpect(method(HttpMethod.POST))
				.andExpect(header("Authorization", "Bearer sk-test"))
				.andExpect(jsonPath("$.model").value("gpt-6.1-sol"))
				.andExpect(jsonPath("$.store").value(false))
				.andExpect(jsonPath("$.instructions").value(org.hamcrest.Matchers.containsString("You are Anna")))
				.andExpect(jsonPath("$.input[0].role").value("assistant"))
				.andExpect(jsonPath("$.input[1].role").value("user"))
				.andExpect(jsonPath("$.input[1].content").value("All of the above"))
				.andExpect(jsonPath("$.text.format.type").value("json_schema"))
				.andExpect(jsonPath("$.text.format.strict").value(true))
				.andExpect(jsonPath("$.text.format.schema.required").isArray())
				.andRespond(withSuccess(completed("""
						{\\"reply\\":\\"Fair enough - a lot of people want both. Hospital, extras or both?\\",\\"stage\\":\\"PREFERENCE\\",\\"objection\\":false,\\"unsubscribe\\":false,\\"abusive\\":false,\\"note\\":\\"wants both\\"}"""),
						MediaType.APPLICATION_JSON));

		AgentOutput output = replier.reply(conversation);

		assertThat(output.reply()).startsWith("Fair enough");
		assertThat(output.stage()).isEqualTo(AgentOutput.Stage.PREFERENCE);
		server.verify();
	}

	@Test
	void anIncompleteResponseIsAnError() {
		server.expect(requestTo("https://api.openai.com/v1/responses"))
				.andRespond(withSuccess("""
						{"status":"incomplete","incomplete_details":{"reason":"max_output_tokens"},"output":[]}""",
						MediaType.APPLICATION_JSON));

		assertThatThrownBy(() -> replier.reply(conversation))
				.isInstanceOf(AgentUnavailableException.class)
				.hasMessageContaining("max_output_tokens");
	}

	@Test
	void aRefusalIsAnError() {
		server.expect(requestTo("https://api.openai.com/v1/responses"))
				.andRespond(withSuccess("""
						{"status":"completed","output":[{"type":"message","content":[{"type":"refusal","refusal":"no"}]}]}""",
						MediaType.APPLICATION_JSON));

		assertThatThrownBy(() -> replier.reply(conversation)).isInstanceOf(AgentUnavailableException.class);
	}

	@Test
	void aServerErrorIsAnError() {
		server.expect(requestTo("https://api.openai.com/v1/responses")).andRespond(withServerError());

		assertThatThrownBy(() -> replier.reply(conversation)).isInstanceOf(AgentUnavailableException.class);
	}

	/** A Responses API reply whose output text is the given JSON (already escaped for embedding). */
	static String completed(String escapedOutputJson) {
		return """
				{"status":"completed","model":"gpt-6.1-sol",
				 "output":[{"type":"reasoning","summary":[]},
				           {"type":"message","role":"assistant","content":[{"type":"output_text","text":"%s"}]}],
				 "usage":{"input_tokens":2500,"input_tokens_details":{"cached_tokens":2048},"output_tokens":80}}"""
				.formatted(escapedOutputJson);
	}

}
