-- 草场身份域：门店详细资料。GL-P3-MERCHANT-001。
CREATE TABLE store_profile (
    store_id uuid PRIMARY KEY,                     -- 引用 store.id（逻辑引用，无 FK）
    address jsonb NOT NULL,                        -- 地址 {province,city,district,address,longitude,latitude}
    phone varchar(32),                             -- 联系电话
    business_hours jsonb,                          -- 营业时间 [{dayOfWeek,openTime,closeTime}]
    description text,                             -- 门店描述
    status varchar(32) NOT NULL DEFAULT 'active',  -- active/inactive
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_store_profile_store ON store_profile(store_id);
