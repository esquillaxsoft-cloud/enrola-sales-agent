package com.enrola.salesagent.conversation;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ConversationRepository extends JpaRepository<Conversation, Long> {

	Optional<Conversation> findByLeadId(Long leadId);

	/** Phone numbers are stored in E.164, so this matches every lead for the same person. */
	Optional<Conversation> findFirstByLeadPhoneAndStatusInOrderByIdDesc(String phone,
			Collection<ConversationStatus> statuses);

	@EntityGraph(attributePaths = "lead")
	List<Conversation> findAllByOrderByIdDesc();

	/** Loads everything a turn or the conversation page needs in one query (open-in-view is off). */
	@EntityGraph(attributePaths = { "lead", "messages" })
	Optional<Conversation> findWithMessagesById(Long id);

}
