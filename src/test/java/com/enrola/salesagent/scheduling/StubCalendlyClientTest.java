package com.enrola.salesagent.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.enrola.salesagent.scheduling.CalendlyClient.BookedEvent;
import com.enrola.salesagent.scheduling.CalendlyClient.SlotUnavailableException;

class StubCalendlyClientTest {

	/** Sunday 26 July 2026, 9:00 am Melbourne (AEST, UTC+10): the brief's sample week. */
	static final Instant NOW = Instant.parse("2026-07-25T23:00:00Z");

	StubCalendlyClient calendly = new StubCalendlyClient(Clock.fixed(NOW, ZoneOffset.UTC));

	List<Instant> week = calendly.availableStartTimes(NOW, NOW.plus(Duration.ofDays(7)));

	@Test
	void slotsAreWeekdayHalfHoursInAdvisorHours() {
		assertThat(week).isNotEmpty().allSatisfy(slot -> {
			var local = slot.atZone(StubCalendlyClient.ADVISOR_ZONE);
			assertThat(local.getDayOfWeek()).isNotIn(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY);
			assertThat(local.toLocalTime()).isBetween(LocalTime.of(9, 0), LocalTime.of(16, 30));
			assertThat(local.getMinute() % 30).isZero();
		});
	}

	@Test
	void someSlotsAreAlreadyTakenSoAlternativesGetExercised() {
		// Monday has 16 half-hour slots between 9:00 and 16:30; every fourth is taken.
		long monday = week.stream()
				.filter(slot -> slot.atZone(StubCalendlyClient.ADVISOR_ZONE).getDayOfWeek() == DayOfWeek.MONDAY)
				.count();
		assertThat(monday).isEqualTo(12);
	}

	@Test
	void nothingWithinAnHourOfNow() {
		var nearlyNow = new StubCalendlyClient(Clock.fixed(Instant.parse("2026-07-26T23:10:00Z"), ZoneOffset.UTC));

		// Monday 9:10 am Melbourne: anything before 10:10 is too soon, so 10:30 is the earliest offer.
		assertThat(nearlyNow.availableStartTimes(Instant.parse("2026-07-26T23:00:00Z"), Instant.parse("2026-07-27T07:00:00Z")))
				.allSatisfy(slot -> assertThat(slot).isAfterOrEqualTo(Instant.parse("2026-07-27T00:10:00Z")));
	}

	@Test
	void queriesLongerThanSevenDaysAreRejectedLikeRealCalendly() {
		assertThatThrownBy(() -> calendly.availableStartTimes(NOW, NOW.plus(Duration.ofDays(8))))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void bookingTakesTheSlotAndAssignsAnAdvisor() {
		Instant slot = week.getFirst();

		BookedEvent event = calendly.book("John", "+61457099876", "john@gmail.com", slot);

		assertThat(event.startTime()).isEqualTo(slot);
		assertThat(event.advisorName()).isIn(StubCalendlyClient.ADVISORS);
		assertThat(calendly.availableStartTimes(NOW, NOW.plus(Duration.ofDays(7)))).doesNotContain(slot);
	}

	@Test
	void aTakenSlotCantBeBookedTwice() {
		Instant slot = week.getFirst();
		calendly.book("John", "+61457099876", null, slot);

		assertThatThrownBy(() -> calendly.book("Jane", "+61457099877", null, slot))
				.isInstanceOf(SlotUnavailableException.class);
	}

	@Test
	void cancellingFreesTheSlot() {
		Instant slot = week.getFirst();
		BookedEvent event = calendly.book("John", "+61457099876", null, slot);

		calendly.cancel(event.id());

		assertThat(calendly.availableStartTimes(NOW, NOW.plus(Duration.ofDays(7)))).contains(slot);
	}

	@Test
	void advisorsAreAssignedRoundRobin() {
		List<String> advisors = week.subList(0, 3).stream()
				.map(slot -> calendly.book("Lead", "+61400000000", null, slot).advisorName())
				.toList();

		assertThat(advisors).containsExactlyElementsOf(StubCalendlyClient.ADVISORS);
	}

}
