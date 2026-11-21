-- UI login (docs/security.md): a password hash and a role per user. Both optional for existing rows:
-- a user without a password simply cannot log in; the role defaults to read-only VIEWER.
ALTER TABLE users
    ADD COLUMN password_hash VARCHAR(100),
    ADD COLUMN role          VARCHAR(20) NOT NULL DEFAULT 'VIEWER',
    ADD CONSTRAINT chk_users_role CHECK (role IN ('VIEWER', 'OPERATOR', 'ADMIN'));
