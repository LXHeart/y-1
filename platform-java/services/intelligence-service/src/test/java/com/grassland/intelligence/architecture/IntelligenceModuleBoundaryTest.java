package com.grassland.intelligence.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.grassland.intelligence.IntelligenceServiceApplication;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/**
 * Bytecode boundary ratchet. Never refresh the checked-in debt list
 * automatically.
 */
class IntelligenceModuleBoundaryTest {
	record Module(String id, List<String> packages, List<String> allows, String responsibility) {
	}
	record Definition(int schemaVersion, String basePackage, List<Module> modules) {
	}

	@Test
	void everyProductionPackageHasAnOwnerAndNoNewBoundaryDebt() throws Exception {
		var mapper = new ObjectMapper();
		Definition definition;
		String[] existingDebt;
		try (var stream = getClass().getResourceAsStream("/architecture/intelligence-modules.v1.json")) {
			definition = mapper.readValue(stream, Definition.class);
		}
		try (var stream = getClass().getResourceAsStream("/architecture/module-debt.json")) {
			existingDebt = mapper.readValue(stream, String[].class);
		}
		assertThat(definition.schemaVersion()).isEqualTo(1);
		Map<String, Module> owners = new HashMap<>();
		Set<String> ids = new HashSet<>();
		for (Module module : definition.modules()) {
			assertThat(ids.add(module.id())).as("unique module: %s", module.id()).isTrue();
			assertThat(module.responsibility()).isNotBlank();
			for (String pkg : module.packages()) {
				assertThat(owners.put(pkg, module)).as("unique package owner: %s", pkg).isNull();
			}
		}
		for (Module module : definition.modules()) {
			assertThat(ids).containsAll(module.allows());
			assertThat(module.allows()).doesNotContain(module.id());
			assertAcyclic(module, definition.modules(), new HashSet<>());
		}
		// Import only this artifact's production bytecode, including fully qualified
		// and generic references.
		var classes = new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
				.importUrl(IntelligenceServiceApplication.class.getProtectionDomain().getCodeSource().getLocation());
		assertThat(classes.size()).isGreaterThan(700);
		Set<String> observedPackages = new HashSet<>();
		Set<String> actual = new TreeSet<>();
		for (var origin : classes) {
			if (!origin.getName().startsWith(definition.basePackage() + "."))
				continue;
			String fromPackage = rootPackage(origin.getPackageName(), definition.basePackage());
			Module from = owners.get(fromPackage);
			assertThat(from).as("unassigned package: %s", origin.getPackageName()).isNotNull();
			observedPackages.add(fromPackage);
			for (var dependency : origin.getDirectDependenciesFromSelf()) {
				var target = dependency.getTargetClass();
				if (!target.getName().startsWith(definition.basePackage() + "."))
					continue;
				Module to = owners.get(rootPackage(target.getPackageName(), definition.basePackage()));
				assertThat(to).as("unassigned target: %s", target.getName()).isNotNull();
				if (from.id().equals(to.id()))
					continue;
				String pair = origin.getName() + " -> " + target.getName();
				if (!from.allows().contains(to.id()))
					actual.add("direction: " + pair);
				String outerName = target.getName().split("\\$")[0];
				if (outerName.endsWith("Repository") || outerName.endsWith("Controller")
						|| outerName.endsWith("Worker")) {
					actual.add("internal: " + pair);
				}
			}
		}
		assertThat(observedPackages).containsExactlyInAnyOrderElementsOf(owners.keySet());
		// Diagnostic candidate only. Review each new edge; normal runs cannot edit the
		// baseline.
		Path report = Path.of("build/reports/architecture/module-debt.actual.json");
		Files.createDirectories(report.getParent());
		mapper.writerWithDefaultPrettyPrinter().writeValue(report.toFile(), actual);
		Set<String> expected = new TreeSet<>(Arrays.asList(existingDebt));
		assertThat(expected).hasSize(existingDebt.length);
		Set<String> added = new TreeSet<>(actual);
		added.removeAll(expected);
		Set<String> retired = new TreeSet<>(expected);
		retired.removeAll(actual);
		assertThat(added).as("new module debt; see %s", report).isEmpty();
		assertThat(retired).as("remove retired exceptions so they cannot be reintroduced").isEmpty();
	}

	private static String rootPackage(String pkg, String base) {
		return pkg.equals(base) ? "" : pkg.substring(base.length() + 1).split("\\.")[0];
	}

	private static void assertAcyclic(Module module, List<Module> modules, Set<String> path) {
		assertThat(path.add(module.id())).as("cyclic allowed dependencies: %s", path).isTrue();
		for (String id : module.allows()) {
			assertAcyclic(modules.stream().filter(candidate -> candidate.id().equals(id)).findFirst().orElseThrow(),
					modules, new HashSet<>(path));
		}
	}
}
