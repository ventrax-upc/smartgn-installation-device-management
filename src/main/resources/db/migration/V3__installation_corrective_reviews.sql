CREATE TABLE installation_corrective_reviews (
    review_id uuid PRIMARY KEY,
    installation_id uuid NOT NULL REFERENCES installations(id),
    previous_installer_id uuid NOT NULL REFERENCES installers(id),
    assigned_installer_id uuid NOT NULL REFERENCES installers(id),
    previous_status varchar(30) NOT NULL,
    previous_completed_at timestamptz,
    reason varchar(500) NOT NULL,
    requested_at timestamptz NOT NULL DEFAULT now(),
    completed_at timestamptz,
    CHECK (previous_installer_id <> assigned_installer_id)
);
CREATE UNIQUE INDEX installation_corrective_review_active
    ON installation_corrective_reviews (installation_id) WHERE completed_at IS NULL;
