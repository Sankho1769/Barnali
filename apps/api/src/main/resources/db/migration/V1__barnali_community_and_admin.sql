CREATE TABLE users (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  username VARCHAR(32) NOT NULL UNIQUE CHECK (username = lower(username)),
  display_name VARCHAR(80) NOT NULL,
  email VARCHAR(254) NOT NULL UNIQUE CHECK (email = lower(email)),
  password_hash VARCHAR(100) NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'SUSPENDED')),
  email_verified_at TIMESTAMPTZ,
  must_change_password BOOLEAN NOT NULL DEFAULT FALSE,
  auth_version BIGINT NOT NULL DEFAULT 0,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  last_login_at TIMESTAMPTZ
);
CREATE INDEX users_created_at_idx ON users (created_at DESC);
CREATE INDEX users_status_idx ON users (status);

CREATE TABLE rooms (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  slug VARCHAR(120) NOT NULL UNIQUE CHECK (slug = lower(slug)),
  name VARCHAR(120) NOT NULL,
  description VARCHAR(600) NOT NULL,
  is_public BOOLEAN NOT NULL DEFAULT TRUE,
  is_active BOOLEAN NOT NULL DEFAULT TRUE,
  created_by UUID REFERENCES users(id) ON DELETE SET NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX rooms_active_public_idx ON rooms (is_active, is_public, name);

CREATE TABLE room_memberships (
  room_id UUID NOT NULL REFERENCES rooms(id) ON DELETE CASCADE,
  user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  joined_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (room_id, user_id)
);
CREATE INDEX room_memberships_user_idx ON room_memberships (user_id, joined_at DESC);

CREATE TABLE discussions (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  slug VARCHAR(180) NOT NULL UNIQUE CHECK (slug = lower(slug)),
  room_id UUID NOT NULL REFERENCES rooms(id) ON DELETE RESTRICT,
  author_id UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
  title VARCHAR(180) NOT NULL,
  body TEXT NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'PUBLISHED' CHECK (status IN ('PUBLISHED', 'HIDDEN', 'REMOVED')),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  published_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX discussions_public_created_idx ON discussions (status, published_at DESC, id DESC);
CREATE INDEX discussions_room_created_idx ON discussions (room_id, status, published_at DESC);
CREATE INDEX discussions_author_created_idx ON discussions (author_id, created_at DESC);
CREATE INDEX discussions_search_idx ON discussions USING GIN (to_tsvector('simple', title || ' ' || body));

CREATE TABLE replies (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  discussion_id UUID NOT NULL REFERENCES discussions(id) ON DELETE CASCADE,
  author_id UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
  body TEXT NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'PUBLISHED' CHECK (status IN ('PUBLISHED', 'HIDDEN', 'REMOVED')),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX replies_discussion_created_idx ON replies (discussion_id, status, created_at ASC);
CREATE INDEX replies_author_created_idx ON replies (author_id, created_at DESC);

CREATE TABLE discussion_votes (
  discussion_id UUID NOT NULL REFERENCES discussions(id) ON DELETE CASCADE,
  user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  value SMALLINT NOT NULL CHECK (value IN (-1, 1)),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (discussion_id, user_id)
);

CREATE TABLE reports (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  reporter_id UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
  target_type VARCHAR(16) NOT NULL CHECK (target_type IN ('DISCUSSION', 'REPLY', 'USER')),
  target_id UUID NOT NULL,
  reason VARCHAR(40) NOT NULL,
  details VARCHAR(1000),
  status VARCHAR(16) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'RESOLVED', 'DISMISSED')),
  reviewer_id UUID REFERENCES users(id) ON DELETE SET NULL,
  resolution VARCHAR(600),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  resolved_at TIMESTAMPTZ
);
CREATE INDEX reports_status_created_idx ON reports (status, created_at DESC);
CREATE INDEX reports_target_idx ON reports (target_type, target_id);

CREATE TABLE user_activity_events (
  id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  event_type VARCHAR(24) NOT NULL CHECK (event_type IN ('LOGIN', 'ROOM_JOIN', 'DISCUSSION_CREATE', 'REPLY_CREATE', 'VOTE')),
  occurred_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX activity_user_time_idx ON user_activity_events (user_id, occurred_at DESC);
CREATE INDEX activity_time_type_idx ON user_activity_events (occurred_at DESC, event_type);

CREATE TABLE admin_accounts (
  user_id UUID PRIMARY KEY REFERENCES users(id) ON DELETE RESTRICT,
  role VARCHAR(24) NOT NULL CHECK (role IN ('OWNER', 'SUPER_ADMIN', 'MODERATOR', 'ANALYTICS_VIEWER', 'SUPPORT_ADMIN')),
  active BOOLEAN NOT NULL DEFAULT TRUE,
  invited_by UUID REFERENCES users(id) ON DELETE SET NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  last_used_at TIMESTAMPTZ
);
CREATE UNIQUE INDEX admin_accounts_single_owner_idx ON admin_accounts (role) WHERE role = 'OWNER';
CREATE INDEX admin_accounts_active_role_idx ON admin_accounts (active, role);

CREATE TABLE admin_bootstrap_guard (
  id SMALLINT PRIMARY KEY CHECK (id = 1)
);
INSERT INTO admin_bootstrap_guard(id) VALUES (1);

CREATE TABLE admin_invitations (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  email VARCHAR(254) NOT NULL CHECK (email = lower(email)),
  role VARCHAR(24) NOT NULL CHECK (role IN ('SUPER_ADMIN', 'MODERATOR', 'ANALYTICS_VIEWER', 'SUPPORT_ADMIN')),
  token_hash CHAR(64) NOT NULL UNIQUE,
  invited_by UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
  expires_at TIMESTAMPTZ NOT NULL,
  accepted_at TIMESTAMPTZ,
  revoked_at TIMESTAMPTZ,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX admin_invitations_email_created_idx ON admin_invitations (email, created_at DESC);

CREATE TABLE admin_audit_events (
  id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  actor_user_id UUID REFERENCES users(id) ON DELETE SET NULL,
  action VARCHAR(80) NOT NULL,
  target_type VARCHAR(40) NOT NULL,
  target_id VARCHAR(100),
  outcome VARCHAR(16) NOT NULL CHECK (outcome IN ('SUCCESS', 'FAILURE', 'DENIED')),
  reason VARCHAR(600),
  request_id VARCHAR(100),
  context JSONB NOT NULL DEFAULT '{}'::jsonb,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX admin_audit_created_idx ON admin_audit_events (created_at DESC, id DESC);
CREATE INDEX admin_audit_actor_idx ON admin_audit_events (actor_user_id, created_at DESC);
CREATE INDEX admin_audit_action_idx ON admin_audit_events (action, created_at DESC);

CREATE FUNCTION barnali_reject_audit_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION 'admin_audit_events is append-only';
END;
$$;
CREATE TRIGGER admin_audit_events_no_update BEFORE UPDATE OR DELETE ON admin_audit_events
  FOR EACH ROW EXECUTE FUNCTION barnali_reject_audit_mutation();

CREATE TABLE auth_login_limits (
  subject_hash CHAR(64) PRIMARY KEY,
  failure_count INTEGER NOT NULL DEFAULT 0 CHECK (failure_count >= 0),
  window_started_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  blocked_until TIMESTAMPTZ,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX auth_login_limits_updated_idx ON auth_login_limits (updated_at);

CREATE TABLE password_reset_tokens (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  token_hash CHAR(64) NOT NULL UNIQUE,
  expires_at TIMESTAMPTZ NOT NULL,
  consumed_at TIMESTAMPTZ,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX password_reset_tokens_expiry_idx ON password_reset_tokens (expires_at);
