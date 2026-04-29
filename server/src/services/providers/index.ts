import { cozeProvider } from './coze-provider.js'
import { qwenProvider, articleQwenProvider, streamComedyScript } from './qwen-provider.js'
import { registerProvider, getProvider, listProviders } from './registry.js'

registerProvider(cozeProvider)
registerProvider(qwenProvider)

export { getProvider, listProviders, articleQwenProvider, streamComedyScript }
