package com.toniflorea.ledger;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

@AnalyzeClasses(packages = "com.toniflorea.ledger", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

	@ArchTest
	static final ArchRule domainIsPureJava = classes()
			.that().resideInAPackage("..domain..")
			.should().onlyDependOnClassesThat().resideInAnyPackage("..domain..", "java..");

	@ArchTest
	static final ArchRule applicationKnowsNoAdaptersOrInfrastructure = noClasses()
			.that().resideInAPackage("..application..")
			.should().dependOnClassesThat().resideInAnyPackage("..adapter..", "..infrastructure..")
			.allowEmptyShould(true);

	@ArchTest
	static final ArchRule adaptersDoNotDependOnEachOther = slices()
			.matching("com.toniflorea.ledger.adapter.(*).(*)..")
			.should().notDependOnEachOther()
			.allowEmptyShould(true);

	@ArchTest
	static final ArchRule onlyInfrastructureAndAdaptersUseSpring = noClasses()
			.that().resideInAnyPackage("..domain..", "..application..")
			.should().dependOnClassesThat().resideInAPackage("org.springframework.web..")
			.allowEmptyShould(true);
}
