package com.enrola.salesagent.scheduling;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.enrola.salesagent.scheduling.SlotFinder.Suggestion;

/** Against a fixed list of free times, so each case reads like the conversation it models. */
class SlotFinderTest {

	static final ZoneId SYDNEY = ZoneId.of("Australia/Sydney");
	static final ZoneId PERTH = ZoneId.of("Australia/Perth");
	static final LocalDate MONDAY = LocalDate.of(2026, 7, 27);
	static final LocalDate TUESDAY = MONDAY.plusDays(1);

	/** Sunday evening before. */
	static final Instant NOW = Instant.parse("2026-07-26T08:00:00Z");

	/** The brief's example: nothing mid-morning Monday; 12:00 Monday, then Tuesday 9:00 and 9:30. */
	static final List<Instant> FREE = List.of(
			sydney(MONDAY, 12, 0),
			sydney(TUESDAY, 9, 0),
			sydney(TUESDAY, 9, 30),
			sydney(TUESDAY, 14, 0));

	SlotFinder finder = new SlotFinder(fixed(FREE), Clock.fixed(NOW, ZoneOffset.UTC));

	@Test
	void theBriefsExampleOffersTheClosestThreeWhenNothingFitsTheWindow() {
		// "Im working tomorrow Monday, mid-morning works"
		Suggestion suggestion = finder.suggest(SYDNEY, MONDAY, LocalTime.of(9, 30), LocalTime.of(11, 30));

		assertThat(suggestion.matchesPreference()).isFalse();
		assertThat(suggestion.slots()).containsExactly(
				sydney(MONDAY, 12, 0), sydney(TUESDAY, 9, 0), sydney(TUESDAY, 9, 30));
	}

	@Test
	void timesInTheWindowComeFirstAndSparePlacesGoToTheNearestOthers() {
		// Tuesday morning: 9:00 and 9:30 fit; 2:00 pm Tuesday is nearer than Monday noon.
		Suggestion suggestion = finder.suggest(SYDNEY, TUESDAY, LocalTime.of(9, 0), LocalTime.of(12, 0));

		assertThat(suggestion.matchesPreference()).isTrue();
		assertThat(suggestion.slots()).containsExactly(
				sydney(TUESDAY, 9, 0), sydney(TUESDAY, 9, 30), sydney(TUESDAY, 14, 0));
	}

	@Test
	void oneFitIsStillOfferedWithTwoAlternatives() {
		// "Any time Monday": only noon is free that day.
		Suggestion suggestion = finder.suggest(SYDNEY, MONDAY, LocalTime.MIN, LocalTime.of(23, 59));

		assertThat(suggestion.matchesPreference()).isTrue();
		assertThat(suggestion.slots()).containsExactly(
				sydney(MONDAY, 12, 0), sydney(TUESDAY, 9, 0), sydney(TUESDAY, 9, 30));
	}

	@Test
	void theWindowIsInTheLeadsTimeZone() {
		// Tuesday 7:00 am in Perth is 9:00 am in Sydney: a WA lead asking for "7 to 8" gets it.
		Suggestion suggestion = finder.suggest(PERTH, TUESDAY, LocalTime.of(7, 0), LocalTime.of(8, 0));

		assertThat(suggestion.matchesPreference()).isTrue();
		assertThat(suggestion.slots()).startsWith(sydney(TUESDAY, 9, 0), sydney(TUESDAY, 9, 30));
	}

	@Test
	void distanceIsZeroInsideTheWindowAndToTheNearerEdgeOutside() {
		Instant start = sydney(MONDAY, 10, 0);
		Instant end = sydney(MONDAY, 11, 0);

		assertThat(SlotFinder.distance(sydney(MONDAY, 10, 30), start, end)).isZero();
		assertThat(SlotFinder.distance(sydney(MONDAY, 9, 0), start, end).toMinutes()).isEqualTo(60);
		assertThat(SlotFinder.distance(sydney(MONDAY, 12, 0), start, end).toMinutes()).isEqualTo(60);
	}

	static Instant sydney(LocalDate day, int hour, int minute) {
		return day.atTime(hour, minute).atZone(SYDNEY).toInstant();
	}

	static CalendlyClient fixed(List<Instant> free) {
		return new CalendlyClient() {
			@Override
			public List<Instant> availableStartTimes(Instant start, Instant end) {
				return free.stream().filter(s -> !s.isBefore(start) && s.isBefore(end)).toList();
			}

			@Override
			public BookedEvent book(String name, String phone, String email, Instant startTime) {
				throw new UnsupportedOperationException();
			}

			@Override
			public void cancel(String eventId) {
				throw new UnsupportedOperationException();
			}
		};
	}

}
