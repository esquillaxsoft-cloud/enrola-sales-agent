package com.enrola.salesagent.api;

import java.time.Instant;
import java.util.List;

import com.enrola.salesagent.conversation.Booking;
import com.enrola.salesagent.conversation.Conversation;
import com.enrola.salesagent.conversation.Message;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * The platform API's request and response bodies. Kept apart from the JPA entities so the
 * contract with Enrola's platform doesn't move when the schema does.
 */
final class ApiModels {

	private ApiModels() {
	}

	/** The conversation state the platform needs with every response (CTO email). */
	record ConversationState(
			String status,
			boolean goalMet,
			boolean unsubscribed,
			/** True once the agent will send nothing more; the platform can stop routing to it. */
			boolean ended,
			String endReason,
			BookingView booking) {

		static ConversationState of(Conversation conversation) {
			return new ConversationState(
					conversation.getStatus().name(),
					conversation.isGoalMet(),
					conversation.isUnsubscribed(),
					conversation.getStatus().isTerminal(),
					conversation.getEndReason() == null ? null : conversation.getEndReason().name(),
					BookingView.of(conversation.getBooking()));
		}

	}

	record BookingView(Instant startTime, String advisorName) {

		static BookingView of(Booking booking) {
			return booking == null ? null : new BookingView(booking.getStartTime(), booking.getAdvisorName());
		}

	}

	/** An SMS for the platform to send to the lead. */
	record OutboundSms(String body, String templateKey) {

		static OutboundSms of(Message message) {
			return new OutboundSms(message.getBody(), message.getTemplateKey());
		}

	}

	/**
	 * @param outcome  STARTED, ALREADY_STARTED or SAME_NUMBER_OPEN (another lead with this phone
	 *                 number already has an open conversation, which this lead now shares)
	 * @param outbound the opener to send; null unless this call started the conversation
	 */
	record StartResponse(long conversationId, String outcome, OutboundSms outbound, ConversationState state) {
	}

	/**
	 * @param messageId the platform's id for this SMS; optional, but when given, retries are safe
	 * @param body      the SMS text (1600 characters is the longest concatenated SMS)
	 */
	record InboundRequest(@Size(max = 64) String messageId, @NotBlank @Size(max = 1600) String body) {
	}

	/**
	 * @param duplicate true when this messageId was already handled; nothing new happened, and
	 *                  outbound repeats the original reply
	 * @param outbound  the reply to send, or null when the agent stays silent
	 */
	record TurnResponse(long conversationId, boolean duplicate, OutboundSms outbound, ConversationState state) {
	}

	record ConversationView(long conversationId, String leadId, String agentVersion, String locale,
			ConversationState state, List<MessageView> messages) {

		static ConversationView of(Conversation conversation) {
			return new ConversationView(
					conversation.getId(),
					conversation.getLead().getExternalId(),
					conversation.getAgentVersion(),
					conversation.getLocale().toString(),
					ConversationState.of(conversation),
					conversation.getMessages().stream().map(MessageView::of).toList());
		}

	}

	record MessageView(String sender, String body, Instant at, String templateKey, String platformMessageId) {

		static MessageView of(Message message) {
			return new MessageView(message.getSender().name(), message.getBody(), message.getCreatedAt(),
					message.getTemplateKey(), message.getPlatformMessageId());
		}

	}

}
