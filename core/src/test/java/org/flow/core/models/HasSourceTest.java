package org.flow.core.models;

import io.micronaut.http.multipart.FormFieldMetadata;
import io.micronaut.core.io.buffer.ReadBufferFactory;

import org.junit.jupiter.api.Test;

import io.micronaut.http.multipart.CompletedFileUpload;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HasSourceTest {

    /** 使用 Micronaut 5 的真实内存上传对象验证无后缀文件错误。 */
    @Test
    void shouldThrowIllegalArgumentWhenImportingFileWithoutExtension() throws Exception {
        // Given a file whose name has no extension
        try (CompletedFileUpload fileUpload = CompletedFileUpload.ofMemory(
            new FormFieldMetadata("file", "no-extension", null),
            ReadBufferFactory.getJdkFactory().copyOf(java.nio.ByteBuffer.allocate(0)))) {

            // When - Then the unsupported type is reported as an IllegalArgumentException
            // instead of a StringIndexOutOfBoundsException raised by substring(-1)
            assertThatThrownBy(() -> HasSource.readSourceFile(null, fileUpload, (source, name) ->
            {
            }))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Cannot import file of type");
        }
    }
}
