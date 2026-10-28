-- Initial schema for Installation & Device Management.

CREATE TABLE associations (
    device_id uuid NOT NULL,
    version bigint NOT NULL,
    account_id uuid NOT NULL,
    property_id uuid NOT NULL,
    point_id uuid NOT NULL,
    valid_from timestamp with time zone NOT NULL,
    valid_to timestamp with time zone,
    confirmed boolean NOT NULL,
    association_id uuid DEFAULT gen_random_uuid() NOT NULL,
    CONSTRAINT associations_check CHECK (((valid_to IS NULL) OR (valid_to >= valid_from))),
    CONSTRAINT associations_version_check CHECK ((version > 0))
);

CREATE TABLE audit (
    id uuid NOT NULL,
    actor_id uuid NOT NULL,
    action character varying(100) NOT NULL,
    resource_type character varying(100) NOT NULL,
    resource_id uuid NOT NULL,
    correlation_id uuid NOT NULL,
    details text NOT NULL,
    occurred_at timestamp with time zone NOT NULL
);

CREATE TABLE credentials (
    id uuid NOT NULL,
    device_id uuid NOT NULL,
    protected_value text NOT NULL,
    state character varying(30) NOT NULL,
    credential_version bigint NOT NULL,
    created_at timestamp with time zone NOT NULL,
    revoked_at timestamp with time zone,
    CONSTRAINT credentials_credential_version_check CHECK ((credential_version > 0)),
    CONSTRAINT credentials_state_check CHECK (((state)::text = ANY ((ARRAY['PENDING'::character varying, 'ACTIVE'::character varying, 'REVOKED'::character varying])::text[])))
);

CREATE TABLE device_provisionings (
    provisioning_id uuid NOT NULL,
    device_id uuid NOT NULL,
    credential_reference uuid NOT NULL,
    association_version bigint NOT NULL,
    broker_authorization character varying(30) NOT NULL,
    approved_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);

CREATE TABLE devices (
    id uuid NOT NULL,
    serial_number character varying(160) NOT NULL,
    account_id uuid NOT NULL,
    property_id uuid NOT NULL,
    point_id uuid NOT NULL,
    installation_id uuid NOT NULL,
    installer_id uuid NOT NULL,
    location character varying(500) NOT NULL,
    installed_at timestamp with time zone NOT NULL,
    status character varying(20) NOT NULL,
    association_version bigint NOT NULL,
    broker_confirmed boolean NOT NULL,
    telemetry_confirmed boolean NOT NULL,
    version bigint NOT NULL,
    firmware_version character varying(40),
    CONSTRAINT devices_association_version_check CHECK ((association_version > 0)),
    CONSTRAINT devices_check CHECK ((((status)::text <> 'ACTIVE'::text) OR (broker_confirmed AND telemetry_confirmed))),
    CONSTRAINT devices_status_check CHECK (((status)::text = ANY ((ARRAY['PENDING'::character varying, 'ACTIVE'::character varying, 'REVOKED'::character varying, 'FAILED'::character varying])::text[]))),
    CONSTRAINT devices_version_check CHECK ((version >= 0))
);

CREATE TABLE domain_outbox (
    event_id uuid NOT NULL,
    event_type character varying(80) NOT NULL,
    aggregate_id uuid NOT NULL,
    payload text NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    published_at timestamp with time zone,
    lease_until timestamp with time zone,
    claim_id uuid,
    available_at timestamp with time zone DEFAULT now() NOT NULL,
    correlation_id uuid DEFAULT gen_random_uuid() NOT NULL
);

CREATE TABLE installation_requests (
    request_id uuid NOT NULL,
    contact_name character varying(200) NOT NULL,
    contact_email character varying(254) NOT NULL,
    contact_phone character varying(30) NOT NULL,
    exact_location character varying(500) NOT NULL,
    purchase_order_id uuid,
    payment_verified_at timestamp with time zone,
    scheduled_window_start timestamp with time zone,
    scheduled_window_end timestamp with time zone,
    CONSTRAINT installation_requests_check CHECK (((scheduled_window_start IS NULL) = (scheduled_window_end IS NULL))),
    CONSTRAINT installation_requests_check1 CHECK ((scheduled_window_end > scheduled_window_start))
);

CREATE TABLE installations (
    id uuid NOT NULL,
    account_id uuid,
    property_id uuid,
    point_id uuid,
    installer_id uuid,
    status character varying(30) NOT NULL,
    device_id uuid,
    created_at timestamp with time zone NOT NULL,
    completed_at timestamp with time zone,
    version bigint NOT NULL,
    CONSTRAINT installation_installer_required CHECK ((((status)::text = ANY ((ARRAY['PROVISIONAL'::character varying, 'PENDING'::character varying, 'CANCELLED'::character varying])::text[])) OR (installer_id IS NOT NULL))),
    CONSTRAINT installation_target_required CHECK ((((status)::text = ANY ((ARRAY['PROVISIONAL'::character varying, 'PENDING'::character varying, 'CANCELLED'::character varying])::text[])) OR ((account_id IS NOT NULL) AND (property_id IS NOT NULL) AND (point_id IS NOT NULL)))),
    CONSTRAINT installations_check1 CHECK ((((status)::text = 'COMPLETED'::text) = (completed_at IS NOT NULL))),
    CONSTRAINT installations_check2 CHECK ((((status)::text <> 'COMPLETED'::text) OR (device_id IS NOT NULL))),
    CONSTRAINT installations_status_check CHECK (((status)::text = ANY ((ARRAY['PROVISIONAL'::character varying, 'PENDING'::character varying, 'ASSIGNED'::character varying, 'IN_PROGRESS'::character varying, 'COMPLETED'::character varying, 'CANCELLED'::character varying])::text[]))),
    CONSTRAINT installations_version_check CHECK ((version >= 0))
);

CREATE TABLE installer_assignments (
    assignment_id uuid NOT NULL,
    request_id uuid NOT NULL,
    installer_id uuid NOT NULL,
    assigned_at timestamp with time zone DEFAULT now() NOT NULL,
    active boolean DEFAULT true NOT NULL,
    source character varying(30) DEFAULT 'ASSIGNMENT'::character varying NOT NULL
);

CREATE TABLE installers (
    id uuid NOT NULL,
    first_name character varying(100) NOT NULL,
    last_name character varying(100) NOT NULL,
    identification character varying(50) NOT NULL,
    phone character varying(50) NOT NULL,
    email character varying(254),
    enabled boolean NOT NULL,
    version bigint NOT NULL,
    accreditation_code character varying(100),
    available boolean DEFAULT false NOT NULL,
    CONSTRAINT installers_version_check CHECK ((version >= 0))
);

CREATE TABLE integration_inbox (
    event_id uuid NOT NULL,
    event_type character varying(80) NOT NULL,
    aggregate_id uuid NOT NULL,
    payload_fingerprint character varying(64) NOT NULL,
    received_at timestamp with time zone DEFAULT now() NOT NULL
);

CREATE TABLE outbox_jobs (
    id uuid NOT NULL,
    type character varying(50) NOT NULL,
    aggregate_id uuid NOT NULL,
    payload text NOT NULL,
    status character varying(20) NOT NULL,
    created_at timestamp with time zone NOT NULL,
    available_at timestamp with time zone NOT NULL,
    attempts integer NOT NULL,
    claim_token uuid,
    lease_until timestamp with time zone,
    last_error character varying(1000),
    CONSTRAINT outbox_jobs_attempts_check CHECK ((attempts >= 0)),
    CONSTRAINT outbox_jobs_status_check CHECK (((status)::text = ANY ((ARRAY['PENDING'::character varying, 'PROCESSING'::character varying, 'DONE'::character varying])::text[])))
);

CREATE TABLE properties (
    id uuid NOT NULL,
    account_id uuid NOT NULL,
    name character varying(150) NOT NULL,
    address character varying(500) NOT NULL,
    property_type character varying(30) NOT NULL,
    active boolean NOT NULL,
    version bigint NOT NULL,
    CONSTRAINT properties_version_check CHECK ((version >= 0))
);

CREATE TABLE supply_points (
    id uuid NOT NULL,
    property_id uuid NOT NULL,
    serial_number character varying(100) NOT NULL,
    location_name character varying(200) NOT NULL,
    active boolean NOT NULL,
    version bigint NOT NULL,
    CONSTRAINT supply_points_version_check CHECK ((version >= 0))
);

ALTER TABLE ONLY associations
    ADD CONSTRAINT associations_pkey PRIMARY KEY (device_id, version);

ALTER TABLE ONLY audit
    ADD CONSTRAINT audit_pkey PRIMARY KEY (id);

ALTER TABLE ONLY credentials
    ADD CONSTRAINT credentials_device_id_credential_version_key UNIQUE (device_id, credential_version);

ALTER TABLE ONLY credentials
    ADD CONSTRAINT credentials_pkey PRIMARY KEY (id);

ALTER TABLE ONLY device_provisionings
    ADD CONSTRAINT device_provisionings_device_id_credential_reference_associa_key UNIQUE (device_id, credential_reference, association_version);

ALTER TABLE ONLY device_provisionings
    ADD CONSTRAINT device_provisionings_pkey PRIMARY KEY (provisioning_id);

ALTER TABLE ONLY devices
    ADD CONSTRAINT devices_pkey PRIMARY KEY (id);

ALTER TABLE ONLY devices
    ADD CONSTRAINT devices_serial_number_key UNIQUE (serial_number);

ALTER TABLE ONLY domain_outbox
    ADD CONSTRAINT domain_outbox_pkey PRIMARY KEY (event_id);

ALTER TABLE ONLY installation_requests
    ADD CONSTRAINT installation_requests_pkey PRIMARY KEY (request_id);

ALTER TABLE ONLY installation_requests
    ADD CONSTRAINT installation_requests_purchase_order_id_key UNIQUE (purchase_order_id);

ALTER TABLE ONLY installations
    ADD CONSTRAINT installations_pkey PRIMARY KEY (id);

ALTER TABLE ONLY installer_assignments
    ADD CONSTRAINT installer_assignments_pkey PRIMARY KEY (assignment_id);

ALTER TABLE ONLY installers
    ADD CONSTRAINT installers_identification_key UNIQUE (identification);

ALTER TABLE ONLY installers
    ADD CONSTRAINT installers_pkey PRIMARY KEY (id);

ALTER TABLE ONLY integration_inbox
    ADD CONSTRAINT integration_inbox_pkey PRIMARY KEY (event_id);

ALTER TABLE ONLY outbox_jobs
    ADD CONSTRAINT outbox_jobs_pkey PRIMARY KEY (id);

ALTER TABLE ONLY properties
    ADD CONSTRAINT properties_id_account_id_key UNIQUE (id, account_id);

ALTER TABLE ONLY properties
    ADD CONSTRAINT properties_pkey PRIMARY KEY (id);

ALTER TABLE ONLY supply_points
    ADD CONSTRAINT supply_points_id_property_id_key UNIQUE (id, property_id);

ALTER TABLE ONLY supply_points
    ADD CONSTRAINT supply_points_pkey PRIMARY KEY (id);

ALTER TABLE ONLY supply_points
    ADD CONSTRAINT supply_points_serial_number_key UNIQUE (serial_number);

CREATE UNIQUE INDEX active_installer_uq ON installer_assignments USING btree (installer_id) WHERE active;

CREATE UNIQUE INDEX active_request_assignment_uq ON installer_assignments USING btree (request_id) WHERE active;

CREATE UNIQUE INDEX association_current_idx ON associations USING btree (device_id) WHERE (valid_to IS NULL);

CREATE UNIQUE INDEX association_id_uq ON associations USING btree (association_id);

CREATE INDEX audit_resource_idx ON audit USING btree (resource_type, resource_id, occurred_at);

CREATE UNIQUE INDEX credential_current_idx ON credentials USING btree (device_id) WHERE ((state)::text = ANY ((ARRAY['PENDING'::character varying, 'ACTIVE'::character varying])::text[]));

CREATE UNIQUE INDEX device_active_point_idx ON devices USING btree (point_id) WHERE ((status)::text <> 'REVOKED'::text);

CREATE INDEX devices_property_state_idx ON devices USING btree (property_id, status, broker_confirmed);

CREATE UNIQUE INDEX installation_open_point_idx ON installations USING btree (point_id) WHERE ((status)::text <> ALL ((ARRAY['COMPLETED'::character varying, 'CANCELLED'::character varying])::text[]));

CREATE INDEX installations_property_status_idx ON installations USING btree (property_id, status);

CREATE UNIQUE INDEX installer_accreditation_uq ON installers USING btree (accreditation_code) WHERE (accreditation_code IS NOT NULL);

CREATE INDEX outbox_aggregate_idx ON outbox_jobs USING btree (aggregate_id, created_at);

CREATE INDEX outbox_pending_idx ON outbox_jobs USING btree (available_at, created_at) WHERE ((status)::text <> 'DONE'::text);

CREATE INDEX outbox_review_idx ON outbox_jobs USING btree (status, attempts, created_at, id);

CREATE INDEX properties_account_idx ON properties USING btree (account_id);

CREATE INDEX supply_points_property_idx ON supply_points USING btree (property_id);

ALTER TABLE ONLY associations
    ADD CONSTRAINT associations_device_id_fkey FOREIGN KEY (device_id) REFERENCES devices(id);

ALTER TABLE ONLY associations
    ADD CONSTRAINT associations_point_id_property_id_fkey FOREIGN KEY (point_id, property_id) REFERENCES supply_points(id, property_id);

ALTER TABLE ONLY associations
    ADD CONSTRAINT associations_property_id_account_id_fkey FOREIGN KEY (property_id, account_id) REFERENCES properties(id, account_id);

ALTER TABLE ONLY credentials
    ADD CONSTRAINT credentials_device_id_fkey FOREIGN KEY (device_id) REFERENCES devices(id);

ALTER TABLE ONLY device_provisionings
    ADD CONSTRAINT device_provisionings_credential_reference_fkey FOREIGN KEY (credential_reference) REFERENCES credentials(id);

ALTER TABLE ONLY device_provisionings
    ADD CONSTRAINT device_provisionings_device_id_fkey FOREIGN KEY (device_id) REFERENCES devices(id);

ALTER TABLE ONLY devices
    ADD CONSTRAINT devices_installation_id_fkey FOREIGN KEY (installation_id) REFERENCES installations(id);

ALTER TABLE ONLY devices
    ADD CONSTRAINT devices_installer_id_fkey FOREIGN KEY (installer_id) REFERENCES installers(id);

ALTER TABLE ONLY devices
    ADD CONSTRAINT devices_point_id_property_id_fkey FOREIGN KEY (point_id, property_id) REFERENCES supply_points(id, property_id);

ALTER TABLE ONLY devices
    ADD CONSTRAINT devices_property_id_account_id_fkey FOREIGN KEY (property_id, account_id) REFERENCES properties(id, account_id);

ALTER TABLE ONLY installations
    ADD CONSTRAINT installation_device_fk FOREIGN KEY (device_id) REFERENCES devices(id);

ALTER TABLE ONLY installation_requests
    ADD CONSTRAINT installation_requests_request_id_fkey FOREIGN KEY (request_id) REFERENCES installations(id);

ALTER TABLE ONLY installations
    ADD CONSTRAINT installations_installer_id_fkey FOREIGN KEY (installer_id) REFERENCES installers(id);

ALTER TABLE ONLY installations
    ADD CONSTRAINT installations_point_id_property_id_fkey FOREIGN KEY (point_id, property_id) REFERENCES supply_points(id, property_id);

ALTER TABLE ONLY installations
    ADD CONSTRAINT installations_property_id_account_id_fkey FOREIGN KEY (property_id, account_id) REFERENCES properties(id, account_id);

ALTER TABLE ONLY installer_assignments
    ADD CONSTRAINT installer_assignments_installer_id_fkey FOREIGN KEY (installer_id) REFERENCES installers(id);

ALTER TABLE ONLY installer_assignments
    ADD CONSTRAINT installer_assignments_request_id_fkey FOREIGN KEY (request_id) REFERENCES installations(id);

ALTER TABLE ONLY supply_points
    ADD CONSTRAINT supply_points_property_id_fkey FOREIGN KEY (property_id) REFERENCES properties(id);
