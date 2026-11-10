ALTER TABLE auth_login_limits ADD COLUMN last_audit_at TIMESTAMPTZ;

CREATE UNIQUE INDEX reports_one_pending_report_per_reporter_target_idx
  ON reports (reporter_id, target_type, target_id)
  WHERE status = 'PENDING';
