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
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.UUID;

// Auth-gated submit + admin-only inbox reads (gating lives in the controller).
@Service
@RequiredArgsConstructor
@Slf4j
public class SupportMessageService {

    static final String STATUS_NEW = "NEW";
    static final String STATUS_READ = "READ";
    static final String STATUS_RESOLVED = "RESOLVED";

    private final SupportMessageRepository supportMessageRepository;
    private final UserRepository userRepository;

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

    public Page<SupportMessage> listForAdmin(String status, Pageable pageable) {
        if (!StringUtils.hasText(status)) {
            return supportMessageRepository.findAllByOrderByCreatedAtDesc(pageable);
        }
        return supportMessageRepository.findByStatusOrderByCreatedAtDesc(status.trim().toUpperCase(), pageable);
    }

    public long countNew() {
        return supportMessageRepository.countByStatus(STATUS_NEW);
    }

    // Only the first NEW → READ stamps readAt — that's the useful "first seen" datum.
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
