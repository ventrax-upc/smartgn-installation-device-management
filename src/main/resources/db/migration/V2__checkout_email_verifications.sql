CREATE TABLE checkout_email_verifications (
    email text PRIMARY KEY,
    challenge uuid NOT NULL UNIQUE,
    code_hash text NOT NULL,
    attempts integer NOT NULL DEFAULT 0,
    expires_at timestamptz NOT NULL,
    proof_hash text,
    installation_id uuid
);
CREATE INDEX checkout_email_verifications_expiry ON checkout_email_verifications(expires_at);
