package com.grassland.identity.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

class IdentityAuthorizationManifestTest {

    private static final String BASE_PACKAGE = "com.grassland.identity";

    @Test
    void everyControllerAndMappedMethodHasAnExplicitPolicy() throws Exception {
        Map<String, Class<?>> controllers = discoverControllers();
        Map<String, IdentityAuthorizationManifest.ControllerPolicy> manifest =
                IdentityAuthorizationManifest.controllers();

        assertThat(manifest.keySet()).containsExactlyInAnyOrderElementsOf(controllers.keySet());

        for (Class<?> controller : controllers.values()) {
            IdentityAuthorizationManifest.ControllerPolicy policy = manifest.get(controller.getName());
            Set<String> mappedMethods = new LinkedHashSet<>();
            for (Method method : controller.getDeclaredMethods()) {
                RequestMapping mapping = AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class);
                if (mapping == null) {
                    continue;
                }
                mappedMethods.add(method.getName());
                IdentityAccessLevel level = policy.levelFor(method.getName());
                for (String path : fullPaths(controller, mapping)) {
                    assertPathPolicy(path, level, controller, method);
                }
            }
            assertThat(policy.methodOverrides().keySet())
                    .as("method policy overrides for %s", controller.getName())
                    .isSubsetOf(mappedMethods);
        }
    }

    private static void assertPathPolicy(String path, IdentityAccessLevel level,
                                         Class<?> controller, Method method) {
        String location = controller.getSimpleName() + "#" + method.getName() + " " + path;
        if (path.startsWith("/internal/")) {
            assertThat(level).as(location).isEqualTo(IdentityAccessLevel.SERVICE);
            return;
        }
        if (path.startsWith("/api/admin/")) {
            assertThat(level).as(location)
                    .isIn(IdentityAccessLevel.ADMIN, IdentityAccessLevel.BACKEND_ROLE);
            return;
        }
        if (path.equals("/api/auth/refresh") || path.equals("/api/auth/revoke")) {
            assertThat(level).as(location).isEqualTo(IdentityAccessLevel.TOKEN_AUTHENTICATED);
            return;
        }
        if (path.startsWith("/api/me/")) {
            assertThat(level).as(location)
                    .isNotIn(IdentityAccessLevel.PUBLIC, IdentityAccessLevel.TOKEN_AUTHENTICATED);
        }
        if (level == IdentityAccessLevel.PUBLIC) {
            assertThat(isReviewedPublicPath(path)).as(location).isTrue();
        }
    }

    private static boolean isReviewedPublicPath(String path) {
        return path.startsWith("/api/auth/")
                || path.matches("/api/stores/[^/]+/public-(?:profile|media)");
    }

    private static Set<String> fullPaths(Class<?> controller, RequestMapping methodMapping) {
        RequestMapping classMapping = AnnotatedElementUtils.findMergedAnnotation(controller, RequestMapping.class);
        String[] prefixes = classMapping == null || classMapping.path().length == 0
                ? new String[] {""} : classMapping.path();
        String[] suffixes = methodMapping.path().length == 0
                ? new String[] {""} : methodMapping.path();
        Set<String> paths = new LinkedHashSet<>();
        for (String prefix : prefixes) {
            for (String suffix : suffixes) {
                paths.add(normalizePath(prefix, suffix));
            }
        }
        return paths;
    }

    private static String normalizePath(String prefix, String suffix) {
        String path = (prefix + "/" + suffix).replaceAll("/+", "/");
        return path.length() > 1 && path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
    }

    private static Map<String, Class<?>> discoverControllers() throws Exception {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        Map<String, Class<?>> controllers = new LinkedHashMap<>();
        for (var candidate : scanner.findCandidateComponents(BASE_PACKAGE)) {
            Class<?> type = Class.forName(candidate.getBeanClassName());
            controllers.put(type.getName(), type);
        }
        return controllers;
    }
}
