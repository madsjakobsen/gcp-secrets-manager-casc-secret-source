package io.jenkins.plugins.casc.secretmanager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.api.gax.rpc.ApiException;
import com.google.api.gax.rpc.StatusCode;
import com.google.cloud.secretmanager.v1.AccessSecretVersionResponse;
import com.google.cloud.secretmanager.v1.SecretManagerServiceClient;
import com.google.cloud.secretmanager.v1.SecretPayload;
import com.google.protobuf.ByteString;
import hudson.ExtensionList;
import io.jenkins.plugins.casc.ConfigurationContext;
import io.jenkins.plugins.casc.ConfiguratorRegistry;
import io.jenkins.plugins.casc.SecretSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.zip.CRC32C;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junitpioneer.jupiter.SetEnvironmentVariable;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@WithJenkins
@ExtendWith(MockitoExtension.class)
class GcpSecretManagerSecretSourceTest {

    @Mock
    private SecretManagerServiceClient mockClient;

    private GcpSecretManagerSecretSource secretSource;

    @BeforeEach
    public void createMockSecretSourceClient() {
        secretSource = new GcpSecretManagerSecretSource() {
            @Override
            protected SecretManagerServiceClient createClient() {
                return mockClient;
            }
        };
        secretSource.init();
    }

    private static AccessSecretVersionResponse buildResponse(String name, String value) {
        byte[] data = value.getBytes(StandardCharsets.UTF_8);
        CRC32C checksum = new CRC32C();
        checksum.update(data, 0, data.length);

        return AccessSecretVersionResponse.newBuilder()
                .setName(name)
                .setPayload(SecretPayload.newBuilder()
                        .setData(ByteString.copyFrom(data))
                        .setDataCrc32C(checksum.getValue())
                        .build())
                .build();
    }

    @Test
    @DisplayName("should return empty for secrets without prefix")
    void shouldReturnEmptyForSecretsWithoutPrefix() throws IOException {
        // Verifies that secrets without the GCP prefix are ignored and return empty.

        // Arrange
        // (No additional setup needed - secretSource is initialized in @BeforeEach)

        // Act
        Optional<String> resolvedValue = secretSource.reveal("other-secret");

        // Assert
        assertThat(resolvedValue).isEmpty();
    }

    @Test
    @DisplayName("should fetch secret from GCP")
    void shouldFetchSecretFromGcp() throws IOException {
        // Verifies that secrets with the correct prefix are fetched from GCP Secret Manager.

        // Arrange
        String path = "projects/p/secrets/my-secret/versions/latest";
        when(mockClient.accessSecretVersion(path)).thenReturn(buildResponse(path, "secret-value"));

        // Act
        Optional<String> resolvedValue = secretSource.reveal(GcpSecretManagerSecretSource.getDefaultPrefix() + path);

        // Assert
        assertThat(resolvedValue).contains("secret-value");
        verify(mockClient).accessSecretVersion(path);
    }

    @Test
    @SetEnvironmentVariable(key = "GCP_SECRET_MANAGER_PREFIX", value = "myprefix:")
    @DisplayName("should fetch secret from GCP using custom prefix from environment variable")
    void shouldFetchSecretFromGcpUsingCustomPrefix(JenkinsRule j) throws IOException {
        // Verifies that custom prefixes set via environment variable work with CasC context.

        // Arrange

        // Re-initialize after environment variable is set
        secretSource.init();

        String prefix = "myprefix:";
        String secretValue = "secret-value";

        String path = "projects/p/secrets/my-secret/versions/latest";
        when(mockClient.accessSecretVersion(path)).thenReturn(buildResponse(path, secretValue));

        final var registry = ConfiguratorRegistry.get();
        ConfigurationContext context = new ConfigurationContext(registry);

        ExtensionList<SecretSource> secretSources = j.jenkins.getExtensionList(SecretSource.class);
        secretSources.removeAll(secretSources.stream()
                .filter(s -> s instanceof GcpSecretManagerSecretSource)
                .toList());
        secretSources.add(0, secretSource);

        // Act
        String resolvedValue = context.getSecretSourceResolver().resolve("${" + prefix + path + "}");

        // Assert
        assertThat(resolvedValue).contains(secretValue);
        verify(mockClient).accessSecretVersion(path);
        verify(mockClient).close();
    }

    @Test
    @DisplayName("should cache secrets")
    void shouldCacheSecrets() throws IOException {
        // Verifies that repeated secret requests are cached and only fetched once from GCP.

        // Arrange
        String path = "projects/p/secrets/cached/versions/latest";
        when(mockClient.accessSecretVersion(path)).thenReturn(buildResponse(path, "cached-value"));

        // Act
        secretSource.reveal(GcpSecretManagerSecretSource.getDefaultPrefix() + path);
        secretSource.reveal(GcpSecretManagerSecretSource.getDefaultPrefix() + path);
        secretSource.reveal(GcpSecretManagerSecretSource.getDefaultPrefix() + path);

        // Assert
        verify(mockClient, times(1)).accessSecretVersion(path);
    }

    @Test
    @DisplayName("should throw on data corruption")
    void shouldThrowOnDataCorruption() {
        // Verifies that data corruption is detected via CRC32C checksum and throws IOException.

        // Arrange
        String path = "projects/p/secrets/corrupted/versions/latest";

        AccessSecretVersionResponse badResponse = AccessSecretVersionResponse.newBuilder()
                .setName(path)
                .setPayload(SecretPayload.newBuilder()
                        .setData(ByteString.copyFromUtf8("value"))
                        .setDataCrc32C(12345L) // wrong checksum
                        .build())
                .build();

        when(mockClient.accessSecretVersion(path)).thenReturn(badResponse);

        // Act & Assert
        assertThatThrownBy(() -> secretSource.reveal("gcpSecretManager:" + path))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("Data corruption");
    }

    @Test
    @DisplayName("should wrap API exceptions in IOException")
    void shouldWrapApiExceptions() {
        // Verifies that GCP API exceptions are wrapped in IOException for consistent error handling.

        // Arrange
        String path = "projects/p/secrets/error/versions/latest";

        ApiException apiException = new ApiException(
                new RuntimeException("Secret not found"),
                new StatusCode() {
                    @Override
                    public Code getCode() {
                        return Code.NOT_FOUND;
                    }

                    @Override
                    public Object getTransportCode() {
                        return 404;
                    }
                },
                false);

        when(mockClient.accessSecretVersion(path)).thenThrow(apiException);

        // Act & Assert
        assertThatThrownBy(() -> secretSource.reveal(GcpSecretManagerSecretSource.getDefaultPrefix() + path))
                .isInstanceOf(IOException.class)
                .hasCauseInstanceOf(ApiException.class);
    }

    @Test
    @DisplayName("should throw IOException when createClient fails")
    void shouldThrowWhenCreateClientFails() {
        // Verifies that IOException from client creation is properly propagated.

        // Arrange
        GcpSecretManagerSecretSource failingSecretSource = new GcpSecretManagerSecretSource() {
            @Override
            protected SecretManagerServiceClient createClient() throws IOException {
                throw new IOException("Failed to authenticate with GCP");
            }
        };
        failingSecretSource.init();

        String path = "projects/p/secrets/my-secret/versions/latest";

        // Act & Assert
        assertThatThrownBy(() -> failingSecretSource.reveal(GcpSecretManagerSecretSource.getDefaultPrefix() + path))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("Failed to authenticate with GCP");
    }

    @Test
    @DisplayName("should throw NullPointerException for null secret")
    void shouldThrowForNullSecret() {
        // Verifies that null input throws NullPointerException.

        // Act & Assert
        assertThatThrownBy(() -> secretSource.reveal(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("should handle secret with prefix but empty path")
    void shouldHandleSecretWithPrefixButEmptyPath() {
        // Verifies behavior when prefix is present but path is empty (e.g., "gcpSecretManager:").

        // Arrange
        String emptyPath = "";
        when(mockClient.accessSecretVersion(emptyPath))
                .thenThrow(new ApiException(
                        new RuntimeException("Invalid secret path"),
                        new StatusCode() {
                            @Override
                            public Code getCode() {
                                return Code.INVALID_ARGUMENT;
                            }

                            @Override
                            public Object getTransportCode() {
                                return 400;
                            }
                        },
                        false));

        // Act & Assert
        assertThatThrownBy(() -> secretSource.reveal(GcpSecretManagerSecretSource.getDefaultPrefix()))
                .isInstanceOf(IOException.class)
                .hasCauseInstanceOf(ApiException.class);
    }
}
