package com.enrola.salesagent.conversation;

import java.util.Optional;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ConversationRepository extends JpaRepository<Conversation, Long> {

	Optional<Conversation> findByLeadId(Long leadId);

	/** Loads everything a turn or the conversation page needs in one query (open-in-view is off). */
	@EntityGraph(attributePaths = { "lead", "messages" })
	Optional<Conversation> findWithMessagesById(Long id);

}
