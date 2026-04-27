import { beforeEach, describe, expect, it, vi } from 'vitest'
import { AppError } from '../lib/errors.js'

const { queryDbMock, hashPasswordMock, verifyPasswordMock } = vi.hoisted(() => ({
  queryDbMock: vi.fn(),
  hashPasswordMock: vi.fn(),
  verifyPasswordMock: vi.fn(),
}))

vi.mock('../lib/db.js', () => ({
  queryDb: queryDbMock,
}))

vi.mock('../lib/password.js', () => ({
  hashPassword: hashPasswordMock,
  verifyPassword: verifyPasswordMock,
}))

const {
  authenticateUser,
  createUser,
  findUserByEmail,
  getAuthUserProfile,
  registerUser,
  recordUserLogin,
} = await import('./user.service.js')

describe('user.service', () => {
  beforeEach(() => {
    queryDbMock.mockReset()
    hashPasswordMock.mockReset()
    verifyPasswordMock.mockReset()
  })

  it('createUser persists a normalized email with a hashed password and defaults role to user', async () => {
    hashPasswordMock.mockResolvedValue('hashed-password')
    queryDbMock.mockResolvedValue({
      rows: [{
        id: 'user-1',
        email: 'user@example.com',
        password_hash: 'hashed-password',
        display_name: '测试用户',
        role: 'user',
        status: 'active',
        created_at: '2026-04-19T00:00:00.000Z',
        last_login_at: null,
      }],
    })

    const user = await createUser({
      email: '  User@Example.com ',
      password: 'password123',
      displayName: '测试用户',
    })

    expect(hashPasswordMock).toHaveBeenCalledWith('password123')
    expect(queryDbMock).toHaveBeenCalledWith(expect.stringContaining('insert into app_users'), [
      expect.any(String),
      'user@example.com',
      'hashed-password',
      '测试用户',
      'user',
    ])
    expect(user).toEqual({
      id: 'user-1',
      email: 'user@example.com',
      displayName: '测试用户',
      role: 'user',
      status: 'active',
      createdAt: '2026-04-19T00:00:00.000Z',
      lastLoginAt: undefined,
    })
  })

  it('registerUser always persists the user role', async () => {
    hashPasswordMock.mockResolvedValue('hashed-password')
    queryDbMock.mockResolvedValue({
      rows: [{
        id: 'user-2',
        email: 'new@example.com',
        password_hash: 'hashed-password',
        display_name: null,
        role: 'user',
        status: 'active',
        created_at: '2026-04-19T00:00:00.000Z',
        last_login_at: null,
      }],
    })

    const user = await registerUser({
      email: 'new@example.com',
      password: 'password123',
    })

    expect(queryDbMock).toHaveBeenCalledWith(expect.any(String), [
      expect.any(String),
      'new@example.com',
      'hashed-password',
      null,
      'user',
    ])
    expect(user.role).toBe('user')
  })

  it('createUser rejects duplicate emails when insert returns no row', async () => {
    hashPasswordMock.mockResolvedValue('hashed-password')
    queryDbMock.mockResolvedValue({ rows: [] })

    await expect(createUser({
      email: 'duplicate@example.com',
      password: 'password123',
    })).rejects.toMatchObject({
      message: '该邮箱已存在',
      statusCode: 409,
    })
  })

  it('findUserByEmail returns a normalized user with password hash', async () => {
    queryDbMock.mockResolvedValue({
      rows: [{
        id: 'user-1',
        email: 'user@example.com',
        password_hash: 'hashed-password',
        display_name: '用户',
        role: 'admin',
        status: 'active',
        created_at: '2026-04-19T00:00:00.000Z',
        last_login_at: '2026-04-19T01:00:00.000Z',
      }],
    })

    const user = await findUserByEmail(' User@Example.com ')

    expect(queryDbMock).toHaveBeenCalledWith(expect.stringContaining('from app_users'), ['user@example.com'])
    expect(user).toEqual({
      id: 'user-1',
      email: 'user@example.com',
      displayName: '用户',
      role: 'admin',
      status: 'active',
      createdAt: '2026-04-19T00:00:00.000Z',
      lastLoginAt: '2026-04-19T01:00:00.000Z',
      passwordHash: 'hashed-password',
    })
  })

  it('recordUserLogin updates last login timestamp', async () => {
    queryDbMock.mockResolvedValue({ rows: [] })

    await recordUserLogin('user-1')

    expect(queryDbMock).toHaveBeenCalledWith(expect.stringContaining('set last_login_at = now()'), ['user-1'])
  })

  it('authenticateUser returns the refreshed user profile when credentials are valid', async () => {
    queryDbMock
      .mockResolvedValueOnce({
        rows: [{
          id: 'user-1',
          email: 'user@example.com',
          password_hash: 'stored-hash',
          display_name: '旧名称',
          role: 'user',
          status: 'active',
          created_at: '2026-04-19T00:00:00.000Z',
          last_login_at: null,
        }],
      })
      .mockResolvedValueOnce({ rows: [] })
      .mockResolvedValueOnce({
        rows: [{
          id: 'user-1',
          email: 'user@example.com',
          password_hash: 'stored-hash',
          display_name: '新名称',
          role: 'user',
          status: 'active',
          created_at: '2026-04-19T00:00:00.000Z',
          last_login_at: '2026-04-19T01:00:00.000Z',
        }],
      })
    verifyPasswordMock.mockResolvedValue(true)

    const user = await authenticateUser('USER@example.com', 'password123')

    expect(verifyPasswordMock).toHaveBeenCalledWith('password123', 'stored-hash')
    expect(user).toEqual({
      id: 'user-1',
      email: 'user@example.com',
      displayName: '新名称',
      role: 'user',
      status: 'active',
      createdAt: '2026-04-19T00:00:00.000Z',
      lastLoginAt: '2026-04-19T01:00:00.000Z',
    })
  })

  it('authenticateUser falls back to the pre-login row when the refreshed user cannot be loaded', async () => {
    queryDbMock
      .mockResolvedValueOnce({
        rows: [{
          id: 'user-1',
          email: 'user@example.com',
          password_hash: 'stored-hash',
          display_name: '用户',
          role: 'user',
          status: 'active',
          created_at: '2026-04-19T00:00:00.000Z',
          last_login_at: null,
        }],
      })
      .mockResolvedValueOnce({ rows: [] })
      .mockResolvedValueOnce({ rows: [] })
    verifyPasswordMock.mockResolvedValue(true)

    const user = await authenticateUser('user@example.com', 'password123')

    expect(user).toEqual({
      id: 'user-1',
      email: 'user@example.com',
      displayName: '用户',
      role: 'user',
      status: 'active',
      createdAt: '2026-04-19T00:00:00.000Z',
      lastLoginAt: undefined,
    })
  })

  it('authenticateUser rejects unknown users', async () => {
    queryDbMock.mockResolvedValue({ rows: [] })

    await expect(authenticateUser('missing@example.com', 'password123')).rejects.toEqual(
      new AppError('邮箱或密码错误', 401),
    )
  })

  it('authenticateUser rejects inactive users before verifying password', async () => {
    queryDbMock.mockResolvedValue({
      rows: [{
        id: 'user-1',
        email: 'inactive@example.com',
        password_hash: 'stored-hash',
        display_name: null,
        role: 'user',
        status: 'disabled',
        created_at: '2026-04-19T00:00:00.000Z',
        last_login_at: null,
      }],
    })

    await expect(authenticateUser('inactive@example.com', 'password123')).rejects.toEqual(
      new AppError('邮箱或密码错误', 401),
    )
    expect(verifyPasswordMock).not.toHaveBeenCalled()
  })

  it('authenticateUser rejects invalid passwords', async () => {
    queryDbMock.mockResolvedValue({
      rows: [{
        id: 'user-1',
        email: 'user@example.com',
        password_hash: 'stored-hash',
        display_name: null,
        role: 'user',
        status: 'active',
        created_at: '2026-04-19T00:00:00.000Z',
        last_login_at: null,
      }],
    })
    verifyPasswordMock.mockResolvedValue(false)

    await expect(authenticateUser('user@example.com', 'wrongpass1')).rejects.toEqual(
      new AppError('邮箱或密码错误', 401),
    )
  })

  it('getAuthUserProfile returns the active user profile without password hash', async () => {
    queryDbMock.mockResolvedValue({
      rows: [{
        id: 'user-1',
        email: 'user@example.com',
        password_hash: 'stored-hash',
        display_name: '用户',
        role: 'admin',
        status: 'active',
        created_at: '2026-04-19T00:00:00.000Z',
        last_login_at: null,
      }],
    })

    const user = await getAuthUserProfile('user-1')

    expect(user).toEqual({
      id: 'user-1',
      email: 'user@example.com',
      displayName: '用户',
      role: 'admin',
      status: 'active',
      createdAt: '2026-04-19T00:00:00.000Z',
      lastLoginAt: undefined,
    })
  })

  it('getAuthUserProfile rejects missing users', async () => {
    queryDbMock.mockResolvedValue({ rows: [] })

    await expect(getAuthUserProfile('missing-user')).rejects.toEqual(
      new AppError('用户不存在', 401),
    )
  })

  it('getAuthUserProfile rejects inactive users', async () => {
    queryDbMock.mockResolvedValue({
      rows: [{
        id: 'user-1',
        email: 'inactive@example.com',
        password_hash: 'stored-hash',
        display_name: null,
        role: 'user',
        status: 'disabled',
        created_at: '2026-04-19T00:00:00.000Z',
        last_login_at: null,
      }],
    })

    await expect(getAuthUserProfile('user-1')).rejects.toEqual(
      new AppError('当前账号不可用', 403),
    )
  })
})
