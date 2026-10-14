package org.flow.plugin.core.preview;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.Base64;
import java.util.Optional;

import org.apache.commons.io.IOUtils;

import org.flow.core.preview.FilePreview;
import org.flow.core.preview.FileRenderer;

import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

@SuperBuilder
@NoArgsConstructor
abstract class AbstractBase64FileRenderer implements FileRenderer {
    @Override
    public FilePreview render(String extension, InputStream inputStream, Optional<Charset> charset, int maxRows) throws IOException {
        if (!supports(extension)) {
            throw new IllegalArgumentException("Unsupported extension: " + extension);
        }

        var content = Base64.getEncoder().encodeToString(IOUtils.toByteArray(inputStream));
        return FilePreview.builder()
            .content(content)
            .truncated(false)
            .extension(extension)
            .type(getPreviewType())
            .build();
    }

    protected abstract FilePreview.Type getPreviewType();
}
