package com.grassland.intelligence.mediaplatform;

import com.grassland.intelligence.security.IntelligenceException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import org.springframework.stereotype.Component;

@Component
public class RemoteMediaDownloader {
    private static final Set<Integer> REDIRECTS = Set.of(301, 302, 303, 307, 308);

    public void download(URI source, Map<String, String> headers, Predicate<URI> guard, Path target, long maxBytes) {
        URI current = source;
        for (int redirects = 0; redirects <= 2; redirects++) {
            if (!guard.test(current)) throw new IntelligenceException(400, "媒体地址不受信任");
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) current.toURL().openConnection();
                connection.setInstanceFollowRedirects(false);
                connection.setConnectTimeout(15_000);
                connection.setReadTimeout(30_000);
                connection.setRequestProperty("Accept", "*/*");
                headers.forEach(connection::setRequestProperty);
                int status = connection.getResponseCode();
                if (REDIRECTS.contains(status)) {
                    String location = connection.getHeaderField("Location");
                    if (location == null || redirects == 2) throw new IntelligenceException(502, "媒体回源重定向无效");
                    current = current.resolve(location);
                    continue;
                }
                if (status >= 400) throw new IntelligenceException(502, "媒体回源失败（HTTP " + status + "）");
                long declared = connection.getContentLengthLong();
                if (declared > maxBytes) throw new IntelligenceException(413, "媒体文件过大，暂不支持处理");
                Files.createDirectories(target.getParent());
                try (InputStream input = connection.getInputStream();
                     var output = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW)) {
                    byte[] buffer = new byte[64 * 1024];
                    long total = 0;
                    for (int read; (read = input.read(buffer)) >= 0;) {
                        total += read;
                        if (total > maxBytes) throw new IntelligenceException(413, "媒体文件过大，暂不支持处理");
                        output.write(buffer, 0, read);
                    }
                }
                return;
            } catch (IntelligenceException error) {
                throw error;
            } catch (Exception error) {
                throw new IntelligenceException(502, "媒体回源下载失败，请稍后重试");
            } finally {
                if (connection != null) connection.disconnect();
            }
        }
        throw new IntelligenceException(502, "媒体回源重定向过多");
    }
}
