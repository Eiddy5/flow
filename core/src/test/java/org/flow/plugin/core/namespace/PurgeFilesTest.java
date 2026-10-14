package org.flow.plugin.core.namespace;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import org.flow.core.context.TestRunContextFactory;
import org.flow.core.exceptions.IllegalVariableEvaluationException;
import org.flow.core.exceptions.ValidationErrorException;
import org.flow.core.junit.annotations.FlowTest;
import org.flow.core.models.FetchVersion;
import org.flow.core.models.flows.Flow;
import org.flow.core.models.flows.GenericFlow;
import org.flow.core.models.property.Property;
import org.flow.core.models.validations.ModelValidator;
import org.flow.core.namespace.NamespaceFileService;
import org.flow.core.repositories.FlowRepositoryInterface;
import org.flow.core.runners.RunContext;
import org.flow.core.storages.Namespace;
import org.flow.core.storages.NamespaceFile;
import org.flow.core.utils.IdUtils;
import org.flow.core.utils.TestsUtils;

import io.micronaut.data.model.Pageable;
import jakarta.inject.Inject;
import jakarta.validation.ConstraintViolationException;

import static org.flow.core.storages.NamespaceFile.toLogicalPath;
import static org.flow.core.tenant.TenantService.MAIN_TENANT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

@Execution(ExecutionMode.SAME_THREAD)
@FlowTest
public class PurgeFilesTest {
    public static final String PARENT_NAMESPACE = "parent";
    public static final String CHILD_NAMESPACE = "parent.child";
    public static final String NAMESPACE = "org.flow.tests";
    public static final String KEY = "file";

    @Inject
    TestRunContextFactory runContextFactory;

    @Inject
    FlowRepositoryInterface flowRepositoryInterface;

    @Inject
    ModelValidator modelValidator;

    @Inject
    NamespaceFileService namespaceFileService;

    @BeforeEach
    protected void setup() throws IOException {
        flowRepositoryInterface.findAll(MAIN_TENANT).forEach(flow -> flowRepositoryInterface.delete(flow));
    }

    @Test
    void should_find_all_namespaces() throws IllegalVariableEvaluationException {
        addNamespaces();

        PurgeFiles purgeFiles = PurgeFiles.builder()
            .type(PurgeFiles.class.getName())
            .build();
        List<String> namespaces = purgeFiles.findNamespaces(runContextFactory.of(NAMESPACE));

        assertThat(namespaces).containsExactlyInAnyOrder(NAMESPACE, CHILD_NAMESPACE, PARENT_NAMESPACE);
    }

    @Test
    void should_find_all_namespaces_with_glob_pattern() throws IllegalVariableEvaluationException {
        addNamespaces();

        PurgeFiles purgeFiles = PurgeFiles.builder()
            .type(PurgeFiles.class.getName())
            .namespacePattern(Property.ofValue("*arent*")) // codespell:ignore
            .build();
        List<String> namespaces = purgeFiles.findNamespaces(runContextFactory.of(NAMESPACE));

        assertThat(namespaces).containsExactlyInAnyOrder(CHILD_NAMESPACE, PARENT_NAMESPACE);
    }

    @Test
    void should_find_all_namespaces_with_namespace_list_without_child() throws IllegalVariableEvaluationException {
        addNamespaces();

        PurgeFiles purgeFiles = PurgeFiles.builder()
            .type(PurgeFiles.class.getName())
            .namespaces(Property.ofValue(List.of("ns1", "ns2", PARENT_NAMESPACE)))
            .includeChildNamespaces(Property.ofValue(false))
            .build();
        List<String> namespaces = purgeFiles.findNamespaces(runContextFactory.of(NAMESPACE));

        assertThat(namespaces).containsExactlyInAnyOrder(PARENT_NAMESPACE, "ns1", "ns2");
    }

    @Test
    void should_find_all_namespaces_with_namespace_list_with_child() throws IllegalVariableEvaluationException {
        addNamespaces();

        PurgeFiles purgeFiles = PurgeFiles.builder()
            .type(PurgeFiles.class.getName())
            .namespaces(Property.ofValue(List.of("ns1", "ns2", PARENT_NAMESPACE)))
            .includeChildNamespaces(Property.ofValue(true))
            .build();
        List<String> namespaces = purgeFiles.findNamespaces(runContextFactory.of(NAMESPACE));

        assertThat(namespaces).containsExactlyInAnyOrder(PARENT_NAMESPACE, CHILD_NAMESPACE, "ns1", "ns2");
    }

    @Test
    void should_not_find_namespaces_with_incorrect_parameters() {
        PurgeFiles purgeFiles = PurgeFiles.builder()
            .type(PurgeFiles.class.getName())
            .namespaces(Property.ofValue(List.of("ns1", "ns2", PARENT_NAMESPACE)))
            .namespacePattern(Property.ofValue("*par*"))
            .build();
        assertThrows(ValidationErrorException.class, () -> purgeFiles.findNamespaces(runContextFactory.of(NAMESPACE)));
    }

    @Test
    void should_delete_every_files_matching_pattern() throws Exception {
        String namespace = "org.flow." + IdUtils.create();
        addNamespace(namespace);

        RunContext runContext = runContextFactory.of(namespace);
        Namespace namespaceStorage = runContext.storage().namespace(namespace);
        namespaceStorage.putFile(Path.of("my/first/file.txt"), new ByteArrayInputStream("unused".getBytes(StandardCharsets.UTF_8)));
        namespaceStorage.putFile(Path.of("my/second/file.txt"), new ByteArrayInputStream("unused".getBytes(StandardCharsets.UTF_8)));
        namespaceStorage.putFile(Path.of("not/found.txt"), new ByteArrayInputStream("unused".getBytes(StandardCharsets.UTF_8)));

        PurgeFiles purgeFiles = PurgeFiles.builder()
            .type(PurgeFiles.class.getName())
            .filePattern(Property.ofValue("*il*"))
            .behavior(Property.ofValue(Version.builder().keepAmount(0).build()))
            .build();
        PurgeFiles.Output output = purgeFiles.run(runContext);

        assertThat(output.getSize()).isEqualTo(2L);
        List<NamespaceFile> namespaceFiles = namespaceStorage.all();
        assertThat(namespaceFiles.size()).isEqualTo(1);
        assertThat(toLogicalPath(namespaceFiles.getFirst().path())).isEqualTo("not/found.txt");
    }

    @Test
    void version_filter_by_date() throws Exception {
        String namespace = TestsUtils.randomNamespace();
        addNamespace(namespace);

        RunContext runContext = runContextFactory.of(namespace);
        Namespace namespaceStorage = runContext.storage().namespace(namespace);

        namespaceStorage.putFile(Path.of("my/first/file.txt"), new ByteArrayInputStream("some value".getBytes(StandardCharsets.UTF_8)));
        Instant afterFirstVersion = Instant.now();
        namespaceStorage.putFile(Path.of("my/first/file.txt"), new ByteArrayInputStream("another value".getBytes(StandardCharsets.UTF_8)));

        List<NamespaceFile> namespaceFiles = namespaceFileService.find(Pageable.UNPAGED, MAIN_TENANT, namespace, Collections.emptyList(), true, FetchVersion.ALL);
        // "/", "/my", "/my/first", "/my/first/file.txt" x2 versions
        assertThat(namespaceFiles.size()).isEqualTo(5);

        PurgeFiles purgeFiles = PurgeFiles.builder()
            .type(PurgeFiles.class.getName())
            .behavior(Property.ofValue(Version.builder().before(afterFirstVersion.toString()).build()))
            .build();
        PurgeFiles.Output run = purgeFiles.run(runContext);

        assertThat(run.getSize()).isEqualTo(1L);

        namespaceFiles = namespaceFileService.find(Pageable.UNPAGED, MAIN_TENANT, namespace, Collections.emptyList(), true, FetchVersion.ALL);
        assertThat(namespaceFiles.size()).isEqualTo(4);
        List<NamespaceFile> files = namespaceFiles.stream().filter(nsFile -> nsFile.path().endsWith("file.txt")).toList();
        assertThat(files.size()).isEqualTo(1);
        assertThat(files.getFirst().revision()).isEqualTo(2);
    }

    @Test
    void version_filter_by_keep_amount() throws Exception {
        String namespace = TestsUtils.randomNamespace();
        addNamespace(namespace);

        RunContext runContext = runContextFactory.of(namespace);
        Namespace namespaceStorage = runContext.storage().namespace(namespace);

        namespaceStorage.putFile(Path.of("my/first/file.txt"), new ByteArrayInputStream("some value".getBytes(StandardCharsets.UTF_8)));
        namespaceStorage.putFile(Path.of("my/first/file.txt"), new ByteArrayInputStream("another value".getBytes(StandardCharsets.UTF_8)));
        namespaceStorage.putFile(Path.of("my/first/file.txt"), new ByteArrayInputStream("yet another value".getBytes(StandardCharsets.UTF_8)));

        List<NamespaceFile> namespaceFiles = namespaceFileService.find(Pageable.UNPAGED, MAIN_TENANT, namespace, Collections.emptyList(), true, FetchVersion.ALL);
        assertThat(namespaceFiles.size()).isEqualTo(6);

        PurgeFiles purgeFiles = PurgeFiles.builder()
            .type(PurgeFiles.class.getName())
            .behavior(Property.ofValue(Version.builder().keepAmount(2).build()))
            .build();
        PurgeFiles.Output run = purgeFiles.run(runContext);

        assertThat(run.getSize()).isEqualTo(1L);

        namespaceFiles = namespaceFileService.find(Pageable.UNPAGED, MAIN_TENANT, namespace, Collections.emptyList(), true, FetchVersion.ALL);
        assertThat(namespaceFiles.size()).isEqualTo(5);
        List<NamespaceFile> files = namespaceFiles.stream().filter(nsFile -> nsFile.path().endsWith("file.txt")).toList();
        assertThat(files.size()).isEqualTo(2);
        assertThat(files.stream().map(NamespaceFile::revision)).containsExactlyInAnyOrder(2, 3);
    }

    @Test
    void validation() throws Exception {
        // valid
        assertThat(
            modelValidator.isValid(
                PurgeFiles.builder()
                    .id(IdUtils.create())
                    .type(PurgeFiles.class.getName())
                    .behavior(Property.ofValue(Version.builder().before(Instant.now().toString()).build()))
                    .build()
            ).isPresent()
        ).isFalse();
        assertThat(
            modelValidator.isValid(
                PurgeFiles.builder()
                    .id(IdUtils.create())
                    .type(PurgeFiles.class.getName())
                    .behavior(Property.ofValue(Version.builder().keepAmount(2).build()))
                    .build()
            ).isPresent()
        ).isFalse();

        // invalid
        Optional<ConstraintViolationException> invalid = modelValidator.isValid(
            PurgeFiles.builder()
                .id(IdUtils.create())
                .type(PurgeFiles.class.getName())
                .behavior(Property.ofValue(Version.builder().before(Instant.now().toString()).keepAmount(2).build()))
                .build()
        );
        assertThat(invalid.isPresent()).isTrue();
        assertThat(invalid.get().getMessage()).contains("behavior: Cannot set both 'before' and 'keepAmount' properties");
    }

    private void addNamespaces() {
        addNamespace(NAMESPACE);
        addNamespace(PARENT_NAMESPACE);
        addNamespace(CHILD_NAMESPACE);
    }

    private void addNamespace(String namespace) {
        flowRepositoryInterface.create(
            GenericFlow.of(
                Flow.builder()
                    .tenantId(MAIN_TENANT)
                    .namespace(namespace)
                    .id("flow1")
                    .tasks(List.of(PurgeFiles.builder().type(PurgeFiles.class.getName()).build()))
                    .build()
            )
        );
    }

}
