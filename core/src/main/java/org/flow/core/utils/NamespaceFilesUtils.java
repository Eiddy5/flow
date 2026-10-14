package org.flow.core.utils;

import java.io.InputStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.time.DurationFormatUtils;
import org.apache.commons.lang3.time.StopWatch;

import com.google.common.util.concurrent.ThreadFactoryBuilder;

import org.flow.core.contexts.FlowContext;
import org.flow.core.models.executions.metrics.Counter;
import org.flow.core.models.executions.metrics.Timer;
import org.flow.core.models.tasks.FileExistComportment;
import org.flow.core.models.tasks.NamespaceFiles;
import org.flow.core.runners.RunContext;
import org.flow.core.storages.Namespace;
import org.flow.core.storages.NamespaceFile;

import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import static org.flow.core.utils.Rethrow.throwConsumer;

public final class NamespaceFilesUtils {
    private static final int maxThreads = Math.max(FlowContext.getContext().getAllocatedCpuCores() * 4, 32);
    private static final ExecutorService EXECUTOR_SERVICE = new ThreadPoolExecutor(
        0,
        maxThreads,
        60L,
        TimeUnit.SECONDS,
        new LinkedBlockingQueue<>(),
        new ThreadFactoryBuilder().setNameFormat("namespace-files").build()
    );;

    private NamespaceFilesUtils() {
        // utility class pattern
    }

    public static void loadNamespaceFiles(
        RunContext runContext,
        NamespaceFiles namespaceFiles)
        throws Exception {
        StopWatch stopWatch = new StopWatch();
        stopWatch.start();

        List<String> include = runContext.render(namespaceFiles.getInclude()).asList(String.class);
        List<String> exclude = runContext.render(namespaceFiles.getExclude()).asList(String.class);
        FileExistComportment fileExistComportment = runContext.render(namespaceFiles.getIfExists())
            .as(FileExistComportment.class).orElse(FileExistComportment.OVERWRITE);
        List<String> namespaces = runContext.render(namespaceFiles.getNamespaces()).asList(String.class);
        Boolean folderPerNamespace = runContext.render(namespaceFiles.getFolderPerNamespace()).as(Boolean.class)
            .orElse(false);

        List<NamespaceFile> matchedNamespaceFiles = new ArrayList<>();
        Map<String, Namespace> namespaceStorages = new HashMap<>();
        for (String namespace : namespaces) {
            Namespace namespaceStorage = runContext.storage().namespace(namespace);
            namespaceStorages.put(namespace, namespaceStorage);

            matchedNamespaceFiles.addAll(namespaceStorage.findAllFilesMatching(include, exclude));
        }

        // Use half of the available threads to avoid impacting concurrent tasks
        int parallelism = maxThreads / 2;
        Flux.fromIterable(matchedNamespaceFiles)
            .parallel(parallelism)
            .runOn(Schedulers.fromExecutorService(EXECUTOR_SERVICE))
            .doOnNext(throwConsumer(nsFile ->
            {
                try (InputStream content = namespaceStorages.get(nsFile.namespace()).getFileContent(nsFile)) {
                    Path path = folderPerNamespace ? Path.of(nsFile.namespace() + "/" + nsFile.path()) : Path.of(nsFile.path());
                    runContext.workingDir().putFile(path, content, fileExistComportment);
                }
            }))
            .doOnError(t ->
            {
                runContext.logger().error("Error while loading namespace files", t);
            })
            .sequential()
            .blockLast();

        Duration duration = stopWatch.getDuration();

        runContext.metric(Counter.of("namespacefiles.count", matchedNamespaceFiles.size()));
        runContext.metric(Timer.of("namespacefiles.duration", duration));

        runContext.logger().info(
            "Loaded {} namespace files from '{}' in {}",
            matchedNamespaceFiles.size(),
            StringUtils.join(namespaces, ", "),
            DurationFormatUtils.formatDurationHMS(duration.toMillis())
        );
    }
}
