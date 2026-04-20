package id.co.blackheart.service.user;

import id.co.blackheart.model.User;
import id.co.blackheart.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Database-backed Spring Security {@link UserDetailsService}.
 *
 * <p>Replaces the original {@code InMemoryUserDetailsManager}. Credentials are now
 * stored in the {@code users} table and hashed with BCrypt.
 *
 * <p>Used by:
 * <ul>
 *   <li>JWT authentication filter — to load the principal after token validation</li>
 *   <li>HTTP Basic filter — for legacy internal endpoints</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class UserDetailsServiceImpl implements UserDetailsService {

    private final UserRepository userRepository;

    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new UsernameNotFoundException("User not found: " + email));

        return org.springframework.security.core.userdetails.User.builder()
                .username(user.getEmail())
                .password(user.getPasswordHash())
                .authorities(List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole())))
                .accountExpired(false)
                .accountLocked("SUSPENDED".equals(user.getStatus()))
                .credentialsExpired(false)
                .disabled(!"ACTIVE".equals(user.getStatus()))
                .build();
    }
}
