package com.enrola.salesagent.scheduling;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.stereotype.Component;

/**
 * In-memory stand-in for Calendly. Advisors take calls every 30 minutes, 9:00 to 17:00 Melbourne
 * time on weekdays, with at least an hour's notice. About a quarter of slots are already taken
 * (deterministically, so tests are stable), so "that time isn't free, here are the closest" gets
 * exercised. Bookings live in memory and are lost on restart.
 */
@Component
class StubCalendlyClient implements CalendlyClient {

	static final ZoneId ADVISOR_ZONE = ZoneId.of("Australia/Melbourne");
	static final LocalTime FIRST_CALL = LocalTime.of(9, 0);
	static final LocalTime LAST_CALL = LocalTime.of(16, 30);
	static final Duration SLOT = Duration.ofMinutes(30);
	static final Duration MIN_NOTICE = Duration.ofHours(1);
	static final List<String> ADVISORS = List.of("Sam Nguyen", "Priya Shah", "Tom Walker");

	private final Clock clock;
	private final Map<String, BookedEvent> bookings = new ConcurrentHashMap<>();
	private final AtomicInteger roundRobin = new AtomicInteger();

	StubCalendlyClient(Clock clock) {
		this.clock = clock;
	}

	@Override
	public List<Instant> availableStartTimes(Instant start, Instant end) {
		if (Duration.between(start, end).compareTo(MAX_RANGE) > 0) {
			throw new IllegalArgumentException("Calendly allows at most 7 days per availability query");
		}
		Instant earliest = clock.instant().plus(MIN_NOTICE);
		List<Instant> free = new ArrayList<>();
		ZonedDateTime day = start.atZone(ADVISOR_ZONE).toLocalDate().atStartOfDay(ADVISOR_ZONE);
		while (day.toInstant().isBefore(end)) {
			if (isWeekday(day)) {
				for (LocalTime t = FIRST_CALL; !t.isAfter(LAST_CALL); t = t.plus(SLOT)) {
					Instant slot = day.with(t).toInstant();
					if (!slot.isBefore(start) && slot.isBefore(end) && !slot.isBefore(earliest) && isFree(slot)) {
						free.add(slot);
					}
				}
			}
			day = day.plusDays(1);
		}
		return free;
	}

	@Override
	public synchronized BookedEvent book(String name, String phone, String email, Instant startTime) {
		if (!availableStartTimes(startTime, startTime.plus(SLOT)).contains(startTime)) {
			throw new SlotUnavailableException(startTime);
		}
		String advisor = ADVISORS.get(roundRobin.getAndIncrement() % ADVISORS.size());
		BookedEvent event = new BookedEvent("evt-" + UUID.randomUUID(), startTime, advisor);
		bookings.put(event.id(), event);
		return event;
	}

	@Override
	public void cancel(String eventId) {
		bookings.remove(eventId);
	}

	private boolean isFree(Instant slot) {
		return !takenByOtherLeads(slot) && bookings.values().stream().noneMatch(b -> b.startTime().equals(slot));
	}

	/** Every fourth half-hour slot, so the same times are always taken and tests are stable. */
	static boolean takenByOtherLeads(Instant slot) {
		return Math.floorMod(slot.getEpochSecond() / SLOT.toSeconds(), 4) == 3;
	}

	private static boolean isWeekday(ZonedDateTime day) {
		return day.getDayOfWeek() != DayOfWeek.SATURDAY && day.getDayOfWeek() != DayOfWeek.SUNDAY;
	}

}
