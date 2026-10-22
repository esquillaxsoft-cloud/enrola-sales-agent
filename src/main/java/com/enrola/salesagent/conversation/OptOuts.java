package com.enrola.salesagent.conversation;

import java.time.Clock;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Opting out, by keyword or (later) as flagged by the model, always goes through here, so the
 * conversation and the per-number suppression list can't disagree.
 */
@Service
public class OptOuts {

	private final OptOutRepository optOuts;
	private final Clock clock;

	OptOuts(OptOutRepository optOuts, Clock clock) {
		this.optOuts = optOuts;
		this.clock = clock;
	}

	@Transactional
	public void optOut(Conversation conversation) {
		conversation.optOut();
		String phone = conversation.getLead().getPhone();
		if (!optOuts.existsById(phone)) {
			optOuts.save(new OptOut(phone, conversation.getId(), clock.instant()));
		}
	}

	public boolean isOptedOut(String phone) {
		return optOuts.existsById(phone);
	}

	/** Testing aid for the UI's reset only. Production never forgets an opt-out. */
	@Transactional
	public void forgetForTesting(String phone) {
		optOuts.deleteById(phone);
	}

}
