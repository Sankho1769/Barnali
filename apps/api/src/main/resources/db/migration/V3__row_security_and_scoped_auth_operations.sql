-- BARNALI access boundary. V1/V2 remain immutable. Apply only after roles named
-- below have been provisioned and the read-only migration preflight passes.
-- Do not FORCE RLS: table ownership remains with the offline migration role so
-- its fixed-search-path SECURITY DEFINER routines can perform narrowly scoped
-- pre-authentication and atomic operations. The API uses barnali_runtime only.

DO $roles$
DECLARE
  runtime_oid oid;
  migrator_oid oid;
  provisioner_oid oid;
BEGIN
  SELECT oid INTO runtime_oid FROM pg_catalog.pg_roles WHERE rolname = 'barnali_runtime';
  SELECT oid INTO migrator_oid FROM pg_catalog.pg_roles WHERE rolname = 'barnali_migrator';
  SELECT oid INTO provisioner_oid FROM pg_catalog.pg_roles WHERE rolname = 'barnali_provisioner';
  IF runtime_oid IS NULL OR migrator_oid IS NULL OR provisioner_oid IS NULL THEN
    RAISE EXCEPTION 'Required BARNALI database roles are not provisioned';
  END IF;
  IF current_user <> 'barnali_migrator'
     OR NOT EXISTS (SELECT 1 FROM pg_catalog.pg_namespace WHERE nspname='barnali' AND nspowner=migrator_oid) THEN
    RAISE EXCEPTION 'Migrations must run as the barnali schema owner';
  END IF;
  IF runtime_oid=migrator_oid OR provisioner_oid=migrator_oid OR runtime_oid=provisioner_oid THEN
    RAISE EXCEPTION 'Runtime, migration, and owner-provisioner roles must be separate';
  END IF;
  IF EXISTS (
       SELECT 1 FROM pg_catalog.pg_roles
       WHERE oid IN (runtime_oid, migrator_oid, provisioner_oid)
         AND (rolsuper OR rolbypassrls OR rolcreatedb OR rolcreaterole)
     ) THEN
    RAISE EXCEPTION 'BARNALI application roles must not have administrative role attributes';
  END IF;
  IF pg_catalog.pg_has_role(runtime_oid, migrator_oid, 'MEMBER')
     OR pg_catalog.pg_has_role(provisioner_oid, migrator_oid, 'MEMBER')
     OR EXISTS (SELECT 1 FROM pg_catalog.pg_auth_members WHERE member IN (runtime_oid, migrator_oid, provisioner_oid)) THEN
    RAISE EXCEPTION 'BARNALI application roles must not inherit other role memberships';
  END IF;
  IF pg_catalog.has_database_privilege(runtime_oid,current_database(),'CREATE')
     OR pg_catalog.has_database_privilege(provisioner_oid,current_database(),'CREATE')
     OR pg_catalog.has_database_privilege(migrator_oid,current_database(),'CREATE')
     OR pg_catalog.has_schema_privilege(runtime_oid,'barnali','CREATE')
     OR pg_catalog.has_schema_privilege(provisioner_oid,'barnali','CREATE')
     OR EXISTS (SELECT 1 FROM pg_catalog.pg_namespace other_schema
       WHERE other_schema.nspname<>'barnali' AND other_schema.nspname<>'information_schema'
         AND other_schema.nspname !~ '^pg_'
          AND (pg_catalog.has_schema_privilege(migrator_oid,other_schema.oid,'CREATE')
            OR pg_catalog.has_schema_privilege(runtime_oid,other_schema.oid,'CREATE')
           OR pg_catalog.has_schema_privilege(provisioner_oid,other_schema.oid,'CREATE'))) THEN
    RAISE EXCEPTION 'Migration principal may create only in barnali; runtime and provisioner must have no DDL privileges';
  END IF;
END
$roles$;

-- Pin V3's unqualified objects to the private schema even if the caller's
-- ambient search_path is broader than the validated Flyway configuration.
SET search_path TO barnali, pg_catalog;

ALTER TABLE admin_bootstrap_guard ADD COLUMN consumed_at TIMESTAMPTZ;

CREATE FUNCTION current_actor_id() RETURNS UUID
LANGUAGE sql STABLE
AS $$
  SELECT CASE
    WHEN NULLIF(current_setting('barnali.user_id', true), '')
         ~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
    THEN NULLIF(current_setting('barnali.user_id', true), '')::uuid
    ELSE NULL
  END
$$;

CREATE FUNCTION actor_has_permission(p_permission TEXT) RETURNS BOOLEAN
LANGUAGE sql STABLE SECURITY DEFINER
SET search_path = pg_catalog, barnali, pg_temp
AS $$
  SELECT CASE a.role
    WHEN 'OWNER' THEN p_permission IN ('OVERVIEW_READ','ANALYTICS_READ','USER_READ','USER_SUSPEND','ROOM_READ','ROOM_MANAGE','REPORT_READ','REPORT_RESOLVE','ADMIN_TEAM_READ','ADMIN_TEAM_MANAGE','AUDIT_READ')
    WHEN 'SUPER_ADMIN' THEN p_permission IN ('OVERVIEW_READ','ANALYTICS_READ','USER_READ','USER_SUSPEND','ROOM_READ','ROOM_MANAGE','REPORT_READ','REPORT_RESOLVE','ADMIN_TEAM_READ','AUDIT_READ')
    WHEN 'MODERATOR' THEN p_permission IN ('OVERVIEW_READ','USER_READ','USER_SUSPEND','ROOM_READ','REPORT_READ','REPORT_RESOLVE','AUDIT_READ')
    WHEN 'ANALYTICS_VIEWER' THEN p_permission IN ('OVERVIEW_READ','ANALYTICS_READ','ROOM_READ')
    WHEN 'SUPPORT_ADMIN' THEN p_permission IN ('OVERVIEW_READ','USER_READ','AUDIT_READ')
    ELSE FALSE
  END
  FROM barnali.admin_accounts a
  JOIN barnali.users u ON u.id = a.user_id
  WHERE a.user_id = barnali.current_actor_id()
    AND a.active
    AND u.status = 'ACTIVE'
    AND NOT u.must_change_password
$$;

CREATE FUNCTION is_admin_account(p_user_id UUID) RETURNS BOOLEAN
LANGUAGE sql STABLE SECURITY DEFINER
SET search_path = pg_catalog, barnali, pg_temp
AS $$ SELECT EXISTS (SELECT 1 FROM barnali.admin_accounts WHERE user_id=p_user_id) $$;

CREATE FUNCTION actor_is_active() RETURNS BOOLEAN
LANGUAGE sql STABLE SECURITY DEFINER
SET search_path = pg_catalog, barnali, pg_temp
AS $$
  SELECT EXISTS (SELECT 1 FROM barnali.users
    WHERE id=barnali.current_actor_id() AND status='ACTIVE')
$$;

CREATE FUNCTION is_public_author(p_user_id UUID) RETURNS BOOLEAN
LANGUAGE sql STABLE SECURITY DEFINER
SET search_path = pg_catalog, barnali, pg_temp
AS $$
  SELECT EXISTS (
    SELECT 1 FROM barnali.discussions d JOIN barnali.rooms r ON r.id=d.room_id
    JOIN barnali.users u ON u.id=d.author_id
    WHERE d.author_id=p_user_id AND u.status='ACTIVE' AND d.status='PUBLISHED' AND r.is_active AND r.is_public
  ) OR EXISTS (
    SELECT 1 FROM barnali.replies rp
    JOIN barnali.discussions d ON d.id=rp.discussion_id
    JOIN barnali.rooms r ON r.id=d.room_id
    JOIN barnali.users u ON u.id=rp.author_id
    WHERE rp.author_id=p_user_id AND u.status='ACTIVE' AND rp.status='PUBLISHED'
      AND d.status='PUBLISHED' AND r.is_active AND r.is_public
  )
$$;

CREATE FUNCTION auth_principal_by_email(p_email TEXT)
RETURNS TABLE(id UUID, email TEXT, username TEXT, display_name TEXT, password_hash TEXT,
              status TEXT, must_change_password BOOLEAN, auth_version BIGINT)
LANGUAGE sql STABLE SECURITY DEFINER
SET search_path = pg_catalog, barnali, pg_temp
AS $$
  SELECT u.id, u.email, u.username, u.display_name, u.password_hash,
         u.status, u.must_change_password, u.auth_version
  FROM barnali.users u
  WHERE u.email = pg_catalog.lower(pg_catalog.btrim(p_email))
$$;

CREATE FUNCTION register_user(p_display_name TEXT, p_username TEXT, p_email TEXT, p_password_hash TEXT)
RETURNS BOOLEAN
LANGUAGE plpgsql SECURITY DEFINER
SET search_path = pg_catalog, barnali, pg_temp
AS $$
BEGIN
  IF p_display_name IS NULL OR pg_catalog.length(pg_catalog.btrim(p_display_name)) NOT BETWEEN 1 AND 80
     OR p_username IS NULL OR p_username !~ '^[a-z0-9_]{3,32}$'
     OR p_email IS NULL OR p_email <> pg_catalog.lower(pg_catalog.btrim(p_email))
     OR pg_catalog.length(p_email) > 254 OR p_email !~ '^[^[:space:]@]+@[^[:space:]@]+[.][^[:space:]@]+$'
     OR p_password_hash IS NULL OR p_password_hash !~ '^\$2[aby]\$[0-9]{2}\$[./A-Za-z0-9]{53}$' THEN
    RAISE EXCEPTION 'Invalid registration input';
  END IF;
  INSERT INTO barnali.users(display_name, username, email, password_hash)
  VALUES (pg_catalog.btrim(p_display_name), p_username, p_email, p_password_hash);
  RETURN TRUE;
END
$$;

CREATE FUNCTION current_session_user()
RETURNS TABLE(id UUID, username TEXT, display_name TEXT, email TEXT, must_change_password BOOLEAN, admin_role TEXT)
LANGUAGE sql STABLE SECURITY DEFINER
SET search_path = pg_catalog, barnali, pg_temp
AS $$
  SELECT u.id, u.username, u.display_name, u.email, u.must_change_password,
         CASE WHEN a.active AND NOT u.must_change_password THEN a.role ELSE NULL END
  FROM barnali.users u LEFT JOIN barnali.admin_accounts a ON a.user_id=u.id
  WHERE u.id=barnali.current_actor_id() AND u.status='ACTIVE'
$$;

CREATE FUNCTION record_successful_login() RETURNS BOOLEAN
LANGUAGE plpgsql SECURITY DEFINER
SET search_path = pg_catalog, barnali, pg_temp
AS $$
DECLARE actor_id UUID := barnali.current_actor_id();
BEGIN
  IF actor_id IS NULL THEN RAISE EXCEPTION 'Authenticated transaction identity required'; END IF;
  UPDATE barnali.users SET last_login_at=pg_catalog.now(), updated_at=pg_catalog.now()
    WHERE id=actor_id AND status='ACTIVE';
  IF NOT FOUND THEN RAISE EXCEPTION 'Active account required'; END IF;
  INSERT INTO barnali.user_activity_events(user_id,event_type) VALUES (actor_id,'LOGIN');
  UPDATE barnali.admin_accounts SET last_used_at=pg_catalog.now() WHERE user_id=actor_id AND active;
  RETURN TRUE;
END
$$;

CREATE FUNCTION current_password_hash() RETURNS TEXT
LANGUAGE sql STABLE SECURITY DEFINER
SET search_path = pg_catalog, barnali, pg_temp
AS $$
  SELECT password_hash FROM barnali.users
  WHERE id=barnali.current_actor_id() AND status='ACTIVE'
$$;

CREATE FUNCTION change_current_password(p_password_hash TEXT, p_request_id TEXT) RETURNS BOOLEAN
LANGUAGE plpgsql SECURITY DEFINER
SET search_path = pg_catalog, barnali, pg_temp
AS $$
DECLARE actor_id UUID := barnali.current_actor_id();
BEGIN
  IF actor_id IS NULL OR NOT barnali.actor_is_active() OR p_password_hash IS NULL
     OR p_password_hash !~ '^\$2[aby]\$[0-9]{2}\$[./A-Za-z0-9]{53}$' THEN
    RAISE EXCEPTION 'Authenticated password change required';
  END IF;
  UPDATE barnali.users SET password_hash=p_password_hash, must_change_password=FALSE,
      auth_version=auth_version+1, updated_at=pg_catalog.now()
    WHERE id=actor_id AND status='ACTIVE';
  IF NOT FOUND THEN RAISE EXCEPTION 'Active account required'; END IF;
  -- A password change invalidates any recovery link issued before it.
  UPDATE barnali.password_reset_tokens SET consumed_at=pg_catalog.now()
    WHERE user_id=actor_id AND consumed_at IS NULL;
  INSERT INTO barnali.admin_audit_events(actor_user_id,action,target_type,target_id,outcome,request_id)
  VALUES (actor_id,'PASSWORD_CHANGED','user',actor_id::text,'SUCCESS',pg_catalog.left(p_request_id,100));
  RETURN TRUE;
END
$$;

CREATE FUNCTION login_limit_blocked(p_identity_hash TEXT, p_address_hash TEXT) RETURNS BOOLEAN
LANGUAGE sql STABLE SECURITY DEFINER
SET search_path = pg_catalog, barnali, pg_temp
AS $$
  SELECT CASE
    WHEN p_identity_hash IS NULL OR p_address_hash IS NULL
      OR p_identity_hash !~ '^[0-9a-f]{64}$' OR p_address_hash !~ '^[0-9a-f]{64}$' THEN TRUE
    ELSE EXISTS (SELECT 1 FROM barnali.auth_login_limits
                 WHERE subject_hash IN (p_identity_hash,p_address_hash) AND blocked_until > pg_catalog.now())
  END
$$;

CREATE FUNCTION login_limit_record_attempt(p_identity_hash TEXT, p_address_hash TEXT) RETURNS BOOLEAN
LANGUAGE plpgsql SECURITY DEFINER
SET search_path = pg_catalog, barnali, pg_temp
AS $$
DECLARE subject TEXT;
BEGIN
  IF p_identity_hash IS NULL OR p_address_hash IS NULL
     OR p_identity_hash !~ '^[0-9a-f]{64}$' OR p_address_hash !~ '^[0-9a-f]{64}$' THEN
    RAISE EXCEPTION 'Invalid login limit key';
  END IF;
  FOREACH subject IN ARRAY ARRAY[p_identity_hash,p_address_hash] LOOP
    INSERT INTO barnali.auth_login_limits(subject_hash,failure_count,window_started_at,blocked_until,updated_at)
    VALUES (subject,1,pg_catalog.now(),NULL,pg_catalog.now())
    ON CONFLICT (subject_hash) DO UPDATE SET
      failure_count=CASE WHEN barnali.auth_login_limits.window_started_at < pg_catalog.now()-interval '15 minutes'
        THEN 1 ELSE barnali.auth_login_limits.failure_count+1 END,
      window_started_at=CASE WHEN barnali.auth_login_limits.window_started_at < pg_catalog.now()-interval '15 minutes'
        THEN pg_catalog.now() ELSE barnali.auth_login_limits.window_started_at END,
      blocked_until=CASE WHEN barnali.auth_login_limits.window_started_at < pg_catalog.now()-interval '15 minutes' THEN NULL
        WHEN barnali.auth_login_limits.failure_count+1 >= 7 THEN pg_catalog.now()+interval '15 minutes'
        ELSE barnali.auth_login_limits.blocked_until END,
      updated_at=pg_catalog.now();
  END LOOP;
  RETURN TRUE;
END
$$;

CREATE FUNCTION login_limit_clear(p_identity_hash TEXT) RETURNS BOOLEAN
LANGUAGE plpgsql SECURITY DEFINER
SET search_path = pg_catalog, barnali, pg_temp
AS $$
BEGIN
  IF p_identity_hash IS NULL OR p_identity_hash !~ '^[0-9a-f]{64}$' THEN RAISE EXCEPTION 'Invalid login limit key'; END IF;
  DELETE FROM barnali.auth_login_limits WHERE subject_hash=p_identity_hash;
  RETURN TRUE;
END
$$;

CREATE FUNCTION login_limit_audit_address(p_address_hash TEXT) RETURNS BOOLEAN
LANGUAGE plpgsql SECURITY DEFINER
SET search_path = pg_catalog, barnali, pg_temp
AS $$
DECLARE affected INTEGER;
BEGIN
  IF p_address_hash IS NULL OR p_address_hash !~ '^[0-9a-f]{64}$' THEN RAISE EXCEPTION 'Invalid login limit key'; END IF;
  UPDATE barnali.auth_login_limits SET last_audit_at=pg_catalog.now(),updated_at=pg_catalog.now()
    WHERE subject_hash=p_address_hash AND (last_audit_at IS NULL OR last_audit_at < pg_catalog.now()-interval '15 minutes');
  GET DIAGNOSTICS affected=ROW_COUNT;
  RETURN affected > 0;
END
$$;

CREATE FUNCTION login_limit_prune() RETURNS INTEGER
LANGUAGE plpgsql SECURITY DEFINER
SET search_path = pg_catalog, barnali, pg_temp
AS $$
DECLARE affected INTEGER;
BEGIN
  DELETE FROM barnali.auth_login_limits WHERE updated_at < pg_catalog.now()-interval '2 days';
  GET DIAGNOSTICS affected=ROW_COUNT;
  RETURN affected;
END
$$;

CREATE FUNCTION issue_password_reset(p_email TEXT, p_token_hash TEXT) RETURNS BOOLEAN
LANGUAGE plpgsql SECURITY DEFINER
SET search_path = pg_catalog, barnali, pg_temp
AS $$
DECLARE target_id UUID;
BEGIN
  IF p_token_hash IS NULL OR p_email IS NULL OR p_token_hash !~ '^[0-9a-f]{64}$'
     OR pg_catalog.length(p_email)>254 THEN RAISE EXCEPTION 'Invalid recovery request'; END IF;
  -- Serialize reset issuance with other issuance/completion for this user.
  SELECT id INTO target_id FROM barnali.users
    WHERE email=pg_catalog.lower(pg_catalog.btrim(p_email)) AND status='ACTIVE' FOR UPDATE;
  IF target_id IS NULL THEN RETURN FALSE; END IF;
  UPDATE barnali.password_reset_tokens SET consumed_at=pg_catalog.now()
    WHERE user_id=target_id AND consumed_at IS NULL;
  INSERT INTO barnali.password_reset_tokens(user_id,token_hash,expires_at)
    VALUES (target_id,p_token_hash,pg_catalog.now()+interval '30 minutes');
  RETURN TRUE;
END
$$;

CREATE FUNCTION invalidate_password_reset_delivery(p_token_hash TEXT) RETURNS BOOLEAN
LANGUAGE plpgsql SECURITY DEFINER
SET search_path = pg_catalog, barnali, pg_temp
AS $$
BEGIN
  IF p_token_hash IS NULL OR p_token_hash !~ '^[0-9a-f]{64}$' THEN RAISE EXCEPTION 'Invalid recovery request'; END IF;
  UPDATE barnali.password_reset_tokens SET consumed_at=pg_catalog.now()
    WHERE token_hash=p_token_hash AND consumed_at IS NULL;
  RETURN TRUE;
END
$$;

CREATE FUNCTION complete_password_reset(p_token_hash TEXT, p_password_hash TEXT, p_request_id TEXT) RETURNS BOOLEAN
LANGUAGE plpgsql SECURITY DEFINER
SET search_path = pg_catalog, barnali, pg_temp
AS $$
DECLARE target_id UUID;
BEGIN
  IF p_token_hash IS NULL OR p_token_hash !~ '^[0-9a-f]{64}$' OR p_password_hash IS NULL
     OR p_password_hash !~ '^\$2[aby]\$[0-9]{2}\$[./A-Za-z0-9]{53}$' THEN RAISE EXCEPTION 'Invalid recovery request'; END IF;
  -- Resolve the user first, then take locks in user->token order. Issuance and
  -- password changes use the same order, avoiding stale parallel reset links.
  SELECT user_id INTO target_id FROM barnali.password_reset_tokens
    WHERE token_hash=p_token_hash AND consumed_at IS NULL AND expires_at>pg_catalog.now();
  IF target_id IS NULL THEN RETURN FALSE; END IF;
  PERFORM 1 FROM barnali.users WHERE id=target_id FOR UPDATE;
  IF NOT FOUND THEN
    UPDATE barnali.password_reset_tokens SET consumed_at=pg_catalog.now()
      WHERE token_hash=p_token_hash AND user_id=target_id AND consumed_at IS NULL;
    RETURN FALSE;
  END IF;
  -- Re-check after waiting for the user lock: another reset may have consumed it.
  SELECT user_id INTO target_id FROM barnali.password_reset_tokens
    WHERE token_hash=p_token_hash AND consumed_at IS NULL AND expires_at>pg_catalog.now()
    FOR UPDATE;
  IF target_id IS NULL THEN RETURN FALSE; END IF;
  UPDATE barnali.users SET password_hash=p_password_hash,must_change_password=FALSE,
      auth_version=auth_version+1,updated_at=pg_catalog.now()
    WHERE id=target_id AND status='ACTIVE';
  IF NOT FOUND THEN
    UPDATE barnali.password_reset_tokens SET consumed_at=pg_catalog.now()
      WHERE user_id=target_id AND consumed_at IS NULL;
    RETURN FALSE;
  END IF;
  UPDATE barnali.password_reset_tokens SET consumed_at=pg_catalog.now()
    WHERE user_id=target_id AND consumed_at IS NULL;
  INSERT INTO barnali.admin_audit_events(actor_user_id,action,target_type,target_id,outcome,request_id)
    VALUES (target_id,'PASSWORD_RESET','user',target_id::text,'SUCCESS',pg_catalog.left(p_request_id,100));
  RETURN TRUE;
END
$$;

CREATE FUNCTION invitation_by_token(p_token_hash TEXT)
RETURNS TABLE(email TEXT,role TEXT)
LANGUAGE plpgsql SECURITY DEFINER
SET search_path = pg_catalog, barnali, pg_temp
AS $$
BEGIN
  IF p_token_hash IS NULL OR p_token_hash !~ '^[0-9a-f]{64}$' THEN RETURN; END IF;
  RETURN QUERY SELECT i.email,i.role
    FROM barnali.admin_invitations i
    WHERE i.token_hash=p_token_hash AND i.accepted_at IS NULL AND i.revoked_at IS NULL AND i.expires_at>pg_catalog.now()
    FOR UPDATE;
END
$$;

CREATE FUNCTION accept_invitation(p_token_hash TEXT, p_existing_user_id UUID,
                                   p_display_name TEXT, p_username TEXT, p_password_hash TEXT) RETURNS BOOLEAN
LANGUAGE plpgsql SECURITY DEFINER
SET search_path = pg_catalog, barnali, pg_temp
AS $$
DECLARE
  invite barnali.admin_invitations%ROWTYPE;
  target_id UUID;
BEGIN
  IF p_token_hash IS NULL OR p_token_hash !~ '^[0-9a-f]{64}$' THEN RAISE EXCEPTION 'Invalid invitation'; END IF;
  SELECT * INTO invite FROM barnali.admin_invitations
    WHERE token_hash=p_token_hash AND accepted_at IS NULL AND revoked_at IS NULL AND expires_at>pg_catalog.now()
    FOR UPDATE;
  IF NOT FOUND OR invite.role='OWNER' THEN RAISE EXCEPTION 'Invalid invitation'; END IF;
  SELECT id INTO target_id FROM barnali.users WHERE email=invite.email;
  IF target_id IS NULL THEN
    IF p_existing_user_id IS NOT NULL OR p_password_hash IS NULL
       OR p_password_hash !~ '^\$2[aby]\$[0-9]{2}\$[./A-Za-z0-9]{53}$' OR p_display_name IS NULL
       OR pg_catalog.length(pg_catalog.btrim(p_display_name)) NOT BETWEEN 1 AND 80
       OR p_username IS NULL OR p_username !~ '^[a-z0-9_]{3,32}$' THEN RAISE EXCEPTION 'Invalid invitation acceptance'; END IF;
    INSERT INTO barnali.users(username,display_name,email,password_hash)
      VALUES (p_username,pg_catalog.btrim(p_display_name),invite.email,p_password_hash) RETURNING id INTO target_id;
  ELSE
    IF p_existing_user_id IS DISTINCT FROM target_id OR p_password_hash IS NOT NULL
       OR NOT EXISTS (SELECT 1 FROM barnali.users WHERE id=target_id AND status='ACTIVE')
       OR EXISTS (SELECT 1 FROM barnali.admin_accounts WHERE user_id=target_id) THEN
      RAISE EXCEPTION 'Account is not eligible for invitation acceptance';
    END IF;
  END IF;
  INSERT INTO barnali.admin_accounts(user_id,role,active,invited_by)
    VALUES (target_id,invite.role,TRUE,invite.invited_by);
  UPDATE barnali.admin_invitations SET accepted_at=pg_catalog.now() WHERE id=invite.id;
  INSERT INTO barnali.admin_audit_events(actor_user_id,action,target_type,target_id,outcome,context)
    VALUES (target_id,'ADMIN_INVITATION_ACCEPTED','admin',target_id::text,'SUCCESS',
            pg_catalog.jsonb_build_object('role',invite.role));
  RETURN TRUE;
END
$$;

CREATE FUNCTION create_admin_invitation(p_id UUID,p_email TEXT,p_role TEXT,p_token_hash TEXT,p_expires_at TIMESTAMPTZ)
RETURNS TEXT
LANGUAGE plpgsql SECURITY DEFINER
SET search_path = pg_catalog, barnali, pg_temp
AS $$
DECLARE
  actor_id UUID := barnali.current_actor_id();
  normalized_email TEXT := pg_catalog.lower(pg_catalog.btrim(p_email));
BEGIN
  IF NOT barnali.actor_has_permission('ADMIN_TEAM_MANAGE') OR actor_id IS NULL THEN RETURN 'DENIED'; END IF;
  IF p_id IS NULL OR normalized_email IS NULL OR pg_catalog.length(normalized_email)>254
     OR p_role IS NULL OR p_token_hash IS NULL OR p_expires_at IS NULL
     OR normalized_email !~ '^[^[:space:]@]+@[^[:space:]@]+[.][^[:space:]@]+$'
     OR p_role NOT IN ('SUPER_ADMIN','MODERATOR','ANALYTICS_VIEWER','SUPPORT_ADMIN')
     OR p_token_hash !~ '^[0-9a-f]{64}$' OR p_expires_at<=pg_catalog.now()
     OR p_expires_at>pg_catalog.now()+interval '7 days' THEN
    RAISE EXCEPTION 'Invalid administrator invitation';
  END IF;
  PERFORM pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtextextended(normalized_email,0));
  IF EXISTS (SELECT 1 FROM barnali.admin_accounts a JOIN barnali.users u ON u.id=a.user_id
             WHERE a.role='OWNER' AND a.active AND u.email=normalized_email) THEN
    RETURN 'OWNER_IDENTITY_RESERVED';
  END IF;
  IF EXISTS (SELECT 1 FROM barnali.users u JOIN barnali.admin_accounts a ON a.user_id=u.id
             WHERE u.email=normalized_email AND a.active) THEN
    RETURN 'ALREADY_ADMIN';
  END IF;
  UPDATE barnali.admin_invitations SET revoked_at=pg_catalog.now()
    WHERE email=normalized_email AND accepted_at IS NULL AND revoked_at IS NULL;
  INSERT INTO barnali.admin_invitations(id,email,role,token_hash,invited_by,expires_at)
    VALUES (p_id,normalized_email,p_role,p_token_hash,actor_id,p_expires_at);
  RETURN 'CREATED';
END
$$;

CREATE FUNCTION submit_report(p_target_type TEXT,p_target_id UUID,p_reason TEXT,p_details TEXT)
RETURNS TABLE(result TEXT,created_id UUID)
LANGUAGE plpgsql SECURITY DEFINER
SET search_path = pg_catalog, barnali, pg_temp
AS $$
DECLARE
  actor_id UUID := barnali.current_actor_id();
  created_report_id UUID;
  target_exists BOOLEAN := FALSE;
BEGIN
  IF actor_id IS NULL OR NOT barnali.actor_is_active() OR p_target_id IS NULL
     OR p_target_type IS NULL OR p_target_type NOT IN ('DISCUSSION','REPLY','USER') THEN
    RAISE EXCEPTION 'Invalid report request';
  END IF;
  IF p_reason IS NULL OR p_reason NOT IN ('SPAM','HARASSMENT','MISINFORMATION','OFF_TOPIC','OTHER')
     OR (p_details IS NOT NULL AND pg_catalog.length(p_details)>1000) THEN
    RAISE EXCEPTION 'Invalid report request';
  END IF;
  IF p_target_type='DISCUSSION' THEN
    SELECT EXISTS(SELECT 1 FROM barnali.discussions d JOIN barnali.rooms r ON r.id=d.room_id
      WHERE d.id=p_target_id AND d.status='PUBLISHED' AND r.is_active AND r.is_public) INTO target_exists;
  ELSIF p_target_type='REPLY' THEN
    SELECT EXISTS(SELECT 1 FROM barnali.replies rp JOIN barnali.discussions d ON d.id=rp.discussion_id
      JOIN barnali.rooms r ON r.id=d.room_id WHERE rp.id=p_target_id AND rp.status='PUBLISHED'
      AND d.status='PUBLISHED' AND r.is_active AND r.is_public) INTO target_exists;
  ELSE
    SELECT EXISTS(SELECT 1 FROM barnali.users WHERE id=p_target_id AND status='ACTIVE') INTO target_exists;
  END IF;
  IF NOT target_exists THEN RETURN QUERY SELECT 'NOT_FOUND'::text,NULL::uuid; RETURN; END IF;
  INSERT INTO barnali.reports(reporter_id,target_type,target_id,reason,details)
    VALUES (actor_id,p_target_type,p_target_id,p_reason,p_details)
    ON CONFLICT (reporter_id,target_type,target_id) WHERE status='PENDING' DO NOTHING
    RETURNING id INTO created_report_id;
  IF created_report_id IS NULL THEN RETURN QUERY SELECT 'DUPLICATE'::text,NULL::uuid; RETURN; END IF;
  RETURN QUERY SELECT 'CREATED'::text,created_report_id;
END
$$;

CREATE FUNCTION discussion_vote_total(p_discussion_id UUID) RETURNS BIGINT
LANGUAGE sql STABLE SECURITY DEFINER
SET search_path = pg_catalog, barnali, pg_temp
AS $$
  SELECT COALESCE(sum(v.value),0)::bigint
  FROM barnali.discussion_votes v
  JOIN barnali.discussions d ON d.id=v.discussion_id
  JOIN barnali.rooms r ON r.id=d.room_id
  JOIN barnali.users author ON author.id=d.author_id
  WHERE d.id=p_discussion_id AND d.status='PUBLISHED' AND r.is_active AND r.is_public AND author.status='ACTIVE'
$$;

CREATE FUNCTION admin_overview(p_from DATE,p_to DATE)
RETURNS TABLE(total_users BIGINT,new_users BIGINT,active_users BIGINT,dau BIGINT,wau BIGINT,mau BIGINT,
              total_rooms BIGINT,total_discussions BIGINT,total_replies BIGINT,pending_reports BIGINT,suspended_users BIGINT)
LANGUAGE plpgsql STABLE SECURITY DEFINER
SET search_path = pg_catalog, barnali, pg_temp
AS $$
DECLARE today DATE := (pg_catalog.now() AT TIME ZONE 'UTC')::date;
BEGIN
  IF NOT barnali.actor_has_permission('OVERVIEW_READ') THEN RAISE EXCEPTION 'Overview permission required'; END IF;
  IF p_from IS NULL OR p_to IS NULL OR p_from>p_to OR p_to>today OR p_from<p_to-364 THEN RAISE EXCEPTION 'Invalid analytics range'; END IF;
  RETURN QUERY SELECT
    (SELECT count(*) FROM barnali.users),
    (SELECT count(*) FROM barnali.users WHERE created_at >= p_from::timestamp AT TIME ZONE 'UTC' AND created_at < (p_to+1)::timestamp AT TIME ZONE 'UTC'),
    (SELECT count(DISTINCT user_id) FROM barnali.user_activity_events WHERE occurred_at >= p_from::timestamp AT TIME ZONE 'UTC' AND occurred_at < (p_to+1)::timestamp AT TIME ZONE 'UTC'),
    (SELECT count(DISTINCT user_id) FROM barnali.user_activity_events WHERE occurred_at >= today::timestamp AT TIME ZONE 'UTC' AND occurred_at < (today+1)::timestamp AT TIME ZONE 'UTC'),
    (SELECT count(DISTINCT user_id) FROM barnali.user_activity_events WHERE occurred_at >= (today-6)::timestamp AT TIME ZONE 'UTC' AND occurred_at < (today+1)::timestamp AT TIME ZONE 'UTC'),
    (SELECT count(DISTINCT user_id) FROM barnali.user_activity_events WHERE occurred_at >= (today-29)::timestamp AT TIME ZONE 'UTC' AND occurred_at < (today+1)::timestamp AT TIME ZONE 'UTC'),
    (SELECT count(*) FROM barnali.rooms),
    (SELECT count(*) FROM barnali.discussions WHERE status='PUBLISHED'),
    (SELECT count(*) FROM barnali.replies WHERE status='PUBLISHED'),
    (SELECT count(*) FROM barnali.reports WHERE status='PENDING'),
    (SELECT count(*) FROM barnali.users WHERE status='SUSPENDED');
END
$$;

CREATE FUNCTION admin_analytics_overview(p_from DATE,p_to DATE)
RETURNS TABLE(total_users BIGINT,new_users BIGINT,active_users BIGINT,dau BIGINT,wau BIGINT,mau BIGINT,
              total_rooms BIGINT,total_discussions BIGINT,total_replies BIGINT,pending_reports BIGINT,suspended_users BIGINT)
LANGUAGE plpgsql STABLE SECURITY DEFINER
SET search_path = pg_catalog, barnali, pg_temp
AS $$
DECLARE today DATE := (pg_catalog.now() AT TIME ZONE 'UTC')::date;
BEGIN
  IF NOT barnali.actor_has_permission('ANALYTICS_READ') THEN RAISE EXCEPTION 'Analytics permission required'; END IF;
  IF p_from IS NULL OR p_to IS NULL OR p_from>p_to OR p_to>today OR p_from<p_to-364 THEN RAISE EXCEPTION 'Invalid analytics range'; END IF;
  RETURN QUERY SELECT
    (SELECT count(*) FROM barnali.users),
    (SELECT count(*) FROM barnali.users WHERE created_at >= p_from::timestamp AT TIME ZONE 'UTC' AND created_at < (p_to+1)::timestamp AT TIME ZONE 'UTC'),
    (SELECT count(DISTINCT user_id) FROM barnali.user_activity_events WHERE occurred_at >= p_from::timestamp AT TIME ZONE 'UTC' AND occurred_at < (p_to+1)::timestamp AT TIME ZONE 'UTC'),
    (SELECT count(DISTINCT user_id) FROM barnali.user_activity_events WHERE occurred_at >= today::timestamp AT TIME ZONE 'UTC' AND occurred_at < (today+1)::timestamp AT TIME ZONE 'UTC'),
    (SELECT count(DISTINCT user_id) FROM barnali.user_activity_events WHERE occurred_at >= (today-6)::timestamp AT TIME ZONE 'UTC' AND occurred_at < (today+1)::timestamp AT TIME ZONE 'UTC'),
    (SELECT count(DISTINCT user_id) FROM barnali.user_activity_events WHERE occurred_at >= (today-29)::timestamp AT TIME ZONE 'UTC' AND occurred_at < (today+1)::timestamp AT TIME ZONE 'UTC'),
    (SELECT count(*) FROM barnali.rooms),
    (SELECT count(*) FROM barnali.discussions WHERE status='PUBLISHED'),
    (SELECT count(*) FROM barnali.replies WHERE status='PUBLISHED'),
    (SELECT count(*) FROM barnali.reports WHERE status='PENDING'),
    (SELECT count(*) FROM barnali.users WHERE status='SUSPENDED');
END
$$;

CREATE FUNCTION admin_analytics_series(p_from DATE,p_to DATE,p_metric TEXT)
RETURNS TABLE(day DATE,value BIGINT)
LANGUAGE plpgsql STABLE SECURITY DEFINER
SET search_path = pg_catalog, barnali, pg_temp
AS $$
DECLARE today DATE := (pg_catalog.now() AT TIME ZONE 'UTC')::date;
BEGIN
  IF NOT barnali.actor_has_permission('ANALYTICS_READ') THEN RAISE EXCEPTION 'Analytics permission required'; END IF;
  IF p_from IS NULL OR p_to IS NULL OR p_from>p_to OR p_to>today OR p_from<p_to-364
     OR p_metric IS NULL OR p_metric NOT IN ('registrations','active','engagement') THEN RAISE EXCEPTION 'Invalid analytics range'; END IF;
  IF p_metric='registrations' THEN
    RETURN QUERY WITH days AS (SELECT g::date AS day FROM pg_catalog.generate_series(p_from,p_to,interval '1 day') g),
      counts AS (SELECT (created_at AT TIME ZONE 'UTC')::date AS day,count(*)::bigint AS value FROM barnali.users
        WHERE created_at>=p_from::timestamp AT TIME ZONE 'UTC' AND created_at<(p_to+1)::timestamp AT TIME ZONE 'UTC' GROUP BY 1)
      SELECT days.day,coalesce(counts.value,0) FROM days LEFT JOIN counts USING(day) ORDER BY days.day;
  ELSIF p_metric='active' THEN
    RETURN QUERY WITH days AS (SELECT g::date AS day FROM pg_catalog.generate_series(p_from,p_to,interval '1 day') g),
      counts AS (SELECT (occurred_at AT TIME ZONE 'UTC')::date AS day,count(DISTINCT user_id)::bigint AS value FROM barnali.user_activity_events
        WHERE occurred_at>=p_from::timestamp AT TIME ZONE 'UTC' AND occurred_at<(p_to+1)::timestamp AT TIME ZONE 'UTC' GROUP BY 1)
      SELECT days.day,coalesce(counts.value,0) FROM days LEFT JOIN counts USING(day) ORDER BY days.day;
  ELSE
    RETURN QUERY WITH days AS (SELECT g::date AS day FROM pg_catalog.generate_series(p_from,p_to,interval '1 day') g),
      counts AS (SELECT (occurred_at AT TIME ZONE 'UTC')::date AS day,count(*)::bigint AS value FROM barnali.user_activity_events
        WHERE occurred_at>=p_from::timestamp AT TIME ZONE 'UTC' AND occurred_at<(p_to+1)::timestamp AT TIME ZONE 'UTC' GROUP BY 1)
      SELECT days.day,coalesce(counts.value,0) FROM days LEFT JOIN counts USING(day) ORDER BY days.day;
  END IF;
END
$$;

CREATE FUNCTION user_activity_series(p_user_id UUID,p_from DATE,p_to DATE)
RETURNS TABLE(day DATE,value BIGINT)
LANGUAGE plpgsql STABLE SECURITY DEFINER
SET search_path = pg_catalog, barnali, pg_temp
AS $$
BEGIN
  IF NOT barnali.actor_has_permission('USER_READ') THEN RAISE EXCEPTION 'User-read permission required'; END IF;
  IF p_user_id IS NULL OR p_from IS NULL OR p_to IS NULL OR p_from>p_to OR p_to>(pg_catalog.now() AT TIME ZONE 'UTC')::date
     OR p_from<p_to-364 THEN RAISE EXCEPTION 'Invalid user activity range'; END IF;
  RETURN QUERY WITH days AS (SELECT g::date AS day FROM pg_catalog.generate_series(p_from,p_to,interval '1 day') g),
    counts AS (SELECT (occurred_at AT TIME ZONE 'UTC')::date AS day,count(*)::bigint AS value
      FROM barnali.user_activity_events WHERE user_id=p_user_id
        AND occurred_at>=p_from::timestamp AT TIME ZONE 'UTC' AND occurred_at<(p_to+1)::timestamp AT TIME ZONE 'UTC' GROUP BY 1)
    SELECT days.day,coalesce(counts.value,0) FROM days LEFT JOIN counts USING(day) ORDER BY days.day;
END
$$;

CREATE FUNCTION owner_bootstrap_readiness() RETURNS TEXT
LANGUAGE plpgsql STABLE SECURITY DEFINER
SET search_path = pg_catalog, barnali, pg_temp
AS $$
DECLARE
  guard_consumed TIMESTAMPTZ;
  guard_count BIGINT;
  owner_count BIGINT;
BEGIN
  IF pg_catalog.to_regclass('barnali.flyway_schema_history') IS NULL
     OR NOT (SELECT count(*)=3 AND pg_catalog.bool_and(success)
             FROM barnali.flyway_schema_history WHERE version IN ('1','2','3')) THEN
    RETURN 'INCONSISTENT';
  END IF;
  SELECT count(*),max(consumed_at) INTO guard_count,guard_consumed FROM barnali.admin_bootstrap_guard WHERE id=1;
  SELECT count(*) INTO owner_count FROM barnali.admin_accounts WHERE role='OWNER';
  IF guard_count<>1 THEN RETURN 'INCONSISTENT'; END IF;
  IF guard_consumed IS NOT NULL THEN RETURN 'CONSUMED'; END IF;
  IF owner_count<>0 THEN RETURN 'INCONSISTENT'; END IF;
  RETURN 'READY';
END
$$;

CREATE FUNCTION provision_initial_owner(p_email TEXT,p_password_hash TEXT) RETURNS BOOLEAN
LANGUAGE plpgsql SECURITY DEFINER
SET search_path = pg_catalog, barnali, pg_temp
AS $$
DECLARE
  guard_consumed TIMESTAMPTZ;
  target_id UUID;
  normalized_email TEXT := pg_catalog.lower(pg_catalog.btrim(p_email));
  generated_username TEXT := 'owner_' || pg_catalog.substr(pg_catalog.replace(pg_catalog.gen_random_uuid()::text,'-',''),1,12);
BEGIN
  IF barnali.owner_bootstrap_readiness() <> 'READY' THEN
    RAISE EXCEPTION 'Owner provisioning is not ready';
  END IF;
  SELECT consumed_at INTO guard_consumed FROM barnali.admin_bootstrap_guard WHERE id=1 FOR UPDATE;
  IF NOT FOUND OR guard_consumed IS NOT NULL
     OR EXISTS (SELECT 1 FROM barnali.admin_accounts WHERE role='OWNER') THEN
    RAISE EXCEPTION 'Owner provisioning is already consumed or inconsistent';
  END IF;
  IF p_email IS NULL OR normalized_email !~ '^[^[:space:]@]+@[^[:space:]@]+[.][^[:space:]@]+$'
     OR pg_catalog.length(normalized_email)>254 OR p_password_hash IS NULL
     OR p_password_hash !~ '^\$2[aby]\$[0-9]{2}\$[./A-Za-z0-9]{53}$' THEN
    RAISE EXCEPTION 'Invalid owner provisioning input';
  END IF;
  IF EXISTS (SELECT 1 FROM barnali.users WHERE email=normalized_email) THEN
    RAISE EXCEPTION 'Owner email is already assigned to an account';
  END IF;
  UPDATE barnali.admin_bootstrap_guard SET consumed_at=pg_catalog.clock_timestamp() WHERE id=1 AND consumed_at IS NULL;
  IF NOT FOUND THEN RAISE EXCEPTION 'Owner provisioning guard is not available'; END IF;
  INSERT INTO barnali.users(username,display_name,email,password_hash,must_change_password)
    VALUES (generated_username,'BARNALI Owner',normalized_email,p_password_hash,TRUE) RETURNING id INTO target_id;
  INSERT INTO barnali.admin_accounts(user_id,role,active) VALUES (target_id,'OWNER',TRUE);
  INSERT INTO barnali.admin_audit_events(actor_user_id,action,target_type,target_id,outcome)
    VALUES (target_id,'OWNER_BOOTSTRAP','admin_account',target_id::text,'SUCCESS');
  RETURN TRUE;
END
$$;

ALTER TABLE users ENABLE ROW LEVEL SECURITY;
ALTER TABLE rooms ENABLE ROW LEVEL SECURITY;
ALTER TABLE room_memberships ENABLE ROW LEVEL SECURITY;
ALTER TABLE discussions ENABLE ROW LEVEL SECURITY;
ALTER TABLE replies ENABLE ROW LEVEL SECURITY;
ALTER TABLE discussion_votes ENABLE ROW LEVEL SECURITY;
ALTER TABLE reports ENABLE ROW LEVEL SECURITY;
ALTER TABLE user_activity_events ENABLE ROW LEVEL SECURITY;
ALTER TABLE admin_accounts ENABLE ROW LEVEL SECURITY;
ALTER TABLE admin_bootstrap_guard ENABLE ROW LEVEL SECURITY;
ALTER TABLE admin_invitations ENABLE ROW LEVEL SECURITY;
ALTER TABLE admin_audit_events ENABLE ROW LEVEL SECURITY;
ALTER TABLE auth_login_limits ENABLE ROW LEVEL SECURITY;
ALTER TABLE password_reset_tokens ENABLE ROW LEVEL SECURITY;

CREATE POLICY users_select_scoped ON users FOR SELECT USING (
  (id=current_actor_id() AND status='ACTIVE') OR actor_has_permission('USER_READ') OR is_public_author(id));
CREATE POLICY users_update_moderation ON users FOR UPDATE USING (
  id<>current_actor_id() AND actor_has_permission('USER_SUSPEND') AND NOT is_admin_account(id)) WITH CHECK (
  id<>current_actor_id() AND actor_has_permission('USER_SUSPEND') AND NOT is_admin_account(id));
CREATE POLICY users_update_admin_session_revoke ON users FOR UPDATE USING (
  id<>current_actor_id() AND actor_has_permission('ADMIN_TEAM_MANAGE') AND is_admin_account(id)) WITH CHECK (
  id<>current_actor_id() AND actor_has_permission('ADMIN_TEAM_MANAGE') AND is_admin_account(id));
CREATE POLICY rooms_select_scoped ON rooms FOR SELECT USING (
  (is_active AND is_public) OR (is_active AND actor_is_active() AND EXISTS
    (SELECT 1 FROM room_memberships m WHERE m.room_id=rooms.id AND m.user_id=current_actor_id()))
  OR actor_has_permission('ROOM_READ'));
CREATE POLICY rooms_insert_admin ON rooms FOR INSERT WITH CHECK (
  actor_has_permission('ROOM_MANAGE') AND created_by=current_actor_id());
CREATE POLICY rooms_update_admin ON rooms FOR UPDATE USING (actor_has_permission('ROOM_MANAGE'))
  WITH CHECK (actor_has_permission('ROOM_MANAGE'));
CREATE POLICY memberships_select_self ON room_memberships FOR SELECT USING (user_id=current_actor_id() AND actor_is_active());
CREATE POLICY memberships_insert_self ON room_memberships FOR INSERT WITH CHECK (
  user_id=current_actor_id() AND actor_is_active()
  AND EXISTS (SELECT 1 FROM rooms r WHERE r.id=room_id AND r.is_active AND r.is_public));
CREATE POLICY discussions_select_visible ON discussions FOR SELECT USING (
  (status='PUBLISHED' AND EXISTS (SELECT 1 FROM rooms r WHERE r.id=room_id AND r.is_active
    AND (r.is_public OR EXISTS (SELECT 1 FROM room_memberships m WHERE m.room_id=r.id AND m.user_id=current_actor_id())))
    AND EXISTS (SELECT 1 FROM users u WHERE u.id=author_id AND u.status='ACTIVE'))
  OR (author_id=current_actor_id() AND actor_is_active()) OR actor_has_permission('REPORT_READ'));
CREATE POLICY discussions_insert_member ON discussions FOR INSERT WITH CHECK (
  author_id=current_actor_id() AND actor_is_active() AND status='PUBLISHED'
  AND EXISTS (SELECT 1 FROM rooms r WHERE r.id=room_id AND r.is_active AND r.is_public)
  AND EXISTS (SELECT 1 FROM room_memberships m WHERE m.room_id=discussions.room_id AND m.user_id=current_actor_id()));
CREATE POLICY discussions_update_moderator ON discussions FOR UPDATE USING (
  actor_has_permission('REPORT_RESOLVE')) WITH CHECK (actor_has_permission('REPORT_RESOLVE') AND status='HIDDEN');
CREATE POLICY replies_select_visible ON replies FOR SELECT USING (
  (status='PUBLISHED' AND EXISTS (SELECT 1 FROM discussions d JOIN rooms r ON r.id=d.room_id
     WHERE d.id=discussion_id AND d.status='PUBLISHED' AND r.is_active
       AND (r.is_public OR EXISTS (SELECT 1 FROM room_memberships m WHERE m.room_id=r.id AND m.user_id=current_actor_id()))))
    AND EXISTS (SELECT 1 FROM users reply_author WHERE reply_author.id=replies.author_id AND reply_author.status='ACTIVE')
  OR (author_id=current_actor_id() AND actor_is_active()) OR actor_has_permission('REPORT_READ'));
CREATE POLICY replies_insert_member ON replies FOR INSERT WITH CHECK (
  author_id=current_actor_id() AND actor_is_active() AND status='PUBLISHED'
  AND EXISTS (SELECT 1 FROM discussions d JOIN rooms r ON r.id=d.room_id
    WHERE d.id=discussion_id AND d.status='PUBLISHED' AND r.is_active AND (r.is_public OR EXISTS
      (SELECT 1 FROM room_memberships m WHERE m.room_id=r.id AND m.user_id=current_actor_id()))));
CREATE POLICY replies_update_moderator ON replies FOR UPDATE USING (actor_has_permission('REPORT_RESOLVE'))
  WITH CHECK (actor_has_permission('REPORT_RESOLVE') AND status='HIDDEN');
CREATE POLICY votes_select_self ON discussion_votes FOR SELECT USING (user_id=current_actor_id() AND actor_is_active());
CREATE POLICY votes_insert_self ON discussion_votes FOR INSERT WITH CHECK (
  user_id=current_actor_id() AND actor_is_active() AND EXISTS (SELECT 1 FROM discussions d JOIN rooms r ON r.id=d.room_id
    JOIN users u ON u.id=d.author_id WHERE d.id=discussion_id AND d.status='PUBLISHED' AND r.is_active AND r.is_public AND u.status='ACTIVE'));
CREATE POLICY votes_update_self ON discussion_votes FOR UPDATE USING (
  user_id=current_actor_id() AND actor_is_active() AND EXISTS (SELECT 1 FROM discussions d JOIN rooms r ON r.id=d.room_id
    JOIN users u ON u.id=d.author_id WHERE d.id=discussion_id AND d.status='PUBLISHED' AND r.is_active AND r.is_public AND u.status='ACTIVE'))
  WITH CHECK (user_id=current_actor_id() AND actor_is_active() AND EXISTS (SELECT 1 FROM discussions d JOIN rooms r ON r.id=d.room_id
    JOIN users u ON u.id=d.author_id WHERE d.id=discussion_id AND d.status='PUBLISHED' AND r.is_active AND r.is_public AND u.status='ACTIVE'));
CREATE POLICY reports_select_scoped ON reports FOR SELECT USING (
  (reporter_id=current_actor_id() AND actor_is_active()) OR actor_has_permission('REPORT_READ'));
CREATE POLICY reports_update_moderator ON reports FOR UPDATE USING (
  status='PENDING' AND actor_has_permission('REPORT_RESOLVE')) WITH CHECK (
  status IN ('RESOLVED','DISMISSED') AND reviewer_id=current_actor_id() AND actor_has_permission('REPORT_RESOLVE'));
CREATE POLICY activity_insert_self ON user_activity_events FOR INSERT WITH CHECK (user_id=current_actor_id() AND actor_is_active());
CREATE POLICY admin_accounts_select_scoped ON admin_accounts FOR SELECT USING (
  (user_id=current_actor_id() AND actor_is_active()) OR actor_has_permission('USER_READ') OR actor_has_permission('ADMIN_TEAM_READ'));
CREATE POLICY admin_accounts_update_owner ON admin_accounts FOR UPDATE USING (
  role<>'OWNER' AND actor_has_permission('ADMIN_TEAM_MANAGE')) WITH CHECK (
  role<>'OWNER' AND actor_has_permission('ADMIN_TEAM_MANAGE'));
CREATE POLICY bootstrap_guard_no_runtime ON admin_bootstrap_guard FOR ALL USING (FALSE) WITH CHECK (FALSE);
CREATE POLICY invitations_select_team ON admin_invitations FOR SELECT USING (actor_has_permission('ADMIN_TEAM_READ'));
CREATE POLICY invitations_insert_owner ON admin_invitations FOR INSERT WITH CHECK (
  actor_has_permission('ADMIN_TEAM_MANAGE') AND invited_by=current_actor_id() AND role<>'OWNER');
CREATE POLICY invitations_update_owner ON admin_invitations FOR UPDATE USING (actor_has_permission('ADMIN_TEAM_MANAGE'))
  WITH CHECK (actor_has_permission('ADMIN_TEAM_MANAGE'));
CREATE POLICY audit_select_admin ON admin_audit_events FOR SELECT USING (actor_has_permission('AUDIT_READ'));
CREATE POLICY audit_insert_actor_or_auth_failure ON admin_audit_events FOR INSERT WITH CHECK (
  (actor_user_id=current_actor_id() AND actor_user_id IS NOT NULL AND actor_is_active())
  OR (actor_user_id IS NULL AND action IN ('LOGIN_FAILED','LOGIN_INACTIVE','LOGIN_THROTTLED')));
CREATE POLICY login_limits_no_runtime ON auth_login_limits FOR ALL USING (FALSE) WITH CHECK (FALSE);
CREATE POLICY reset_tokens_no_runtime ON password_reset_tokens FOR ALL USING (FALSE) WITH CHECK (FALSE);

CREATE VIEW public_room_summaries WITH (security_barrier=true) AS
  SELECT r.id,r.slug,r.name,r.description,
    (SELECT count(*) FROM barnali.room_memberships m JOIN barnali.users u ON u.id=m.user_id
      WHERE m.room_id=r.id AND u.status='ACTIVE') AS member_count,
    (SELECT count(*) FROM barnali.discussions d JOIN barnali.users u ON u.id=d.author_id
      WHERE d.room_id=r.id AND d.status='PUBLISHED' AND u.status='ACTIVE') AS discussion_count
  FROM barnali.rooms r WHERE r.is_active AND r.is_public;

CREATE VIEW admin_user_rows WITH (security_barrier=true) AS
  SELECT u.id,u.username,u.display_name,u.email,u.status,(u.email_verified_at IS NOT NULL) AS email_verified,
    u.created_at,(SELECT max(e.occurred_at) FROM barnali.user_activity_events e WHERE e.user_id=u.id) AS last_activity_at,
    coalesce(a.role,'MEMBER') AS role,
    (SELECT count(*) FROM barnali.room_memberships m WHERE m.user_id=u.id) AS room_count,
    (SELECT count(*) FROM barnali.discussions d WHERE d.author_id=u.id) AS discussion_count,
    (SELECT count(*) FROM barnali.replies rp WHERE rp.author_id=u.id) AS reply_count
  FROM barnali.users u LEFT JOIN barnali.admin_accounts a ON a.user_id=u.id
  WHERE barnali.actor_has_permission('USER_READ');

CREATE VIEW admin_team_rows WITH (security_barrier=true) AS
  SELECT u.id,u.display_name,u.email,u.username,a.role,a.active,a.created_at,a.last_used_at
  FROM barnali.admin_accounts a JOIN barnali.users u ON u.id=a.user_id
  WHERE barnali.actor_has_permission('ADMIN_TEAM_READ');

CREATE VIEW admin_room_rows WITH (security_barrier=true) AS
  SELECT r.id,r.slug,r.name,r.description,r.is_active,r.is_public,r.created_at,
    (SELECT count(*) FROM barnali.room_memberships m WHERE m.room_id=r.id) AS member_count,
    (SELECT count(*) FROM barnali.discussions d WHERE d.room_id=r.id) AS discussion_count
  FROM barnali.rooms r WHERE barnali.actor_has_permission('ROOM_READ');

CREATE VIEW admin_audit_rows WITH (security_barrier=true) AS
  SELECT e.id,u.display_name AS actor_name,e.action,e.target_type,e.target_id,e.outcome,e.reason,e.created_at
  FROM barnali.admin_audit_events e LEFT JOIN barnali.users u ON u.id=e.actor_user_id
  WHERE barnali.actor_has_permission('AUDIT_READ');

REVOKE ALL ON TABLE users,rooms,room_memberships,discussions,replies,discussion_votes,reports,
  user_activity_events,admin_accounts,admin_bootstrap_guard,admin_invitations,admin_audit_events,
  auth_login_limits,password_reset_tokens FROM PUBLIC;
REVOKE ALL ON SEQUENCE user_activity_events_id_seq,admin_audit_events_id_seq FROM PUBLIC;
REVOKE ALL ON SCHEMA barnali FROM PUBLIC;
REVOKE ALL ON ALL TABLES IN SCHEMA barnali FROM barnali_runtime,barnali_provisioner;
REVOKE ALL ON ALL SEQUENCES IN SCHEMA barnali FROM barnali_runtime,barnali_provisioner;
REVOKE ALL ON ALL FUNCTIONS IN SCHEMA barnali FROM barnali_runtime,barnali_provisioner;
REVOKE CREATE ON SCHEMA barnali FROM barnali_runtime,barnali_provisioner;
-- V1's trigger function has PostgreSQL's default PUBLIC EXECUTE ACL. It is
-- only usable as a trigger, but no non-owner needs direct execute permission.
REVOKE ALL ON FUNCTION barnali.barnali_reject_audit_mutation() FROM PUBLIC;

GRANT USAGE ON SCHEMA barnali TO barnali_runtime,barnali_provisioner;
GRANT SELECT(id,username,display_name,status,email_verified_at,must_change_password,auth_version,created_at,updated_at,last_login_at)
  ON users TO barnali_runtime;
GRANT UPDATE(status,auth_version,updated_at) ON users TO barnali_runtime;
GRANT SELECT ON rooms TO barnali_runtime;
GRANT INSERT(id,slug,name,description,is_public,is_active,created_by) ON rooms TO barnali_runtime;
GRANT UPDATE(is_active,updated_at) ON rooms TO barnali_runtime;
GRANT SELECT(room_id,user_id,joined_at),INSERT(room_id,user_id) ON room_memberships TO barnali_runtime;
GRANT SELECT ON discussions TO barnali_runtime;
GRANT INSERT(id,slug,room_id,author_id,title,body) ON discussions TO barnali_runtime;
GRANT UPDATE(status,updated_at) ON discussions TO barnali_runtime;
GRANT SELECT(discussion_id,user_id,value,created_at),INSERT(discussion_id,user_id,value),UPDATE(value,created_at)
  ON discussion_votes TO barnali_runtime;
GRANT SELECT ON replies TO barnali_runtime;
GRANT INSERT(id,discussion_id,author_id,body) ON replies TO barnali_runtime;
GRANT UPDATE(status) ON replies TO barnali_runtime;
GRANT SELECT ON reports TO barnali_runtime;
GRANT UPDATE(status,reviewer_id,resolution,resolved_at) ON reports TO barnali_runtime;
GRANT INSERT(user_id,event_type) ON user_activity_events TO barnali_runtime;
GRANT SELECT(user_id,role,active,invited_by,created_at,last_used_at),UPDATE(role,active,invited_by)
  ON admin_accounts TO barnali_runtime;
GRANT SELECT(id,email,role,invited_by,expires_at,accepted_at,revoked_at,created_at),
  UPDATE(revoked_at) ON admin_invitations TO barnali_runtime;
GRANT SELECT(id,actor_user_id,action,target_type,target_id,outcome,reason,request_id,context,created_at),
  INSERT(actor_user_id,action,target_type,target_id,outcome,reason,request_id,context) ON admin_audit_events TO barnali_runtime;
GRANT USAGE ON SEQUENCE user_activity_events_id_seq,admin_audit_events_id_seq TO barnali_runtime;
GRANT SELECT ON public_room_summaries,admin_user_rows,admin_team_rows,admin_room_rows,admin_audit_rows TO barnali_runtime;

REVOKE ALL ON FUNCTION current_actor_id(),actor_has_permission(TEXT),is_admin_account(UUID),actor_is_active(),is_public_author(UUID),
  auth_principal_by_email(TEXT),register_user(TEXT,TEXT,TEXT,TEXT),current_session_user(),record_successful_login(),
  current_password_hash(),change_current_password(TEXT,TEXT),login_limit_blocked(TEXT,TEXT),
  login_limit_record_attempt(TEXT,TEXT),login_limit_clear(TEXT),login_limit_audit_address(TEXT),login_limit_prune(),
  issue_password_reset(TEXT,TEXT),invalidate_password_reset_delivery(TEXT),complete_password_reset(TEXT,TEXT,TEXT),
  invitation_by_token(TEXT),accept_invitation(TEXT,UUID,TEXT,TEXT,TEXT),
  create_admin_invitation(UUID,TEXT,TEXT,TEXT,TIMESTAMPTZ),
  submit_report(TEXT,UUID,TEXT,TEXT),
  discussion_vote_total(UUID),admin_overview(DATE,DATE),admin_analytics_overview(DATE,DATE),admin_analytics_series(DATE,DATE,TEXT),
  user_activity_series(UUID,DATE,DATE),owner_bootstrap_readiness(),provision_initial_owner(TEXT,TEXT) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION current_actor_id(),actor_has_permission(TEXT),is_admin_account(UUID),actor_is_active(),is_public_author(UUID),
  auth_principal_by_email(TEXT),register_user(TEXT,TEXT,TEXT,TEXT),current_session_user(),record_successful_login(),
  current_password_hash(),change_current_password(TEXT,TEXT),login_limit_blocked(TEXT,TEXT),
  login_limit_record_attempt(TEXT,TEXT),login_limit_clear(TEXT),login_limit_audit_address(TEXT),login_limit_prune(),
  issue_password_reset(TEXT,TEXT),invalidate_password_reset_delivery(TEXT),complete_password_reset(TEXT,TEXT,TEXT),
  invitation_by_token(TEXT),accept_invitation(TEXT,UUID,TEXT,TEXT,TEXT),
  create_admin_invitation(UUID,TEXT,TEXT,TEXT,TIMESTAMPTZ),submit_report(TEXT,UUID,TEXT,TEXT),
  discussion_vote_total(UUID),admin_overview(DATE,DATE),admin_analytics_overview(DATE,DATE),admin_analytics_series(DATE,DATE,TEXT),
  user_activity_series(UUID,DATE,DATE) TO barnali_runtime;
GRANT EXECUTE ON FUNCTION owner_bootstrap_readiness(),provision_initial_owner(TEXT,TEXT) TO barnali_provisioner;

