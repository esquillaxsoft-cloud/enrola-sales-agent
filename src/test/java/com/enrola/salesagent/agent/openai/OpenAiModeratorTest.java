package com.enrola.salesagent.agent.openai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.time.Duration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class OpenAiModeratorTest {

	MockRestServiceServer server;
	OpenAiModerator moderator;

	@BeforeEach
	void setUp() {
		RestClient.Builder http = RestClient.builder();
		server = MockRestServiceServer.bindTo(http).build();
		moderator = new OpenAiModerator(http,
				new OpenAiProperties("sk-test", null, null, Duration.ofSeconds(5), 600, null));
	}

	@Test
	void harassmentIsAbuse() {
		respond("{\"harassment\":true,\"harassment/threatening\":true,\"self-harm\":false}");

		assertThat(moderator.abusiveCategories("...")).containsExactly("harassment", "harassment/threatening");
	}

	@Test
	void selfHarmIsNotAbuseTheLeadMayBeInDistress() {
		respond("{\"harassment\":false,\"self-harm\":true,\"self-harm/intent\":true,\"violence\":false}");

		assertThat(moderator.abusiveCategories("...")).isEmpty();
	}

	@Test
	void ifModerationIsDownTheTurnCarriesOn() {
		server.expect(requestTo("https://api.openai.com/v1/moderations")).andRespond(withServerError());

		assertThat(moderator.abusiveCategories("...")).isEmpty();
	}

	private void respond(String categories) {
		server.expect(requestTo("https://api.openai.com/v1/moderations"))
				.andExpect(jsonPath("$.model").value("omni-moderation-latest"))
				.andRespond(withSuccess("{\"results\":[{\"flagged\":true,\"categories\":" + categories + "}]}",
						MediaType.APPLICATION_JSON));
	}

}
