--liquibase formatted sql

--changeset revenium:seed-001-tenants
INSERT INTO tenant (id, name, currency) VALUES
    ('tenant-a', 'Tenant A (demo)', 'USD'),
    ('tenant-b', 'Tenant B (demo)', 'EUR');

--changeset revenium:seed-002-price-rules
-- tenant-a has a price change on 2026-08-01 to demonstrate effective dating.
INSERT INTO price_rule (id, tenant_id, transaction_code, unit_price, effective_from, effective_to, created_at) VALUES
    ('00000000-0000-0000-0000-00000000a001', 'tenant-a', 'VEHICLE_REGISTRATION', 12.500000,
     '2026-01-01T00:00:00Z', '2026-08-01T00:00:00Z', '2025-12-15T00:00:00Z'),
    ('00000000-0000-0000-0000-00000000a002', 'tenant-a', 'VEHICLE_REGISTRATION', 13.750000,
     '2026-08-01T00:00:00Z', NULL, '2026-07-15T00:00:00Z'),
    ('00000000-0000-0000-0000-00000000a003', 'tenant-a', 'TITLE_TRANSFER', 7.250000,
     '2026-01-01T00:00:00Z', NULL, '2025-12-15T00:00:00Z'),
    ('00000000-0000-0000-0000-00000000b001', 'tenant-b', 'VEHICLE_REGISTRATION', 11.000000,
     '2026-01-01T00:00:00Z', NULL, '2025-12-15T00:00:00Z');
