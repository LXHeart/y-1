package com.grassland.marketplace.taskcatalog;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

class TaskArchitectureTest {
	@Test
	void httpAdaptersCannotOwnPersistenceFinanceOrTransactions() {
		var classes = new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
				.importClasses(TaskController.class, TaskQueryController.class);
		noClasses().should().dependOnClassesThat().haveSimpleNameEndingWith("Repository").check(classes);
		noClasses().should().dependOnClassesThat().haveSimpleName("TransactionalOperator").check(classes);
		noClasses().should().dependOnClassesThat()
				.resideInAnyPackage("com.grassland.marketplace.workflow..", "com.grassland.marketplace.event..")
				.check(classes);
	}

	@Test
	void commandServicesCannotDependOnHttpAdaptersOrReadModels() {
		var classes = new ClassFileImporter().importClasses(TaskDraftService.class, TaskPublicationService.class,
				TaskRevisionService.class, TaskCancellationService.class, TaskPromotionService.class,
				TaskWritePolicy.class);
		noClasses().should().dependOnClassesThat().haveSimpleNameEndingWith("Controller").check(classes);
		noClasses().should().dependOnClassesThat().haveSimpleName("TaskQueryService").check(classes);
		noClasses().should().dependOnClassesThat().haveSimpleName("TaskFeedService").check(classes);
		noClasses().should().dependOnClassesThat()
				.resideInAnyPackage("org.springframework.http..", "org.springframework.web..").check(classes);
	}
}
