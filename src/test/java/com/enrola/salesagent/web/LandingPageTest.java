package com.enrola.salesagent.web;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest
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

}
