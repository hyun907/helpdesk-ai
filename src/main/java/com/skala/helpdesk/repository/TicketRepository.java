package com.skala.helpdesk.repository;

import com.skala.helpdesk.domain.Ticket;
import com.skala.helpdesk.domain.TicketStatus;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TicketRepository extends JpaRepository<Ticket, String> {

    Optional<Ticket> findByNoAndOwnerId(String no, String ownerId);

    List<Ticket> findByStatusOrderByCreatedAtAsc(TicketStatus status);

    List<Ticket> findByOwnerIdOrderByCreatedAtDesc(String ownerId);
}
