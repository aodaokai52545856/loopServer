package com.company.loopengine.publishing;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Process-local robot write credentials for the server-side Publisher.
 * Tokens come from a mounted file or Vault adapter — never from node tasks,
 * database fields, remote URLs, or Git command arguments.
 */
public final class RobotCredentialProvider {
    private final RobotIdentity identity;
    private final Supplier<String> writeTokenSource;

    private RobotCredentialProvider(RobotIdentity identity, Supplier<String> writeTokenSource) {
        this.identity = Objects.requireNonNull(identity, "identity");
        this.writeTokenSource = Objects.requireNonNull(writeTokenSource, "writeTokenSource");
    }

    public static RobotCredentialProvider fromMountedFile(Path tokenFile, RobotIdentity identity) {
        Objects.requireNonNull(tokenFile, "tokenFile");
        Path absolute = tokenFile.toAbsolutePath().normalize();
        return new RobotCredentialProvider(identity, () -> readMountedToken(absolute));
    }

    /**
     * Vault (or other secret-manager) adapter: the supplier must resolve the write token
     * in-process and must not embed it into URLs or argv.
     */
    public static RobotCredentialProvider fromVaultAdapter(
            Supplier<String> vaultTokenSupplier,
            RobotIdentity identity) {
        Objects.requireNonNull(vaultTokenSupplier, "vaultTokenSupplier");
        return new RobotCredentialProvider(identity, vaultTokenSupplier);
    }

    public RobotIdentity identity() {
        return identity;
    }

    /** Returns the write token for the current process only. */
    public String writeToken() {
        String token = writeTokenSource.get();
        return token == null ? "" : token;
    }

    private static String readMountedToken(Path tokenFile) {
        try {
            return Files.readString(tokenFile, StandardCharsets.UTF_8).strip();
        } catch (IOException ex) {
            throw new IllegalStateException("ROBOT_TOKEN_UNREADABLE: " + tokenFile, ex);
        }
    }

    public record RobotIdentity(String name, String email) {
        public RobotIdentity {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(email, "email");
            if (name.isBlank()) {
                throw new IllegalArgumentException("robot name must not be blank");
            }
            if (email.isBlank()) {
                throw new IllegalArgumentException("robot email must not be blank");
            }
        }
    }
}
