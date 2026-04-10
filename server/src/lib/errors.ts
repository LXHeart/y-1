export class AppError extends Error {
  readonly statusCode: number
  readonly expose: boolean

  constructor(message: string, statusCode = 500, expose = true) {
    super(message)
    this.name = 'AppError'
    this.statusCode = statusCode
    this.expose = expose
  }
}
