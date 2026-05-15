-- Seed the singleton research_control row that V23 (no-op baseline) omitted.
-- ON CONFLICT DO NOTHING is safe for re-runs and existing installs.
INSERT INTO research_control (control_id, paused, reason, updated_at)
VALUES (1, FALSE, NULL, NOW())
ON CONFLICT (control_id) DO NOTHING;
