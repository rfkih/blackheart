package id.co.blackheart.repository;


import id.co.blackheart.model.Users;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface UsersRepository extends JpaRepository<Users, Long> {
    List<Users> findByIsActive(String isActive);
    List<Users> findByIsActiveAndExchange(String isActive, String exchange);
}
