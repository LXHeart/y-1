import { createPinia, setActivePinia } from 'pinia'

setActivePinia(createPinia())

process.env.NODE_ENV = 'test'
