package com.enrola.salesagent.api;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import com.jayway.jsonpath.JsonPath;

/** The contract with Enrola's platform, end to end over HTTP (with the stub agent). */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class PlatformApiTest {

	@Autowired
	MockMvc mvc;

	@Test
	void startingALeadReturnsTheOpenerToSendAndTheState() throws Exception {
		mvc.perform(post("/api/leads/lead-john/conversation"))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.outcome").value("STARTED"))
				.andExpect(jsonPath("$.outbound.body").value(startsWith("Hi John, it's Anna from Comparato.")))
				.andExpect(jsonPath("$.outbound.templateKey").value("opener-known-provider"))
				.andExpect(jsonPath("$.state.status").value("ACTIVE"))
				.andExpect(jsonPath("$.state.goalMet").value(false))
				.andExpect(jsonPath("$.state.unsubscribed").value(false))
				.andExpect(jsonPath("$.state.ended").value(false))
				.andExpect(jsonPath("$.state.booking").value(nullValue()));
	}

	@Test
	void startingTwiceSendsNothingTheSecondTime() throws Exception {
		long id = startJohn();

		mvc.perform(post("/api/leads/lead-john/conversation"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.outcome").value("ALREADY_STARTED"))
				.andExpect(jsonPath("$.conversationId").value(id))
				.andExpect(jsonPath("$.outbound").value(nullValue()));
	}

	@Test
	void aDuplicateLeadForTheSameNumberIsPointedAtTheOpenConversation() throws Exception {
		long id = startJohn();

		mvc.perform(post("/api/leads/lead-john-again/conversation"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.outcome").value("SAME_NUMBER_OPEN"))
				.andExpect(jsonPath("$.conversationId").value(id))
				.andExpect(jsonPath("$.outbound").value(nullValue()));
	}

	@Test
	void unknownLeadIs404ProblemDetail() throws Exception {
		mvc.perform(post("/api/leads/lead-nobody/conversation"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.detail").value("Lead API has no lead lead-nobody"));
	}

	@Test
	void inboundReturnsTheReplyToSendAndTheState() throws Exception {
		long id = startJohn();

		mvc.perform(inbound(id, "sms-1", "Save money"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.duplicate").value(false))
				.andExpect(jsonPath("$.outbound.body").value(startsWith("Thanks John")))
				.andExpect(jsonPath("$.state.status").value("ACTIVE"));
	}

	@Test
	void aRetriedInboundSmsReturnsTheOriginalReplyWithoutRunningTheAgentAgain() throws Exception {
		long id = startJohn();
		String first = mvc.perform(inbound(id, "sms-1", "Save money")).andReturn().getResponse().getContentAsString();

		mvc.perform(inbound(id, "sms-1", "Save money"))
				.andExpect(jsonPath("$.duplicate").value(true))
				.andExpect(jsonPath("$.outbound.body").value((String) JsonPath.read(first, "$.outbound.body")));

		// Opener, one lead message, one reply: the retry added nothing.
		mvc.perform(get("/api/conversations/{id}", id))
				.andExpect(jsonPath("$.messages", hasSize(3)))
				.andExpect(jsonPath("$.messages[1].platformMessageId").value("sms-1"));
	}

	@Test
	void inboundWithoutAMessageIdStillWorks() throws Exception {
		long id = startJohn();

		mvc.perform(post("/api/conversations/{id}/inbound", id)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"body\":\"hi\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.outbound.body").exists());
	}

	@Test
	void blankInboundIs400() throws Exception {
		long id = startJohn();

		mvc.perform(inbound(id, "sms-1", " "))
				.andExpect(status().isBadRequest());
	}

	@Test
	void inboundForAnUnknownConversationIs404() throws Exception {
		mvc.perform(inbound(999_999, "sms-1", "hi"))
				.andExpect(status().isNotFound());
	}

	@Test
	void getReturnsTheTranscript() throws Exception {
		long id = startJohn();

		mvc.perform(get("/api/conversations/{id}", id))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.leadId").value("lead-john"))
				.andExpect(jsonPath("$.agentVersion").value("comparato/v1"))
				.andExpect(jsonPath("$.messages[0].sender").value("AGENT"))
				.andExpect(jsonPath("$.messages[0].templateKey").value("opener-known-provider"));
	}

	private long startJohn() throws Exception {
		String json = mvc.perform(post("/api/leads/lead-john/conversation")).andReturn().getResponse().getContentAsString();
		return ((Number) JsonPath.read(json, "$.conversationId")).longValue();
	}

	private static RequestBuilder inbound(long id, String messageId, String body) {
		return post("/api/conversations/{id}/inbound", id)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"messageId\":\"" + messageId + "\",\"body\":\"" + body + "\"}");
	}

}
