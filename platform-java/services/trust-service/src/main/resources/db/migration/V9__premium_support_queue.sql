-- P2 等级权益：争议开案时固化 application accept 时的专属客服权益，进行中案件不受后续降级影响。
ALTER TABLE dispute_case
    ADD COLUMN premium_support boolean NOT NULL DEFAULT false,
    ADD COLUMN support_priority integer NOT NULL DEFAULT 0;

ALTER TABLE dispute_case
    ADD CONSTRAINT ck_dispute_support_priority
        CHECK (support_priority BETWEEN 0 AND 100) NOT VALID;
ALTER TABLE dispute_case VALIDATE CONSTRAINT ck_dispute_support_priority;
