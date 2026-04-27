package id.co.blackheart.service.support;

import id.co.blackheart.model.SupportMessage;
import id.co.blackheart.model.User;
import id.co.blackheart.repository.SupportMessageRepository;
import id.co.blackheart.repository.UserRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Submission + admin-side read of contact-form messages. The submit path
 * is auth-gated (we always know who is writing); the admin reads are
 * gated at the controller via {@code @PreAuthorize}.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SupportMessageService {

    static final String STATUS_NEW = "NEW";
    static final String STATUS_READ = "READ";
    static final String STATUS_RESOLVED = "RESOLVED";

    private final SupportMessageRepository supportMessageRepository;
    private final UserRepository userRepository;

    /**
     * Submit a new message. Always returns the saved row so the frontend
     * can show a "thanks, ticket #abc123" confirmation.
     */
    @Transactional
    public SupportMessage submit(UUID fromUserId, String subject, String body, String diagnostic) {
        User user = userRepository.findById(fromUserId)
                .orElseThrow(() -> new EntityNotFoundException("User not found"));

        SupportMessage saved = supportMessageRepository.save(SupportMessage.builder()
                .supportMessageId(UUID.randomUUID())
                .fromUserId(fromUserId)
                .fromEmail(user.getEmail())
                .subject(subject)
                .body(body)
                .diagnostic(diagnostic)
                .status(STATUS_NEW)
                .createdAt(LocalDateTime.now())
                .build());
        log.info("Support message submitted | id={} from={}", saved.getSupportMessageId(), user.getEmail());
        return saved;
    }

    /**
     * Admin list. {@code status} is an optional filter — null returns the
     * full inbox, newest first.
     */
    public Page<SupportMessage> listForAdmin(String status, Pageable pageable) {
        if (status == null || status.isBlank()) {
            return supportMessageRepository.findAllByOrderByCreatedAtDesc(pageable);
        }
        return supportMessageRepository.findByStatusOrderByCreatedAtDesc(status.trim().toUpperCase(), pageable);
    }

    /** Unread badge count for the admin nav. */
    public long countNew() {
        return supportMessageRepository.countByStatus(STATUS_NEW);
    }

    /**
     * Transition a message to a new status. Only NEW → READ stamps
     * {@code readAt}; later transitions don't overwrite it (the original
     * "first seen" timestamp is the useful audit datum).
     */
    @Transactional
    public SupportMessage updateStatus(UUID id, String newStatus) {
        SupportMessage msg = supportMessageRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Support message not found"));
        String normalised = newStatus == null ? STATUS_READ : newStatus.trim().toUpperCase();
        if (!STATUS_NEW.equals(normalised)
                && !STATUS_READ.equals(normalised)
                && !STATUS_RESOLVED.equals(normalised)) {
            throw new IllegalArgumentException(
                    "status must be NEW / READ / RESOLVED, got: " + newStatus);
        }
        if (STATUS_NEW.equals(msg.getStatus()) && !STATUS_NEW.equals(normalised) && msg.getReadAt() == null) {
            msg.setReadAt(LocalDateTime.now());
        }
        msg.setStatus(normalised);
        return supportMessageRepository.save(msg);
    }
}
