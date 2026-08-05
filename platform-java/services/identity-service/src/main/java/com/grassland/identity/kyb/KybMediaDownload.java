package com.grassland.identity.kyb;

import java.net.URI;
import java.time.Instant;

/** intelligence 签发的 KYB 证据短时下载地址。 */
public record KybMediaDownload(URI downloadUrl, Instant expiresAt) {}
