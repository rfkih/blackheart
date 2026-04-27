package id.co.blackheart.repository;

import id.co.blackheart.model.PasswordResetToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

@Repository
public interface PasswordResetTokenRepository extends JpaRepository<PasswordResetToken, String> {

    /**
     * Invalidate any prior unused tokens for a user before issuing a new
     * one. Keeps "request reset twice" from leaving multiple live tokens
     * in flight — only the most recent request can complete the reset.
     */
    @Modifying
    @Transactional
    @Query("UPDATE PasswordResetToken t SET t.usedAt = :now "
            + "WHERE t.userId = :userId AND t.usedAt IS NULL")
    int invalidateActiveForUser(@Param("userId") UUID userId, @Param("now") LocalDateTime now);
}
