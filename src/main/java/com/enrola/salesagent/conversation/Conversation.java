package com.enrola.salesagent.conversation;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import com.enrola.salesagent.lead.Lead;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OneToOne;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Version;

/**
 * The agent's SMS conversation with one lead: everything the next turn needs, whether the lead
 * replies in a minute or in two days.
 */
@Entity
public class Conversation {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@OneToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "lead_id")
	private Lead lead;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private ConversationStatus status;

	@Enumerated(EnumType.STRING)
	private EndReason endReason;

	@Column(nullable = false)
	private int objectionCount;

	/** Prompt and template bundle, e.g. "comparato/v1". Pinned so a conversation never switches mid-way. */
	@Column(nullable = false)
	private String agentVersion;

	@Column(nullable = false)
	private Locale locale;

	@Embedded
	private Booking booking;

	@OneToMany(mappedBy = "conversation", cascade = CascadeType.ALL, orphanRemoval = true)
	@OrderBy("id")
	private List<Message> messages = new ArrayList<>();

	/** Optimistic lock: two turns for the same conversation can't both commit. */
	@Version
	private long version;

	@Column(nullable = false)
	private Instant createdAt;

	protected Conversation() {
		// for JPA
	}

	public Conversation(Lead lead, String agentVersion, Locale locale, Instant createdAt) {
		this.lead = lead;
		this.agentVersion = agentVersion;
		this.locale = locale;
		this.createdAt = createdAt;
		this.status = ConversationStatus.ACTIVE;
	}

	public void append(Message message) {
		message.attachTo(this);
		messages.add(message);
	}

	public void book(Booking booking) {
		this.booking = booking;
		this.status = ConversationStatus.BOOKED;
	}

	/** Reported to the platform with every turn; derived so it can't disagree with the status. */
	public boolean isGoalMet() {
		return status == ConversationStatus.BOOKED;
	}

	/** Reported to the platform with every turn; derived so it can't disagree with the status. */
	public boolean isUnsubscribed() {
		return status == ConversationStatus.OPTED_OUT;
	}

	public Long getId() {
		return id;
	}

	public Lead getLead() {
		return lead;
	}

	public ConversationStatus getStatus() {
		return status;
	}

	public EndReason getEndReason() {
		return endReason;
	}

	public int getObjectionCount() {
		return objectionCount;
	}

	public String getAgentVersion() {
		return agentVersion;
	}

	public Locale getLocale() {
		return locale;
	}

	/** Null when nothing is booked. */
	public Booking getBooking() {
		return booking;
	}

	public List<Message> getMessages() {
		return Collections.unmodifiableList(messages);
	}

	public long getVersion() {
		return version;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

}
