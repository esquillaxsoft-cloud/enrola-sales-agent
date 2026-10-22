package com.enrola.salesagent.web;

import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class LandingPageTest {

	@Autowired
	MockMvc mvc;

	@Test
	void showsEmptyStateWhenThereAreNoLeads() throws Exception {
		mvc.perform(get("/"))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString("No leads yet")))
				// The WebJar locator rewrites the link to the versioned path, e.g. /webjars/bootstrap/5.3.7/...
				.andExpect(content().string(matchesPattern("(?s).*/webjars/bootstrap/[\\d.]+/css/bootstrap\\.min\\.css.*")));
	}

	@Test
	void seedingALeadStartsAConversationAndListsIt() throws Exception {
		mvc.perform(post("/leads").param("externalId", "lead-john"))
				.andExpect(redirectedUrl("/"))
				.andExpect(flash().attribute("notice", containsString("Started conversation")));

		mvc.perform(get("/"))
				.andExpect(content().string(allOf(containsString("lead-john"), containsString("ACTIVE"))));
	}

	@Test
	void seedingTheSameNumberTwiceSaysSo() throws Exception {
		mvc.perform(post("/leads").param("externalId", "lead-john"));

		mvc.perform(post("/leads").param("externalId", "lead-john-again"))
				.andExpect(flash().attribute("notice", containsString("already has an open conversation")));
	}

	@Test
	void unknownLeadIdShowsAnError() throws Exception {
		mvc.perform(post("/leads").param("externalId", "lead-nobody"))
				.andExpect(flash().attribute("error", containsString("lead-nobody")));
	}

}
