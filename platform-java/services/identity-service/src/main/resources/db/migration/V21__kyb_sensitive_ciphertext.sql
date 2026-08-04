-- 草场身份域：KYB 敏感字段改存信封加密密文。GL-P3-MERCHANT-001。
--
-- V15 把法人身份证号声明为 varchar(32) 并注释「敏感信息，需加密」，但写入路径存的是明文；
-- 信封加密后的 Base64 密文（DEK_IV || DEK_Encrypted || Ciphertext || AuthTag）远超 32 字符，
-- 故放宽为 text。withdrawal_account.account_number_encrypted 本就是 text，无需改。
--
-- 无 backfill：这批表由 9def15e 新建、尚未切流也无生产数据，不存在待加密的历史明文。
-- 若将来发现存量明文，须单独写一次性 backfill 任务（读明文→加密→回写），不在此迁移内。
ALTER TABLE merchant_profile
    ALTER COLUMN legal_person_id_number TYPE text;

COMMENT ON COLUMN merchant_profile.legal_person_id_number IS
    '法人身份证号密文（platform-crypto 信封加密，KybFieldCrypto 是唯一写入通道；响应体只回末 4 位掩码）';

COMMENT ON COLUMN withdrawal_account.account_number_encrypted IS
    '收款账号密文（platform-crypto 信封加密，KybFieldCrypto 是唯一写入通道；响应体只回末 4 位掩码）';
