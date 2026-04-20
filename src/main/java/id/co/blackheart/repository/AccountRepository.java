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
}
