package com.enrola.salesagent.lead;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface LeadRepository extends JpaRepository<Lead, Long> {

	Optional<Lead> findByExternalId(String externalId);

}
