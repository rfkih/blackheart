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
    List<Account> findByIsActive(String isActive);
    @Query(value = """
    SELECT a.*
    FROM accounts a
    WHERE a.account_id = :accountId
    """, nativeQuery = true)
    Optional<Account> findByAccountId(@Param("accountId") UUID accountId);
}
