CREATE TABLE users (
    id         UUID         NOT NULL,
    name       VARCHAR(255) NOT NULL,
    email      VARCHAR(255) NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_users PRIMARY KEY (id),
    CONSTRAINT uq_users_email UNIQUE (email)
);

CREATE TABLE workspace (
    id         UUID         NOT NULL,
    name       VARCHAR(255) NOT NULL,
    created_by UUID         NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_workspace PRIMARY KEY (id),
    CONSTRAINT fk_workspace_created_by FOREIGN KEY (created_by) REFERENCES users (id) ON DELETE RESTRICT
);

CREATE TABLE workspace_member (
    id           UUID        NOT NULL,
    workspace_id UUID        NOT NULL,
    user_id      UUID        NOT NULL,
    role         VARCHAR(50) NOT NULL,
    CONSTRAINT pk_workspace_member PRIMARY KEY (id),
    CONSTRAINT fk_workspace_member_workspace FOREIGN KEY (workspace_id) REFERENCES workspace (id),
    CONSTRAINT fk_workspace_member_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT uq_workspace_member UNIQUE (workspace_id, user_id),
    CONSTRAINT chk_workspace_member_role CHECK (role IN ('OWNER', 'EDITOR', 'VIEWER'))
);

CREATE TABLE area (
    id              UUID                   NOT NULL,
    workspace_id    UUID                   NOT NULL,
    name            VARCHAR(255)           NOT NULL,
    geometry        geometry(POLYGON, 4326) NOT NULL,
    area_km2        NUMERIC(20, 6)         NOT NULL,
    status          VARCHAR(50)            NOT NULL,
    current_version INTEGER                NOT NULL,
    created_by      UUID                   NOT NULL,
    created_at      TIMESTAMPTZ            NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_area PRIMARY KEY (id),
    CONSTRAINT fk_area_workspace FOREIGN KEY (workspace_id) REFERENCES workspace (id),
    CONSTRAINT fk_area_created_by FOREIGN KEY (created_by) REFERENCES users (id),
    CONSTRAINT chk_area_status CHECK (status IN ('ACTIVE', 'DELETED')),
    CONSTRAINT chk_area_current_version CHECK (current_version >= 1),
    CONSTRAINT chk_area_area_km2 CHECK (area_km2 >= 0),
    CONSTRAINT chk_area_geometry_valid CHECK (ST_IsValid(geometry))
);

CREATE TABLE area_version (
    id          UUID                   NOT NULL,
    area_id     UUID                   NOT NULL,
    version     INTEGER                NOT NULL,
    name        VARCHAR(255)           NOT NULL,
    geometry    geometry(POLYGON, 4326) NOT NULL,
    area_km2    NUMERIC(20, 6)         NOT NULL,
    status      VARCHAR(50)            NOT NULL,
    change_type VARCHAR(50)            NOT NULL,
    created_by  UUID                   NOT NULL,
    created_at  TIMESTAMPTZ            NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_area_version PRIMARY KEY (id),
    CONSTRAINT fk_area_version_area FOREIGN KEY (area_id) REFERENCES area (id),
    CONSTRAINT fk_area_version_created_by FOREIGN KEY (created_by) REFERENCES users (id),
    CONSTRAINT uq_area_version UNIQUE (area_id, version),
    CONSTRAINT chk_area_version_version CHECK (version >= 1),
    CONSTRAINT chk_area_version_area_km2 CHECK (area_km2 >= 0),
    CONSTRAINT chk_area_version_status CHECK (status IN ('ACTIVE', 'DELETED')),
    CONSTRAINT chk_area_version_change_type CHECK (change_type IN ('CREATED', 'UPDATED', 'DELETED', 'RESTORED')),
    CONSTRAINT chk_area_version_geometry_valid CHECK (ST_IsValid(geometry))
);

-- B-tree index: primary spatial query access pattern filters by workspace_id before geometry
CREATE INDEX idx_area_workspace_id ON area (workspace_id);

-- Partial GiST index: Viewport spatial queries only operate on ACTIVE areas
CREATE INDEX idx_area_active_geometry ON area USING GIST (geometry) WHERE status = 'ACTIVE';
