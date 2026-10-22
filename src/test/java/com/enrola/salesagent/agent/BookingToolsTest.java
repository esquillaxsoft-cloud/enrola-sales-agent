package com.enrola.salesagent.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import com.enrola.salesagent.conversation.Conversation;
import com.enrola.salesagent.conversation.ConversationRepository;
import com.enrola.salesagent.conversation.ConversationStarter;
import com.enrola.salesagent.conversation.ConversationStatus;
import com.enrola.salesagent.scheduling.CalendlyClient;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The tools as the model sees them: JSON in, JSON out, against the stub Calendly. */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class BookingToolsTest {

	@Autowired
	BookingTools tools;

	@Autowired
	ConversationStarter starter;

	@Autowired
	ConversationRepository conversations;

	@Autowired
	CalendlyClient calendly;

	@Autowired
	Clock clock;

	JsonMapper json = JsonMapper.builder().build();
	Long johns;
	ZoneId perth = ZoneId.of("Australia/Perth");
	List<Instant> free;

	@BeforeEach
	void setUp() {
		johns = starter.startLead("lead-john").conversation().getId();
		Instant now = clock.instant();
		free = calendly.availableStartTimes(now, now.plus(Duration.ofDays(7)));
	}

	@Test
	void definitionsAreStrictFunctions() {
		assertThat(tools.definitions()).extracting(d -> d.get("name"))
				.containsExactly("get_available_slots", "book_call", "reschedule_call", "cancel_call");
		assertThat(tools.definitions()).allSatisfy(d -> assertThat(d.get("strict")).isEqualTo(true));
	}

	@Test
	void availableSlotsAreLabelledInTheLeadsTimeAndAtMostThree() {
		LocalDate day = free.getFirst().atZone(perth).toLocalDate();

		JsonNode result = run("get_available_slots",
				"{\"day\":\"%s\",\"from\":\"00:00\",\"to\":\"23:59\"}".formatted(day));

		assertThat(result.path("slots").size()).isBetween(1, 3);
		JsonNode first = result.path("slots").get(0);
		Instant start = Instant.parse(first.path("start_time").asString());
		// John is in WA: a 9:00 am Melbourne slot is labelled 7:00 am (or 6:00 am in daylight saving).
		assertThat(first.path("label").asString()).contains(start.atZone(perth).getDayOfMonth() + "");
		assertThat(result.has("matches_preference")).isTrue();
	}

	@Test
	void bookingAnOfferedTimeBooksTheConversation() {
		JsonNode result = run("book_call", "{\"start_time\":\"%s\"}".formatted(free.getFirst()));

		assertThat(result.path("status").asString()).isEqualTo("booked");
		assertThat(result.path("advisor").asString()).isNotBlank();
		Conversation conversation = conversations.findWithMessagesById(johns).orElseThrow();
		assertThat(conversation.getStatus()).isEqualTo(ConversationStatus.BOOKED);
		assertThat(conversation.getBooking().getStartTime()).isEqualTo(free.getFirst());
	}

	@Test
	void aTakenTimeComesBackWithAlternatives() {
		calendly.book("Someone else", "+61400000000", null, free.getFirst());

		JsonNode result = run("book_call", "{\"start_time\":\"%s\"}".formatted(free.getFirst()));

		assertThat(result.path("status").asString()).isEqualTo("unavailable");
		assertThat(result.path("alternatives").size()).isBetween(1, 3);
	}

	@Test
	void bookingTwiceTellsTheModelToRescheduleInstead() {
		run("book_call", "{\"start_time\":\"%s\"}".formatted(free.get(0)));

		JsonNode again = run("book_call", "{\"start_time\":\"%s\"}".formatted(free.get(1)));

		assertThat(again.path("error").asString()).contains("reschedule_call");
	}

	@Test
	void rescheduleMovesTheCall() {
		run("book_call", "{\"start_time\":\"%s\"}".formatted(free.get(0)));

		JsonNode result = run("reschedule_call", "{\"start_time\":\"%s\"}".formatted(free.get(1)));

		assertThat(result.path("status").asString()).isEqualTo("booked");
		assertThat(conversations.findById(johns).orElseThrow().getBooking().getStartTime()).isEqualTo(free.get(1));
	}

	@Test
	void cancelReopensTheConversation() {
		run("book_call", "{\"start_time\":\"%s\"}".formatted(free.getFirst()));

		JsonNode result = run("cancel_call", "{}");

		assertThat(result.path("status").asString()).isEqualTo("cancelled");
		assertThat(conversations.findById(johns).orElseThrow().getStatus()).isEqualTo(ConversationStatus.ACTIVE);
	}

	@Test
	void badArgumentsAreReportedToTheModelNotThrown() {
		assertThat(run("get_available_slots", "{\"day\":\"next tuesday\",\"from\":\"9\",\"to\":\"12\"}").path("error")
				.asString()).startsWith("Bad arguments");
		assertThat(run("cancel_call", "{}").path("error").asString()).contains("nothing to cancel");
	}

	private JsonNode run(String tool, String arguments) {
		return json.readTree(tools.execute(tool, arguments, johns));
	}

}
