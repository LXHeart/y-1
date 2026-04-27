-- Extend user_settings to support per-user image review style preferences.
ALTER TABLE user_settings DROP CONSTRAINT user_settings_type_check;
ALTER TABLE user_settings ADD CONSTRAINT user_settings_type_check
  CHECK (settings_type IN ('analysis', 'homepage', 'image-review-style'));
