package id.co.blackheart.repository;


import id.co.blackheart.model.Account;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface AccountRepository extends JpaRepository<Account, Long> {
    @Query(
            value = "SELECT * FROM accounts WHERE is_active = :isActive",
            nativeQuery = true
    )
    List<Account> findByIsActive(@Param("isActive") String isActive);

    @Query(value = """
    SELECT a.*
    FROM accounts a
    WHERE a.account_id = :accountId
    """, nativeQuery = true)
    Optional<Account> findByAccountId(@Param("accountId") UUID accountId);

    @Query(value = """
    SELECT a.*
    FROM accounts a
    WHERE a.user_id = :userId
    ORDER BY a.created_time ASC
    """, nativeQuery = true)
    List<Account> findByUserId(@Param("userId") UUID userId);

    /**
     * Per-user case-insensitive existence check for username. Per-user (not
     * global) since V51 - different tenants may choose the same label, but
     * within one user the label is unique. Used by account create/update
     * for friendly 409s rather than constraint-violation stack traces.
     */
    @Query(value = """
    SELECT EXISTS (
        SELECT 1
        FROM accounts
        WHERE user_id = :userId
          AND LOWER(username) = LOWER(:username)
    )
    """, nativeQuery = true)
    boolean existsByUserIdAndUsernameIgnoreCase(
            @Param("userId") UUID userId,
            @Param("username") String username);

    /**
     * Same as above but excludes a specific account id - used during rename
     * so the caller's own existing label doesn't trip the uniqueness check.
     */
    @Query(value = """
    SELECT EXISTS (
        SELECT 1
        FROM accounts
        WHERE user_id = :userId
          AND LOWER(username) = LOWER(:username)
          AND account_id <> :excludeAccountId
    )
    """, nativeQuery = true)
    boolean existsByUserIdAndUsernameIgnoreCaseExcludingId(
            @Param("userId") UUID userId,
            @Param("username") String username,
            @Param("excludeAccountId") UUID excludeAccountId);
}
