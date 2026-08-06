import { Router } from 'express'
import { getHomepageHotItemsHandler } from '../controllers/homepage.controller.js'

/**
 * 已迁 intelligence-service（Legacy 迁移收尾）。
 * `GET /api/homepage/hot-items` 现由 intelligence 的 {@code HomepageController} 承载
 * （60s 三平台聚合 + ALAPI 全网热点，缓存语义 1:1 复刻 `homepage-hot.service.ts`），
 * edge-bff 经 {@code EDGE_ROUTE_HOMEPAGE_INTELLIGENCE}（默认 true）切流。
 * 本 router 仅作回滚路径保留（flag=false 即回落 legacy）。
 *
 * ⚠️ Java 侧 60s 缓存落独立表 `intelligence_cached_hot_topics`（不共用 legacy 的 `cached_hot_topics`），
 * 两套缓存互不干扰——回滚后 legacy 仍读自己的表。
 */
export const homepageRouter = Router()

homepageRouter.get('/hot-items', getHomepageHotItemsHandler)
