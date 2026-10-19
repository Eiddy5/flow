package org.flow.plugin.core.preview;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.flow.plugin.api.annotations.Plugin;
import org.flow.core.preview.FilePreview;
import org.flow.core.preview.FileRenderer;
import org.flow.core.serializers.FileSerde;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

import static org.flow.core.utils.Rethrow.throwConsumer;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "ION file renderer",
    description = "Preview ION files inside the Flow UI."
)
@Plugin
public class IonFileRenderer implements FileRenderer {
    @Override
    public boolean supports(String extension) {
        return "ion".equalsIgnoreCase(extension);
    }

    @Override
    public Set<String> extensions() {
        return Set.of("ion");
    }

    @Override
    public FilePreview render(String extension, InputStream inputStream, Optional<Charset> charset, int maxRows) throws IOException {
        if (!supports(extension)) {
            throw new IllegalArgumentException("Unsupported extension: " + extension);
        }

        try (InputStream bis = new BufferedInputStream(inputStream, FileSerde.BUFFER_SIZE)) {
            List<Object> list = new ArrayList<>();
            boolean truncated = FileSerde.read(bis, maxRows, throwConsumer(list::add));

            return FilePreview.builder()
                .content(list)
                .truncated(truncated)
                .extension(extension)
                .type(FilePreview.Type.LIST)
                .build();
        }
    }
}
