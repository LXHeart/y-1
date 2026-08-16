package com.grassland.identity.recommenderprofile;

import java.net.URI;
import java.time.Instant;

/** intelligence 签发的头像短时下载地址。{@code expiresAt} 为媒体资产 TTL，非 URL 过期时间。 */
public record AvatarMediaDownload(URI downloadUrl, Instant expiresAt) {}
