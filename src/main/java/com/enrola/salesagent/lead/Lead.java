package com.enrola.salesagent.lead;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;

/**
 * Local snapshot of a lead from Enrola's lead API. The agent works from this copy so a
 * conversation's context doesn't shift if the upstream record changes mid-conversation.
 */
@Entity
public class Lead {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false, unique = true)
	private String externalId;

	@Column(nullable = false)
	private String givenName;

	@Column(nullable = false)
	private String phone;

	private String email;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private AuState state;

	private String currentProvider;

	private String currentPremium;

	@Column(nullable = false)
	private Instant createdAt;

	protected Lead() {
		// for JPA
	}

	public Lead(String externalId, String givenName, String phone, String email, AuState state,
			String currentProvider, String currentPremium, Instant createdAt) {
		this.externalId = externalId;
		this.givenName = givenName;
		this.phone = phone;
		this.email = email;
		this.state = state;
		this.currentProvider = currentProvider;
		this.currentPremium = currentPremium;
		this.createdAt = createdAt;
	}

	public Long getId() {
		return id;
	}

	public String getExternalId() {
		return externalId;
	}

	public String getGivenName() {
		return givenName;
	}

	public String getPhone() {
		return phone;
	}

	public String getEmail() {
		return email;
	}

	public AuState getState() {
		return state;
	}

	/** Null when the lead has no current insurer. */
	public String getCurrentProvider() {
		return currentProvider;
	}

	/** Free text from Comparato, e.g. "$350-$450". Null when unknown. */
	public String getCurrentPremium() {
		return currentPremium;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

}
