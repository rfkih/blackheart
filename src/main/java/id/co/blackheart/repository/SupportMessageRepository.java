package id.co.blackheart.repository;

import id.co.blackheart.model.SupportMessage;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface SupportMessageRepository extends JpaRepository<SupportMessage, UUID> {

    /** Admin inbox view — every message, newest first. */
    Page<SupportMessage> findAllByOrderByCreatedAtDesc(Pageable pageable);

    /** Filtered admin views — by status (NEW / READ / RESOLVED), newest first. */
    Page<SupportMessage> findByStatusOrderByCreatedAtDesc(String status, Pageable pageable);

    /** Unread badge count on the admin inbox link. */
    long countByStatus(String status);
}
