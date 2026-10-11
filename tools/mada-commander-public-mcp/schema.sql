-- Dedicated database ONLY for the public Mada Commander service.
-- Apply using: npm run migrate after setting MADA_COMMANDER_DATABASE_URL.
-- This schema deliberately does not share tables with the LudoMada game backend.
CREATE TABLE IF NOT EXISTS mada_public_users (
  id text PRIMARY KEY,
  google_sub text NOT NULL UNIQUE,
  email text NOT NULL,
  display_name text NOT NULL DEFAULT '',
  email_verified boolean NOT NULL DEFAULT false,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS mada_web_sessions (
  token_hash char(64) PRIMARY KEY,
  user_id text NOT NULL REFERENCES mada_public_users(id) ON DELETE CASCADE,
  expires_at timestamptz,
  created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS mada_web_sessions_user_idx ON mada_web_sessions(user_id);
CREATE INDEX IF NOT EXISTS mada_web_sessions_expires_idx ON mada_web_sessions(expires_at);

CREATE TABLE IF NOT EXISTS mada_google_states (
  state_hash char(64) PRIMARY KEY,
  purpose text NOT NULL CHECK (purpose IN ('dashboard','mcp')),
  transaction_id text,
  expires_at timestamptz NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS mada_google_states_expires_idx ON mada_google_states(expires_at);
CREATE INDEX IF NOT EXISTS mada_google_states_transaction_idx ON mada_google_states(transaction_id);

CREATE TABLE IF NOT EXISTS mada_oauth_authorization_requests (
  transaction_id text PRIMARY KEY,
  user_id text REFERENCES mada_public_users(id) ON DELETE CASCADE,
  client_id text NOT NULL,
  redirect_uri text NOT NULL,
  client_state text NOT NULL,
  code_challenge text NOT NULL,
  scope text[] NOT NULL,
  resource text NOT NULL,
  consent_nonce_hash char(64),
  expires_at timestamptz NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS mada_oauth_requests_user_expires_idx ON mada_oauth_authorization_requests(user_id, expires_at);

CREATE TABLE IF NOT EXISTS mada_oauth_codes (
  code_hash char(64) PRIMARY KEY,
  user_id text NOT NULL REFERENCES mada_public_users(id) ON DELETE CASCADE,
  client_id text NOT NULL,
  redirect_uri text NOT NULL,
  code_challenge text NOT NULL,
  scope text[] NOT NULL,
  resource text NOT NULL,
  expires_at timestamptz NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS mada_oauth_codes_expires_idx ON mada_oauth_codes(expires_at);

CREATE TABLE IF NOT EXISTS mada_oauth_access_tokens (
  token_hash char(64) PRIMARY KEY,
  user_id text NOT NULL REFERENCES mada_public_users(id) ON DELETE CASCADE,
  client_id text NOT NULL,
  scope text[] NOT NULL,
  resource text NOT NULL,
  expires_at timestamptz NOT NULL,
  revoked_at timestamptz,
  created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS mada_access_tokens_user_idx ON mada_oauth_access_tokens(user_id);
CREATE INDEX IF NOT EXISTS mada_access_tokens_expires_idx ON mada_oauth_access_tokens(expires_at);

CREATE TABLE IF NOT EXISTS mada_oauth_refresh_tokens (
  token_hash char(64) PRIMARY KEY,
  user_id text NOT NULL REFERENCES mada_public_users(id) ON DELETE CASCADE,
  client_id text NOT NULL,
  scope text[] NOT NULL,
  resource text NOT NULL,
  expires_at timestamptz,
  created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS mada_refresh_tokens_expires_idx ON mada_oauth_refresh_tokens(expires_at);

CREATE TABLE IF NOT EXISTS mada_agent_pair_codes (
  code_hash char(64) PRIMARY KEY,
  user_id text NOT NULL REFERENCES mada_public_users(id) ON DELETE CASCADE,
  expires_at timestamptz NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS mada_agent_pair_codes_expires_idx ON mada_agent_pair_codes(expires_at);

CREATE TABLE IF NOT EXISTS mada_public_agents (
  id text PRIMARY KEY,
  user_id text NOT NULL REFERENCES mada_public_users(id) ON DELETE CASCADE,
  name text NOT NULL,
  token_hash char(64) NOT NULL UNIQUE,
  last_seen_at timestamptz,
  revoked_at timestamptz,
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (id, user_id)
);
CREATE INDEX IF NOT EXISTS mada_public_agents_user_idx ON mada_public_agents(user_id);

CREATE TABLE IF NOT EXISTS mada_public_projects (
  agent_id text NOT NULL REFERENCES mada_public_agents(id) ON DELETE CASCADE,
  project_id text NOT NULL,
  label text NOT NULL,
  branch text NOT NULL DEFAULT '',
  head text NOT NULL DEFAULT '',
  updated_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY(agent_id, project_id)
);

CREATE TABLE IF NOT EXISTS mada_public_jobs (
  id text PRIMARY KEY,
  user_id text NOT NULL REFERENCES mada_public_users(id) ON DELETE CASCADE,
  agent_id text NOT NULL,
  project_id text NOT NULL,
  profile text NOT NULL CHECK (profile IN ('git_status','toolchain','inventory')),
  status text NOT NULL CHECK (status IN ('queued','running','succeeded','failed','cancelled')),
  result jsonb,
  error_summary text NOT NULL DEFAULT '',
  created_at timestamptz NOT NULL DEFAULT now(),
  started_at timestamptz,
  finished_at timestamptz,
  FOREIGN KEY (agent_id, project_id) REFERENCES mada_public_projects(agent_id, project_id) ON DELETE CASCADE,
  FOREIGN KEY (agent_id, user_id) REFERENCES mada_public_agents(id, user_id) ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS mada_public_jobs_user_idx ON mada_public_jobs(user_id, created_at DESC);
CREATE INDEX IF NOT EXISTS mada_public_jobs_queue_idx ON mada_public_jobs(agent_id, status, created_at);
CREATE INDEX IF NOT EXISTS mada_public_jobs_finished_idx ON mada_public_jobs(finished_at) WHERE finished_at IS NOT NULL;

-- Bounded request payloads allow registered agents to perform approved workspace operations.
ALTER TABLE public.mada_public_jobs ADD COLUMN IF NOT EXISTS request jsonb NOT NULL DEFAULT '{}'::jsonb;
ALTER TABLE public.mada_public_jobs DROP CONSTRAINT IF EXISTS mada_public_jobs_profile_check;
ALTER TABLE public.mada_public_jobs ADD CONSTRAINT mada_public_jobs_profile_check CHECK (profile IN ('git_status','toolchain','inventory','workspace_list','workspace_read','workspace_write','run_task','opera_status','opera_tabs','opera_navigate','opera_action'));

CREATE TABLE IF NOT EXISTS mada_public_rate_limits (
  key_hash char(64) PRIMARY KEY,
  window_start timestamptz NOT NULL,
  hit_count integer NOT NULL DEFAULT 1,
  updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS mada_public_rate_limits_updated_idx ON mada_public_rate_limits(updated_at);


-- Make existing dashboard sessions and OAuth grants persistent as well.
-- They remain revocable through sign-out/account controls and the connected-apps page.
ALTER TABLE public.mada_web_sessions ALTER COLUMN expires_at DROP NOT NULL;
UPDATE public.mada_web_sessions SET expires_at = NULL;
ALTER TABLE public.mada_oauth_refresh_tokens ALTER COLUMN expires_at DROP NOT NULL;
UPDATE public.mada_oauth_refresh_tokens SET expires_at = NULL;
CREATE INDEX IF NOT EXISTS mada_refresh_tokens_user_client_idx ON public.mada_oauth_refresh_tokens(user_id, client_id);
CREATE INDEX IF NOT EXISTS mada_access_tokens_user_client_idx ON public.mada_oauth_access_tokens(user_id, client_id);
