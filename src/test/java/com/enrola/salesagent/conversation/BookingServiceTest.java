package com.enrola.salesagent.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.annotation.Transactional;

import com.enrola.salesagent.conversation.BookingService.Booked;
import com.enrola.salesagent.conversation.BookingService.Result;
import com.enrola.salesagent.conversation.BookingService.Unavailable;
import com.enrola.salesagent.scheduling.CalendlyClient;

/** Against the stub Calendly, wrapped in a spy to check the order of calls. */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class BookingServiceTest {

	@Autowired
	BookingService bookings;

	@Autowired
	ConversationStarter starter;

	@Autowired
	Clock clock;

	@MockitoSpyBean
	CalendlyClient calendly;

	Conversation johns;
	List<Instant> free;

	@BeforeEach
	void setUp() {
		johns = starter.startLead("lead-john").conversation();
		Instant now = clock.instant();
		free = calendly.availableStartTimes(now, now.plus(Duration.ofDays(7)));
	}

	@Test
	void bookingAFreeTimeMeetsTheGoal() {
		Result result = bookings.book(johns, free.getFirst());

		assertThat(result).isInstanceOf(Booked.class);
		assertThat(johns.getStatus()).isEqualTo(ConversationStatus.BOOKED);
		assertThat(johns.isGoalMet()).isTrue();
		assertThat(johns.getBooking().getStartTime()).isEqualTo(free.getFirst());
		assertThat(johns.getBooking().getAdvisorName()).isNotBlank();
	}

	@Test
	void aTimeTakenSinceItWasOfferedComesBackWithAlternatives() {
		calendly.book("Someone else", "+61400000000", null, free.getFirst());

		Result result = bookings.book(johns, free.getFirst());

		assertThat(result).isInstanceOfSatisfying(Unavailable.class, unavailable -> assertThat(unavailable.alternatives())
				.isNotEmpty().hasSizeLessThanOrEqualTo(3).doesNotContain(free.getFirst()));
		assertThat(johns.getBooking()).isNull();
		assertThat(johns.getStatus()).isEqualTo(ConversationStatus.ACTIVE);
	}

	@Test
	void rescheduleBooksTheNewTimeBeforeCancellingTheOld() {
		bookings.book(johns, free.get(0));
		String oldEvent = johns.getBooking().getEventId();

		bookings.reschedule(johns, free.get(1));

		InOrder order = inOrder(calendly);
		order.verify(calendly).book(anyString(), anyString(), any(), eq(free.get(1)));
		order.verify(calendly).cancel(oldEvent);
		assertThat(johns.getBooking().getStartTime()).isEqualTo(free.get(1));
		assertThat(johns.getStatus()).isEqualTo(ConversationStatus.BOOKED);
		assertThat(calendly.availableStartTimes(free.get(0), free.get(0).plusSeconds(1))).containsExactly(free.get(0));
	}

	@Test
	void ifTheNewTimeIsGoneTheExistingCallIsKept() {
		bookings.book(johns, free.get(0));
		Booking original = johns.getBooking();
		calendly.book("Someone else", "+61400000000", null, free.get(1));

		Result result = bookings.reschedule(johns, free.get(1));

		assertThat(result).isInstanceOf(Unavailable.class);
		assertThat(johns.getBooking()).isSameAs(original);
		verify(calendly, never()).cancel(original.getEventId());
	}

	@Test
	void cancellingFreesTheTimeAndReopensTheConversation() {
		bookings.book(johns, free.getFirst());

		bookings.cancel(johns);

		assertThat(johns.getBooking()).isNull();
		assertThat(johns.isGoalMet()).isFalse();
		assertThat(johns.getStatus()).isEqualTo(ConversationStatus.ACTIVE);
		assertThat(calendly.availableStartTimes(free.getFirst(), free.getFirst().plusSeconds(1))).isNotEmpty();
	}

	@Test
	void resetCancelsTheBookedCall() {
		bookings.book(johns, free.getFirst());
		String event = johns.getBooking().getEventId();

		starter.restart(johns.getId());

		verify(calendly).cancel(event);
	}

}
