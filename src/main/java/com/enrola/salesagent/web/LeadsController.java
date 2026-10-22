package com.enrola.salesagent.web;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.enrola.salesagent.conversation.Conversation;
import com.enrola.salesagent.conversation.ConversationRepository;
import com.enrola.salesagent.conversation.ConversationStarter;
import com.enrola.salesagent.conversation.ConversationStarter.StartResult;
import com.enrola.salesagent.lead.Lead;
import com.enrola.salesagent.lead.LeadRepository;
import com.enrola.salesagent.lead.SampleLeads;

/** The landing page: leads, their conversations, and seeding sample leads to play with. */
@Controller
class LeadsController {

	/** A lead and the conversation it's in, which may belong to another lead with the same number. */
	record LeadRow(Lead lead, Conversation conversation, boolean sharedWithAnotherLead) {
	}

	private final LeadRepository leads;
	private final ConversationRepository conversations;
	private final ConversationStarter starter;
	private final SampleLeads samples;

	LeadsController(LeadRepository leads, ConversationRepository conversations, ConversationStarter starter,
			SampleLeads samples) {
		this.leads = leads;
		this.conversations = conversations;
		this.starter = starter;
		this.samples = samples;
	}

	@GetMapping("/")
	String index(Model model) {
		model.addAttribute("leads", leadRows());
		model.addAttribute("samples", samples.all());
		return "index";
	}

	@PostMapping("/leads")
	String seed(@RequestParam String externalId, RedirectAttributes redirect) {
		try {
			StartResult result = starter.startLead(externalId);
			redirect.addFlashAttribute("notice", describe(result));
		}
		catch (NoSuchElementException | IllegalArgumentException e) {
			redirect.addFlashAttribute("error", e.getMessage());
		}
		return "redirect:/";
	}

	private List<LeadRow> leadRows() {
		List<Conversation> all = conversations.findAllByOrderByIdDesc();
		Map<Long, Conversation> byLead = all.stream()
				.collect(Collectors.toMap(c -> c.getLead().getId(), Function.identity()));
		// Newest first, so a lead without its own conversation picks the latest one for its number.
		Map<String, Conversation> latestByPhone = all.stream()
				.collect(Collectors.toMap(c -> c.getLead().getPhone(), Function.identity(), (newer, older) -> newer));

		return leads.findAll(Sort.by(Sort.Direction.DESC, "id")).stream()
				.map(lead -> {
					Conversation own = byLead.get(lead.getId());
					return own != null
							? new LeadRow(lead, own, false)
							: new LeadRow(lead, latestByPhone.get(lead.getPhone()), true);
				})
				.toList();
	}

	private static String describe(StartResult result) {
		Lead lead = result.lead();
		Long id = result.conversation().getId();
		return switch (result.outcome()) {
			case STARTED -> "Started conversation #" + id + " with " + lead.getGivenName() + ".";
			case ALREADY_STARTED -> lead.getGivenName() + " already has conversation #" + id + ".";
			case SAME_NUMBER_OPEN -> lead.getPhone() + " already has an open conversation (#" + id
					+ "), so no new conversation was started.";
			case NUMBER_OPTED_OUT -> lead.getPhone() + " has opted out, so nothing was sent. Conversation #" + id
					+ " records " + lead.getGivenName() + " as opted out.";
		};
	}

}
