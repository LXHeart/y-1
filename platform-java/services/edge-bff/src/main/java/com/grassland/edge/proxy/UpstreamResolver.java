package com.grassland.edge.proxy;

import java.net.URI;
import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * 按 method + path 选择目标上游。先匹配 routes；生产默认策略为 fail-closed。
 */
@Component
public class UpstreamResolver {
	private final EdgeRoutingProperties properties;

	public UpstreamResolver(EdgeRoutingProperties properties) {
		this.properties = properties;
	}

	public URI resolve(String method, String path) {
		return properties.upstreams().get(resolveUpstreamName(method, path));
	}

	/** 命中 route 的上游名；未命中返回默认策略。供断言 filter 与代理 handler 判定。 */
	public String resolveUpstreamName(String method, String path) {
		if (method != null && path != null) {
			for (RouteProperties route : properties.routes()) {
				if (route.enabled() && matches(route, method, path)) {
					return route.upstream();
				}
			}
		}
		return properties.defaultUpstream();
	}

	/** 仅对已声明的 Java 上游签发内部断言；fail-closed 不签发。 */
	public boolean isInternalUpstream(String method, String path) {
		String upstream = resolveUpstreamName(method, path);
		return properties.upstreams().containsKey(upstream);
	}

	boolean matches(RouteProperties route, String method, String path) {
		if (route.method() != null && !route.method().isBlank() && !route.method().equalsIgnoreCase(method)) {
			return false;
		}
		String routePath = route.path();
		if (routePath == null || routePath.isBlank()) {
			return true;
		}
		if (route.exact()) {
			return matchesTemplate(routePath, path);
		}
		if (routePath.endsWith("/**")) {
			String prefix = routePath.substring(0, routePath.length() - 3);
			return path.equals(prefix) || path.startsWith(prefix + "/");
		}
		return path.equals(routePath) || path.startsWith(routePath + "/");
	}

	/**
	 * Template matching for exact routes (task-107 K09.1): a route path may use
	 * whole-segment {@code {identifier}} placeholders. Segments must match
	 * one-to-one; static segments compare literally; parameter segments must be
	 * non-empty and may not carry traversal, separators or NUL. Routes without
	 * placeholders keep the original equality semantics.
	 */
	boolean matchesTemplate(String routePath, String path) {
		if (routePath.indexOf('{') < 0) {
			return path.equals(routePath);
		}
		String[] routeSegments = routePath.split("/", -1);
		String[] pathSegments = path.split("/", -1);
		if (routeSegments.length != pathSegments.length) {
			return false;
		}
		for (int i = 0; i < routeSegments.length; i++) {
			String routeSegment = routeSegments[i];
			if (routeSegment.startsWith("{") && routeSegment.endsWith("}") && routeSegment.length() > 2) {
				String value = pathSegments[i];
				if (value.isEmpty() || value.equals(".") || value.equals("..") || value.indexOf('/') >= 0
						|| value.indexOf('\\') >= 0 || value.indexOf('\0') >= 0 || value.indexOf('%') >= 0) {
					// ids are UUID/slug shaped; percent-encoding (incl. %2F) is
					// not an id character and must not smuggle separators
					return false;
				}
			} else if (!routeSegment.equals(pathSegments[i])) {
				return false;
			}
		}
		return true;
	}
}
