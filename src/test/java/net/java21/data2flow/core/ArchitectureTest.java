package net.java21.data2flow.core;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import net.java21.data2flow.contracts.test.arch.Data2flowArchRules;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * IAM-01.01·NFR-05.01(TC-NFR-046)·BR-IAM-01: 리포지토리 조회에 조직 조건 강제, 그리고 design/testing/backend.md §6 공통 규칙.
 * AT-IAM-21.7: JWT는 data2flow-auth만 해석한다(core에 JWT 라이브러리 호출이 없다).
 */
@AnalyzeClasses(packages = "net.java21.data2flow.core", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    @ArchTest
    static final ArchRule organizationScoped = Data2flowArchRules.REPOSITORY_QUERIES_ARE_ORGANIZATION_SCOPED;
    @ArchTest
    static final ArchRule noUnscopedCrud = Data2flowArchRules.UNSCOPED_CRUD_LOOKUPS_ARE_NOT_CALLED;
    @ArchTest
    static final ArchRule noSleep = Data2flowArchRules.NO_THREAD_SLEEP;
    @ArchTest
    static final ArchRule noSystemClock = Data2flowArchRules.NO_SYSTEM_CLOCK;

    /** AT-IAM-21.7 · TC-IAM-187: JWT 파싱 라이브러리 호출이 auth 밖에 없다 */
    @ArchTest
    static final ArchRule noJwtParsing = noClasses().should().dependOnClassesThat()
            .resideInAnyPackage("io.jsonwebtoken..", "com.nimbusds.jose..", "com.auth0.jwt..")
            .allowEmptyShould(true);

    /** controller → service → repository 방향만(design/testing/backend.md §6) */
    @ArchTest
    static final ArchRule repositoriesDoNotUseServices = noClasses().that().resideInAPackage("..repository..")
            .should().dependOnClassesThat().resideInAnyPackage("..service..", "..controller..");

    @ArchTest
    static final ArchRule servicesDoNotUseControllers = noClasses().that().resideInAPackage("..service..")
            .should().dependOnClassesThat().resideInAPackage("..controller..")
            .allowEmptyShould(true);
}
