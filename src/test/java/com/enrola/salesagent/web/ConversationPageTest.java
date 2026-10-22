package com.enrola.salesagent.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import com.enrola.salesagent.conversation.Conversation;
import com.enrola.salesagent.conversation.ConversationRepository;
import com.enrola.salesagent.conversation.ConversationStarter;
import com.enrola.salesagent.conversation.Message;
import com.enrola.salesagent.conversation.Sender;
import com.enrola.salesagent.lead.LeadService;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class ConversationPageTest {

	@Autowired
	MockMvc mvc;

	@Autowired
	LeadService leadService;

	@Autowired
	ConversationStarter starter;

	@Autowired
	ConversationRepository conversations;

	Long johnsConversation;

	@BeforeEach
	void seedJohn() {
		johnsConversation = starter.start(leadService.ingest("lead-john")).conversation().getId();
	}

	@Test
	void showsTheLeadAndTheOpener() throws Exception {
		mvc.perform(get("/conversations/{id}", johnsConversation))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString("lead-john")))
				.andExpect(content().string(containsString("helping a lot of HBF members")))
				.andExpect(content().string(containsString("template opener-known-provider")));
	}

	@Test
	void replyingAsTheLeadStoresTheMessageAndTheAgentsReply() throws Exception {
		mvc.perform(post("/conversations/{id}/messages", johnsConversation).param("body", "  Save money  "))
				.andExpect(redirectedUrl("/conversations/" + johnsConversation));

		Conversation conversation = conversations.findWithMessagesById(johnsConversation).orElseThrow();
		assertThat(conversation.getMessages()).extracting(Message::getSender)
				.containsExactly(Sender.AGENT, Sender.LEAD, Sender.AGENT);
		assertThat(conversation.getMessages().get(1).getBody()).isEqualTo("Save money");
		// The model's output for the turn sits on the lead message that started it.
		assertThat(conversation.getMessages().get(1).getAgentOutput()).contains("\"stage\":\"ENGAGE\"");

		mvc.perform(get("/conversations/{id}", johnsConversation))
				.andExpect(content().string(containsString("Save money")))
				// The turn panel and the state the platform sees.
				.andExpect(content().string(containsString("Agent's read")))
				.andExpect(content().string(containsString("ENGAGE")))
				.andExpect(content().string(containsString("What the platform sees")));
	}

	@Test
	void blankMessagesAreRejected() throws Exception {
		mvc.perform(post("/conversations/{id}/messages", johnsConversation).param("body", "   "))
				.andExpect(flash().attribute("error", containsString("1 to 1600")));

		assertThat(conversations.findWithMessagesById(johnsConversation).orElseThrow().getMessages())
				.extracting(Message::getSender).containsExactly(Sender.AGENT);
	}

	@Test
	void resetReplacesTheConversationWithAFreshOne() throws Exception {
		mvc.perform(post("/conversations/{id}/messages", johnsConversation).param("body", "hi"));

		mvc.perform(post("/conversations/{id}/reset", johnsConversation))
				.andExpect(status().is3xxRedirection());

		assertThat(conversations.findById(johnsConversation)).isEmpty();
		Conversation fresh = conversations.findAll().getFirst();
		assertThat(fresh.getLead().getExternalId()).isEqualTo("lead-john");
		// Only the opener: the earlier exchange is gone.
		assertThat(conversations.findWithMessagesById(fresh.getId()).orElseThrow().getMessages())
				.extracting(Message::getTemplateKey).containsExactly("opener-known-provider");
	}

	@Test
	void unknownConversationIs404() throws Exception {
		mvc.perform(get("/conversations/{id}", 999_999))
				.andExpect(status().isNotFound())
				.andExpect(content().string(containsString("Not found")));
	}

}
