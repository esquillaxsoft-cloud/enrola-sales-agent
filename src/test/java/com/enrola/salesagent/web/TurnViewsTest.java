package com.enrola.salesagent.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Locale;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.enrola.salesagent.conversation.Conversation;
import com.enrola.salesagent.conversation.Message;
import com.enrola.salesagent.lead.AuState;
import com.enrola.salesagent.lead.Lead;
import com.enrola.salesagent.web.TurnViews.TurnView;

import tools.jackson.databind.json.JsonMapper;

/** Each shape of turn record becomes something the page can show. */
class TurnViewsTest {

	static final Instant NOW = Instant.parse("2026-07-26T23:00:00Z");

	TurnViews views = new TurnViews(JsonMapper.builder().build());

	@Test
	void aModelTurnShowsItsStageFlagsToolsAndCost() {
		TurnView view = viewOf("""
				{"output":{"reply":null,"stage":"BOOKED","objection":true,"unsubscribe":false,"abusive":false,"note":"booked"},
				 "toolCalls":[{"name":"book_call","arguments":"{\\"start_time\\":\\"x\\"}","result":"{\\"status\\":\\"booked\\"}"}],
				 "usage":{"model":"gpt-6.1-sol","rounds":2,"inputTokens":5600,"cachedTokens":5300,"outputTokens":90,"latencyMillis":2300},
				 "retriedForLength":false}""");

		assertThat(view.stage()).isEqualTo("BOOKED");
		assertThat(view.flags()).containsExactly("objection");
		assertThat(view.reply()).isNull();
		assertThat(view.toolCalls()).singleElement().satisfies(c -> assertThat(c.name()).isEqualTo("book_call"));
		assertThat(view.usage()).isEqualTo("gpt-6.1-sol · 2 rounds · 5,600 in (5,300 cached) / 90 out · 2.3 s");
	}

	@Test
	void aModerationRecordSaysTheModelWasNeverAsked() {
		TurnView view = viewOf("{\"moderation\":{\"flagged\":[\"harassment\"]}}");

		assertThat(view.moderation()).containsExactly("harassment");
		assertThat(view.note()).contains("before the model was asked");
	}

	@Test
	void anOlderRecordHoldingJustTheOutputStillShows() {
		TurnView view = viewOf("{\"reply\":\"Hi\",\"stage\":\"ENGAGE\",\"objection\":false,\"unsubscribe\":true,\"abusive\":false,\"note\":\"n\"}");

		assertThat(view.stage()).isEqualTo("ENGAGE");
		assertThat(view.flags()).containsExactly("unsubscribe");
		assertThat(view.usage()).isNull();
	}

	private TurnView viewOf(String record) {
		Conversation conversation = new Conversation(
				new Lead("lead-x", "John", "+61457099876", null, AuState.WA, "HBF", null, NOW),
				"comparato/v1", Locale.forLanguageTag("en-AU"), NOW);
		Message inbound = Message.fromLead("hi", NOW);
		ReflectionTestUtils.setField(inbound, "id", 7L);
		inbound.recordAgentOutput(record);
		conversation.append(inbound);

		Map<Long, TurnView> views = this.views.of(conversation);
		return views.get(7L);
	}

}
