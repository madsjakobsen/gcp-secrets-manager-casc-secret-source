package io.jenkins.plugins.casc.secretmanager;

import com.google.cloud.secretmanager.v1.AccessSecretVersionResponse;
import com.google.cloud.secretmanager.v1.SecretManagerServiceClient;
import com.google.protobuf.ByteString;
import hudson.Extension;
import io.jenkins.plugins.casc.SecretSource;
import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.zip.CRC32C;
import java.util.zip.Checksum;

@Extension
public class GcpSecretManagerSecretSource extends SecretSource {

    private static final Logger LOGGER = Logger.getLogger(GcpSecretManagerSecretSource.class.getName());

    // Environment variable names
    private static final String ENV_PREFIX = "GCP_SECRET_MANAGER_PREFIX";

    // Default values
    public static final String DEFAULT_PREFIX = "gcpSecretManager:";

    // Instance-level cache for storing secret values
    private ConcurrentHashMap<String, String> secretCache;
    private String prefix;

    @Override
    public void init() {
        prefix = getConfiguredPrefix();
        secretCache = new ConcurrentHashMap<>();

        LOGGER.log(Level.FINE, "GCP Secret Manager initialized with prefix: {0}", prefix);
    }

    /**
     * Get the secret prefix from environment variable or use default
     */
    private String getConfiguredPrefix() {
        String value = System.getenv(ENV_PREFIX);
        if (value == null || value.trim().isEmpty()) {
            value = System.getProperty(ENV_PREFIX, DEFAULT_PREFIX);
        }

        return value;
    }

    /**
     * Creates the Secret Manager client. Protected to allow test overriding.
     */
    protected SecretManagerServiceClient createClient() throws IOException {
        return SecretManagerServiceClient.create();
    }

    /**
     * Checks if the secret uses the configured prefix
     */
    private boolean hasConfiguredPrefix(String secret) {
        return prefix != null && !prefix.isEmpty() && secret.startsWith(prefix);
    }

    @Override
    public Optional<String> reveal(String secret) throws IOException {
        // Only handle secrets with the configured prefix
        if (!hasConfiguredPrefix(secret)) {
            return Optional.empty();
        }

        // Strip the prefix
        String secretPath = secret.substring(prefix.length());

        String cached = secretCache.get(secretPath);
        if (cached != null) {
            LOGGER.log(Level.FINE, "Read Secret from cache: {0}", secretPath);
            return Optional.of(cached);
        }

        try (SecretManagerServiceClient client = createClient()) {
            AccessSecretVersionResponse response = client.accessSecretVersion(secretPath);

            ByteString payloadData = response.getPayload().getData();

            // Verify checksum.
            byte[] data = payloadData.toByteArray();
            Checksum checksum = new CRC32C();
            checksum.update(data, 0, data.length);

            if (response.getPayload().getDataCrc32C() != checksum.getValue()) {
                throw new IOException("Data corruption detected for secret: %s".formatted(response.getName()));
            }

            // Get the secret payload.
            String payloadString = payloadData.toStringUtf8();

            LOGGER.log(Level.FINE, "Read Secret from Secret Manager: {0}", secretPath);

            // Cache the result
            secretCache.put(secretPath, payloadString);

            return Optional.of(payloadString);
        } catch (com.google.api.gax.rpc.ApiException e) {
            LOGGER.log(Level.SEVERE, "Failed to access secret: %s".formatted(secretPath), e);
            throw new IOException(e);
        } catch (IOException e) {
            // Re-throw IOExceptions we explicitly threw (e.g., data corruption)
            throw e;
        } catch (Exception e) {
            // Wrap any other unexpected exceptions
            String msg = "Unexpected error accessing secret: %s".formatted(secretPath);
            LOGGER.log(Level.SEVERE, msg, e);
            throw new IOException(msg, e);
        }
    }

    public static String getDefaultPrefix() {
        return DEFAULT_PREFIX;
    }

    public String getPrefix() {
        return prefix;
    }
}
