package com.enrola.salesagent.api;

import java.util.NoSuchElementException;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.enrola.salesagent.agent.AgentUnavailableException;
import com.enrola.salesagent.agent.TurnService;
import com.enrola.salesagent.agent.TurnService.TurnResult;
import com.enrola.salesagent.api.ApiModels.ConversationState;
import com.enrola.salesagent.api.ApiModels.ConversationView;
import com.enrola.salesagent.api.ApiModels.InboundRequest;
import com.enrola.salesagent.api.ApiModels.OutboundSms;
import com.enrola.salesagent.api.ApiModels.StartResponse;
import com.enrola.salesagent.api.ApiModels.TurnResponse;
import com.enrola.salesagent.conversation.ConversationRepository;
import com.enrola.salesagent.conversation.ConversationStarter;
import com.enrola.salesagent.conversation.ConversationStarter.Outcome;
import com.enrola.salesagent.conversation.ConversationStarter.StartResult;

import jakarta.validation.Valid;

/**
 * What Enrola's platform calls. The platform owns SMS transport and scheduling; the agent owns
 * what to say and the conversation state. Every response carries that state.
 *
 * <p>Inbound SMS are addressed by conversation id, which the platform gets back from the start
 * call. Unauthenticated: this is a local prototype (README §4).
 */
@RestController
@RequestMapping("/api")
class PlatformApiController {

	private final ConversationStarter starter;
	private final TurnService turns;
	private final ConversationRepository conversations;

	PlatformApiController(ConversationStarter starter, TurnService turns, ConversationRepository conversations) {
		this.starter = starter;
		this.turns = turns;
		this.conversations = conversations;
	}

	/** Start the agent's conversation with a lead. 201 when started, 200 when one already exists. */
	@PostMapping("/leads/{leadId}/conversation")
	ResponseEntity<StartResponse> start(@PathVariable String leadId) {
		StartResult result = starter.startLead(leadId);
		var body = new StartResponse(
				result.conversation().getId(),
				result.outcome().name(),
				result.messageToSend().map(OutboundSms::of).orElse(null),
				ConversationState.of(result.conversation()));
		return ResponseEntity.status(result.outcome() == Outcome.STARTED ? HttpStatus.CREATED : HttpStatus.OK)
				.body(body);
	}

	/** An SMS from the lead. Returns the agent's reply (if any) and the new state. */
	@PostMapping("/conversations/{id}/inbound")
	TurnResponse inbound(@PathVariable Long id, @Valid @RequestBody InboundRequest request) {
		TurnResult result = turns.handleInbound(id, request.messageId(), request.body());
		return new TurnResponse(
				result.conversation().getId(),
				result.duplicate(),
				result.reply().map(OutboundSms::of).orElse(null),
				ConversationState.of(result.conversation()));
	}

	/** The full conversation, for support and debugging. */
	@GetMapping("/conversations/{id}")
	ConversationView get(@PathVariable Long id) {
		return conversations.findWithMessagesById(id)
				.map(ConversationView::of)
				.orElseThrow(() -> new NoSuchElementException("No conversation " + id));
	}

	@ExceptionHandler(NoSuchElementException.class)
	ProblemDetail notFound(NoSuchElementException e) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
	}

	/** The lead API returned data we can't use, e.g. an invalid phone number. */
	@ExceptionHandler(IllegalArgumentException.class)
	ProblemDetail unusableLead(IllegalArgumentException e) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_CONTENT, e.getMessage());
	}

	/** Two requests for the same thing raced (e.g. a retried webhook, two turns at once); retrying is safe. */
	@ExceptionHandler({ DataIntegrityViolationException.class, ObjectOptimisticLockingFailureException.class })
	ProblemDetail conflict() {
		return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "Concurrent request for the same resource; retry");
	}

	/** The model failed. The lead's SMS is stored; retrying with the same messageId resumes the turn. */
	@ExceptionHandler(AgentUnavailableException.class)
	ProblemDetail agentUnavailable(AgentUnavailableException e) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
	}

}
