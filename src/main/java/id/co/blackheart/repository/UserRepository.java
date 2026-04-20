package id.co.blackheart.repository;

import id.co.blackheart.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface UserRepository extends JpaRepository<User, UUID> {

    @Query(value = "SELECT * FROM users WHERE email = :email LIMIT 1", nativeQuery = true)
    Optional<User> findByEmail(@Param("email") String email);

    @Query(value = "SELECT * FROM users WHERE user_id = :userId LIMIT 1", nativeQuery = true)
    Optional<User> findByUserId(@Param("userId") UUID userId);


    boolean existsByEmail(String email);

    @Modifying
    @Query(value = """
            UPDATE users
            SET last_login_at = :loginAt, updated_time = NOW(), updated_by = :email
            WHERE user_id = :userId
            """, nativeQuery = true)
    void updateLastLogin(
            @Param("userId") UUID userId,
            @Param("email") String email,
            @Param("loginAt") LocalDateTime loginAt
    );
}
