-- =============================================================================
-- seed_admin_user.sql
-- Inserts a default ADMIN user for HTTP Basic access to internal endpoints.
--
-- IMPORTANT: Change the password before deploying to production.
-- The hash below encodes the string "admin123!" with BCrypt (rounds=12).
-- Generate a new hash:
--   In Java: new BCryptPasswordEncoder().encode("your-password")
--   In CLI:  htpasswd -bnBC 12 "" "your-password" | tr -d ':\n'
--
-- To change the password after first run, update the users table directly:
--   UPDATE users
--      SET password_hash = '<new-bcrypt-hash>', updated_time = NOW()
--    WHERE email = 'admin@blackheart.internal';
-- =============================================================================

INSERT INTO users (
    user_id, email, password_hash, full_name,
    role, status, email_verified,
    created_by, updated_by
)
VALUES (
    gen_random_uuid(),
    'admin@blackheart.internal',
    -- BCrypt hash of "admin123!" — CHANGE THIS IN PRODUCTION
    '$2a$12$K8GpCqX7rVpL3VaUTuH9.uY8ZCixHLbnJV6E5kqt4FQ0.5Rfn/eim',
    'System Administrator',
    'ADMIN',
    'ACTIVE',
    TRUE,
    'system', 'system'
)
ON CONFLICT (email) DO NOTHING;
