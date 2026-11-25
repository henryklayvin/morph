package io.github.henryklayvin.morph.contracts;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/** Everything a provider may touch while converting. */
public interface ConversionContext {

    Capability capability();

    /** Read-only sources, in the order requested by the user. Always at least one. */
    List<SourceFile> inputs();

    FormatDescriptor target();

    ResolvedOptions options();

    ResourceLimits limits();

    CancellationToken cancellation();

    /** Private directory of this conversion; erased when it finishes, whatever the outcome. */
    Path workDirectory();

    /** Reserves the path of the next output inside the work directory. */
    Path newOutputFile() throws IOException;

    /** Reports progress in {@code [0, 1]}. */
    void progress(double fraction);

    /** Reports something that actually happened to this file and that the user should know. */
    void warn(String message);

    default SourceFile singleInput() {
        if (inputs().size() != 1) {
            throw new IllegalStateException("Expected exactly one input");
        }
        return inputs().getFirst();
    }
}
