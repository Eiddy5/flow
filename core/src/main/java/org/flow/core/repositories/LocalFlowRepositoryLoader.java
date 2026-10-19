package org.flow.core.repositories;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

import org.apache.commons.io.FileUtils;

import org.flow.core.exceptions.FlowProcessingException;
import org.flow.core.models.flows.FlowInterface;
import org.flow.core.models.flows.FlowWithSource;
import org.flow.core.models.flows.GenericFlow;
import org.flow.core.queues.QueueException;
import org.flow.core.serializers.YamlParser;
import org.flow.core.services.FlowService;
import org.flow.core.utils.Rethrow;
import org.flow.core.utils.SessionUtil;
import org.paas.session.Session;
import org.paas.session.SessionUser;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.extern.slf4j.Slf4j;

import static org.flow.core.utils.Rethrow.throwConsumer;

@Singleton
@Slf4j
public class LocalFlowRepositoryLoader {

    @Inject
    private FlowRepositoryInterface flowRepository;

    @Inject
    private FlowService flowService;

    /**
     * 将 URL 目录中的流程导入会话租户，支持文件和 JAR 内资源。
     * @param session 具有企业和操作者的宿主会话，不能为 null
     * @param basePath YAML 文件或目录 URL，不能为 null
     * @return 成功保存的流程；单个定义校验失败时记录日志并跳过
     * @throws IOException 资源读取失败
     * @throws URISyntaxException URL 不能转换为 URI
     */
    public <S extends Session<U>, U extends SessionUser> List<FlowWithSource> load(S session, URL basePath) throws IOException, URISyntaxException {
        SessionUtil.user(session);
        URI uri = basePath.toURI();

        if (uri.getScheme().equals("jar")) {
            try (FileSystem fileSystem = FileSystems.newFileSystem(uri, Collections.emptyMap())) {
                String substring = uri.toString().substring(uri.toString().indexOf("!") + 1);

                Path tempDirectory = Files.createTempDirectory("loader");

                for (Path path1 : fileSystem.getRootDirectories()) {
                    try (var files = Files.walk(path1)) {
                        files.filter(path -> Files.isRegularFile(path) && path.startsWith(substring))
                            .forEach(
                                throwConsumer(
                                    path -> FileUtils.copyURLToFile(
                                        path.toUri().toURL(),
                                        tempDirectory.resolve(path.toString().substring(1)).toFile()
                                    )
                                )
                            );
                    }
                }

                return this.load(session, tempDirectory.toFile());
            }
        } else {
            return this.load(session, Paths.get(uri).toFile());
        }
    }

    /**
     * 将目录中的流程创建或更新到会话租户，逐个保留成功导入的结果。
     * @param session 具有企业和操作者的宿主会话，不能为 null
     * @param basePath YAML 文件或目录，不能为 null
     * @return 按遍历顺序排列的成功保存流程，失败定义记录日志并跳过
     * @throws IOException 文件遍历或读取失败
     */
    public <S extends Session<U>, U extends SessionUser> List<FlowWithSource> load(S session, File basePath) throws IOException {
        SessionUtil.user(session);
        String tenantId = SessionUtil.company(session);
        List<FlowWithSource> loaded = new ArrayList<>();
        try (Stream<Path> pathStream = Files.walk(basePath.toPath())) {
            pathStream.filter(YamlParser::isValidExtension)
                .forEach(Rethrow.throwConsumer(file ->
                {
                    try {
                        String source = Files.readString(Path.of(file.toFile().getPath()), Charset.defaultCharset());
                        GenericFlow parsed = GenericFlow.fromYaml(tenantId, source);

                        FlowInterface existing = flowRepository.findById(tenantId, parsed.getNamespace(), parsed.getId()).orElse(null);

                        FlowWithSource persisted;
                        if (existing == null) {
                            persisted = flowService.create(session, parsed);
                            log.trace("Created flow {}.{}", parsed.getNamespace(), parsed.getId());
                        } else {
                            persisted = flowService.update(session, parsed);
                            log.trace("Updated flow {}.{}", parsed.getNamespace(), parsed.getId());
                        }

                        loaded.add(persisted);
                    } catch (FlowProcessingException | QueueException | RuntimeException e) {
                        log.warn("Unable to create flow {}", file, e);
                    }
                }));
        }

        return loaded;
    }
}
