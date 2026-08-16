-- D-07: optional per-store, per-time-slot inventory. The legacy aggregate inventory remains
-- the compatibility path for versions without slots.
CREATE TABLE commerce_package_inventory_slot (
    id uuid PRIMARY KEY,
    package_version_id uuid NOT NULL REFERENCES commerce_package_version(id),
    store_id uuid,
    slot_start timestamptz NOT NULL,
    slot_end timestamptz NOT NULL,
    total_stock int NOT NULL CHECK (total_stock >= 0),
    remaining_stock int NOT NULL CHECK (remaining_stock >= 0 AND remaining_stock <= total_stock),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CHECK (slot_end > slot_start),
    UNIQUE (package_version_id, store_id, slot_start, slot_end)
);
CREATE INDEX idx_commerce_inventory_slot_lookup
    ON commerce_package_inventory_slot(package_version_id, store_id, slot_start);

ALTER TABLE consumer_order ADD COLUMN inventory_slot_id uuid REFERENCES commerce_package_inventory_slot(id);
