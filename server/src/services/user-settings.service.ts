import { randomUUID } from 'node:crypto'
import { queryDb } from '../lib/db.js'

export type UserSettingsType = 'analysis' | 'homepage' | 'image-review-style'

interface UserSettingsRow {
  settings_json: unknown
}

export async function loadUserSettingsRecord(
  userId: string,
  settingsType: UserSettingsType,
): Promise<unknown | undefined> {
  const result = await queryDb<UserSettingsRow>(
    `select settings_json
       from user_settings
      where user_id = $1 and settings_type = $2
      limit 1`,
    [userId, settingsType],
  )

  return result.rows[0]?.settings_json
}

export async function saveUserSettingsRecord(
  userId: string,
  settingsType: UserSettingsType,
  settingsJson: unknown,
): Promise<void> {
  await queryDb(
    `insert into user_settings (id, user_id, settings_type, settings_json)
     values ($1, $2, $3, $4::jsonb)
     on conflict (user_id, settings_type)
     do update set
       settings_json = excluded.settings_json,
       version = user_settings.version + 1,
       updated_at = now()`,
    [randomUUID(), userId, settingsType, JSON.stringify(settingsJson)],
  )
}
