package com.enrola.salesagent.web;

import java.util.NoSuchElementException;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.enrola.salesagent.agent.TurnService;
import com.enrola.salesagent.conversation.Conversation;
import com.enrola.salesagent.conversation.ConversationRepository;
import com.enrola.salesagent.conversation.ConversationStarter;

/** One conversation: read the transcript, reply as the lead, or reset and start again. */
@Controller
@RequestMapping("/conversations/{id}")
class ConversationController {

	/** Longest concatenated SMS (see the message table); anything longer couldn't arrive by SMS. */
	static final int MAX_LEAD_MESSAGE = 1600;

	private final ConversationRepository conversations;
	private final TurnService turns;
	private final ConversationStarter starter;

	ConversationController(ConversationRepository conversations, TurnService turns, ConversationStarter starter) {
		this.conversations = conversations;
		this.turns = turns;
		this.starter = starter;
	}

	@GetMapping
	String show(@PathVariable Long id, Model model) {
		Conversation conversation = conversations.findWithMessagesById(id)
				.orElseThrow(() -> new NoSuchElementException("No conversation " + id));
		model.addAttribute("conversation", conversation);
		model.addAttribute("maxLength", MAX_LEAD_MESSAGE);
		return "conversation";
	}

	@PostMapping("/messages")
	String replyAsLead(@PathVariable Long id, @RequestParam String body, RedirectAttributes redirect) {
		if (body.isBlank() || body.length() > MAX_LEAD_MESSAGE) {
			redirect.addFlashAttribute("error", "Message must be 1 to " + MAX_LEAD_MESSAGE + " characters.");
		}
		else {
			turns.handleInbound(id, body);
		}
		return "redirect:/conversations/" + id;
	}

	@PostMapping("/reset")
	String reset(@PathVariable Long id, RedirectAttributes redirect) {
		Conversation fresh = starter.restart(id);
		redirect.addFlashAttribute("notice", "Conversation reset.");
		return "redirect:/conversations/" + fresh.getId();
	}

	@ExceptionHandler(NoSuchElementException.class)
	@ResponseStatus(HttpStatus.NOT_FOUND)
	String notFound() {
		return "not-found";
	}

}
