package id.co.blackheart.repository;

import id.co.blackheart.model.EmailVerificationToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

@Repository
public interface EmailVerificationTokenRepository extends JpaRepository<EmailVerificationToken, String> {

    /** Invalidate prior unused tokens for a user before issuing a new one. */
    @Modifying
    @Transactional
    @Query("UPDATE EmailVerificationToken t SET t.usedAt = :now "
            + "WHERE t.userId = :userId AND t.usedAt IS NULL")
    int invalidateActiveForUser(@Param("userId") UUID userId, @Param("now") LocalDateTime now);
}
